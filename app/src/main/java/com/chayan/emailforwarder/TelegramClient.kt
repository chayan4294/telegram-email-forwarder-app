package com.chayan.emailforwarder

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramClient(private val botToken: String, private val chatId: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun sendMessage(htmlText: String): Boolean {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val formBody = okhttp3.FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", htmlText)
            .add("parse_mode", "HTML")
            .add("disable_web_page_preview", "true")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Fallback to plain text if HTML parse error
                    sendPlainMessage(htmlText.replace(Regex("<[^>]*>"), ""))
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun sendPlainMessage(plainText: String): Boolean {
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val formBody = okhttp3.FormBody.Builder()
            .add("chat_id", chatId)
            .add("text", plainText)
            .add("disable_web_page_preview", "true")
            .build()

        val request = Request.Builder().url(url).post(formBody).build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    fun sendDocument(fileName: String, fileBytes: ByteArray, caption: String = ""): Boolean {
        val url = "https://api.telegram.org/bot$botToken/sendDocument"
        val fileBody = fileBytes.toRequestBody("application/octet-stream".toMediaTypeOrNull())

        val multipartBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("document", fileName, fileBody)
            .addFormDataPart("caption", caption)
            .build()

        val request = Request.Builder().url(url).post(multipartBody).build()
        return try {
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }
}
