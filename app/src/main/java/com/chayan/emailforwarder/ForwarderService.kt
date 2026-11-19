package com.chayan.emailforwarder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.mail.*
import javax.mail.internet.MimeMultipart

class ForwarderService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var wakeLock: PowerManager.WakeLock? = null
    private var isRunning = false

    companion object {
        const val ACTION_STOP = "com.chayan.emailforwarder.STOP"
        const val BROADCAST_LOG = "com.chayan.emailforwarder.LOG"
        const val EXTRA_LOG_MSG = "log_message"
        var isServiceActive = false
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EmailForwarder::WakeLock").apply {
            acquire(24 * 60 * 60 * 1000L) // 24 hours lock
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isRunning) {
            isRunning = true
            isServiceActive = true
            startForeground(1001, buildNotification("Monitoring Gmail inbox..."))
            startMonitoringLoop()
        }

        return START_STICKY
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
            val gmailAddress = prefs.getString("gmail_address", "chayan0000001@gmail.com") ?: ""
            val gmailPassword = prefs.getString("gmail_password", "tzlerhnvdnxgaxuq") ?: ""
            val botToken = prefs.getString("bot_token", "9277450202:AAEVaqYpAkG0Mpz_a1V6RGZvSKo4RCU3M-Q") ?: ""
            val groupId = prefs.getString("group_id", "-1004417279988") ?: ""

            val telegram = TelegramClient(botToken, groupId)
            sendLog("🟢 Bot started. Connecting to $gmailAddress...")

            var lastWatermark = prefs.getInt("last_imap_id", 0)

            while (isActive && isRunning) {
                var store: Store? = null
                var inbox: Folder? = null
                try {
                    val props = Properties().apply {
                        put("mail.store.protocol", "imaps")
                        put("mail.imaps.host", "imap.gmail.com")
                        put("mail.imaps.port", "993")
                        put("mail.imaps.ssl.enable", "true")
                        put("mail.imaps.timeout", "8000")
                        put("mail.imaps.connectiontimeout", "8000")
                    }

                    val session = Session.getInstance(props)
                    store = session.getStore("imaps")
                    store.connect("imap.gmail.com", gmailAddress, gmailPassword)

                    inbox = store.getFolder("INBOX")
                    inbox.open(Folder.READ_ONLY)

                    val totalMessages = inbox.messageCount

                    // First run: establish watermark so old emails aren't spammed
                    if (lastWatermark == 0) {
                        lastWatermark = totalMessages
                        prefs.edit().putInt("last_imap_id", lastWatermark).apply()
                        sendLog("Initial watermark set to $lastWatermark. Listening for NEW emails...")
                    } else if (totalMessages > lastWatermark) {
                        val newCount = totalMessages - lastWatermark
                        sendLog("📬 Detected $newCount new email(s)!")

                        for (i in (lastWatermark + 1)..totalMessages) {
                            try {
                                val msg = inbox.getMessage(i)
                                processAndForward(msg, telegram, gmailAddress)
                                lastWatermark = i
                                prefs.edit().putInt("last_imap_id", lastWatermark).apply()
                            } catch (e: Exception) {
                                sendLog("⚠️ Error reading message $i: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Silent retry on network blips
                } finally {
                    try { inbox?.close(false) } catch (_: Exception) {}
                    try { store?.close() } catch (_: Exception) {}
                }

                // 1 second delay between checks for super-fast forwarding
                delay(1000)
            }
        }
    }

    private fun processAndForward(msg: Message, telegram: TelegramClient, toAddress: String) {
        val from = (msg.from?.firstOrNull()?.toString() ?: "Unknown")
        val subject = msg.subject ?: "(No Subject)"
        val sentDate = msg.sentDate ?: Date()
        val dateFormat = SimpleDateFormat("dd MMMM yyyy", Locale.ENGLISH).format(sentDate)
        val timeFormat = SimpleDateFormat("hh:mm a", Locale.ENGLISH).format(sentDate)

        val (bodyText, attachments) = extractContent(msg)

        val formattedText = """
📩 <b>NEW EMAIL RECEIVED</b>
👤 <b>From:</b> ${escapeHtml(from)}
📥 <b>To:</b> <code>${escapeHtml(toAddress)}</code>
📝 <b>Subject:</b> <b>${escapeHtml(subject)}</b>
📅 <b>Date:</b> $dateFormat
🕒 <b>Time:</b> $timeFormat
━━━━━━━━━━━━━━━━
<b>Email Body:</b>
${escapeHtml(bodyText.take(3000))}
━━━━━━━━━━━━━━━━
${if (attachments.isNotEmpty()) "📎 Attachments: ${attachments.size} file(s)" else ""}
""".trimIndent()

        val sent = telegram.sendMessage(formattedText)
        if (sent) {
            sendLog("✅ Forwarded: '$subject'")
        } else {
            sendLog("❌ Failed to forward '$subject'")
        }

        // Send attachments
        for ((fileName, fileBytes) in attachments) {
            telegram.sendDocument(fileName, fileBytes, caption = "📎 $fileName")
        }
    }

    private fun extractContent(part: Part): Pair<String, List<Pair<String, ByteArray>>> {
        var body = ""
        val attachments = mutableListOf<Pair<String, ByteArray>>()

        when {
            part.isMimeType("text/plain") -> {
                body += part.content.toString()
            }
            part.isMimeType("text/html") -> {
                val html = part.content.toString()
                body += html.replace(Regex("<[^>]*>"), " ").trim()
            }
            part.isMimeType("multipart/*") -> {
                val mp = part.content as MimeMultipart
                for (i in 0 until mp.count) {
                    val bp = mp.getBodyPart(i)
                    val disp = bp.disposition
                    if (Part.ATTACHMENT.equals(disp, ignoreCase = true) || !bp.fileName.isNullOrEmpty()) {
                        val fileName = bp.fileName ?: "attachment_$i"
                        val bytes = readBytes(bp.inputStream)
                        attachments.add(Pair(fileName, bytes))
                    } else {
                        val (childBody, childAtts) = extractContent(bp)
                        if (body.isEmpty()) body = childBody
                        attachments.addAll(childAtts)
                    }
                }
            }
        }
        return Pair(body.trim(), attachments)
    }

    private fun readBytes(inputStream: InputStream): ByteArray {
        val byteBuffer = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var len: Int
        while (inputStream.read(buffer).also { len = it } != -1) {
            byteBuffer.write(buffer, 0, len)
        }
        return byteBuffer.toByteArray()
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun sendLog(msg: String) {
        val intent = Intent(BROADCAST_LOG).apply {
            putExtra(EXTRA_LOG_MSG, msg)
        }
        sendBroadcast(intent)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, ForwarderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, "email_forwarder_channel")
            .setContentTitle("Email Forwarder Active 🟢")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "email_forwarder_channel",
                "Email Forwarder Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        isRunning = false
        isServiceActive = false
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
