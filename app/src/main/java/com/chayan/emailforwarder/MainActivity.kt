package com.chayan.emailforwarder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var switchBot: SwitchMaterial
    private lateinit var tvStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var etGmailAddress: TextInputEditText
    private lateinit var etGmailPassword: TextInputEditText
    private lateinit var etBotToken: TextInputEditText
    private lateinit var etGroupId: TextInputEditText
    private lateinit var btnSaveConfig: MaterialButton
    private lateinit var btnBatteryOpt: MaterialButton

    private val logReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val log = intent?.getStringExtra(ForwarderService.EXTRA_LOG_MSG) ?: return
            appendLog(log)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadPreferences()
        updateStatusUI(ForwarderService.isServiceActive)

        switchBot.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startBotService()
            } else {
                stopBotService()
            }
        }

        btnSaveConfig.setOnClickListener {
            savePreferences()
            Toast.makeText(this, "Configuration saved successfully!", Toast.LENGTH_SHORT).show()
        }

        btnBatteryOpt.setOnClickListener {
            requestIgnoreBatteryOptimization()
        }

        val filter = IntentFilter(ForwarderService.BROADCAST_LOG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(logReceiver, filter)
        }
    }

    private fun initViews() {
        switchBot = findViewById(R.id.switchBot)
        tvStatus = findViewById(R.id.tvStatus)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        etGmailAddress = findViewById(R.id.etGmailAddress)
        etGmailPassword = findViewById(R.id.etGmailPassword)
        etBotToken = findViewById(R.id.etBotToken)
        etGroupId = findViewById(R.id.etGroupId)
        btnSaveConfig = findViewById(R.id.btnSaveConfig)
        btnBatteryOpt = findViewById(R.id.btnBatteryOpt)
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        etGmailAddress.setText(prefs.getString("gmail_address", ""))
        etGmailPassword.setText(prefs.getString("gmail_password", ""))
        etBotToken.setText(prefs.getString("bot_token", "9277450202:AAEVaqYpAkG0Mpz_a1V6RGZvSKo4RCU3M-Q"))
        etGroupId.setText(prefs.getString("group_id", ""))
    }

    private fun savePreferences() {
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("gmail_address", etGmailAddress.text.toString().trim())
            .putString("gmail_password", etGmailPassword.text.toString().trim())
            .putString("bot_token", etBotToken.text.toString().trim())
            .putString("group_id", etGroupId.text.toString().trim())
            .apply()
    }

    private fun startBotService() {
        savePreferences()
        val serviceIntent = Intent(this, ForwarderService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        updateStatusUI(true)
        appendLog("🚀 Bot activated by user switch.")
    }

    private fun stopBotService() {
        val serviceIntent = Intent(this, ForwarderService::class.java)
        stopService(serviceIntent)
        updateStatusUI(false)
        appendLog("🛑 Bot stopped.")
    }

    private fun updateStatusUI(running: Boolean) {
        switchBot.isChecked = running
        if (running) {
            tvStatus.text = "● RUNNING (1s interval)"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_green))
        } else {
            tvStatus.text = "● STOPPED"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_red))
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val currentLogs = tvLogs.text.toString()
        val newLog = "[$time] $message\n" + currentLogs
        tvLogs.text = newLog
        scrollLogs.smoothScrollTo(0, 0)
    }

    private fun requestIgnoreBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Battery restrictions already disabled!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(logReceiver)
        super.onDestroy()
    }
}
