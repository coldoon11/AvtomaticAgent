package com.example.autonomousai

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class BackendClient {
    private fun normalizedBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        require(trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            "URL сервера должен начинаться с http:// или https://"
        }
        return trimmed
    }

    private fun connection(baseUrl: String, path: String, token: String, method: String): HttpURLConnection {
        val conn = URL(normalizedBaseUrl(baseUrl) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 7_000
        conn.readTimeout = 190_000
        conn.setRequestProperty("Accept", "application/json")
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        return conn
    }

    fun health(baseUrl: String, token: String): String {
        val conn = connection(baseUrl, "/health", token, "GET")
        return try {
            val body = readText(conn)
            if (conn.responseCode !in 200..299) error(serverError(conn.responseCode, body))
            JSONObject(body).optString("status", "ok")
        } finally {
            conn.disconnect()
        }
    }

    fun chat(baseUrl: String, token: String, text: String, screenContext: String): String {
        val conn = connection(baseUrl, "/chat", token, "POST")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val payload = JSONObject()
            .put("text", text)
            .put("screen_context", screenContext)
            .put("device", "android")
            .toString()
            .toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(payload) }
        return try {
            val body = readText(conn)
            if (conn.responseCode !in 200..299) error(serverError(conn.responseCode, body))
            JSONObject(body).optString("answer").ifBlank { "Сервер вернул пустой ответ." }
        } finally {
            conn.disconnect()
        }
    }

    fun vision(baseUrl: String, token: String, prompt: String, imageJpeg: ByteArray, screenContext: String): String {
        require(imageJpeg.isNotEmpty()) { "Пустой снимок экрана" }
        val conn = connection(baseUrl, "/vision", token, "POST")
        conn.doOutput = true
        conn.readTimeout = 190_000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("screen_context", screenContext)
            .put("image_base64", Base64.encodeToString(imageJpeg, Base64.NO_WRAP))
            .toString()
            .toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(payload) }
        return try {
            val body = readText(conn)
            if (conn.responseCode !in 200..299) error(serverError(conn.responseCode, body))
            JSONObject(body).optString("answer").ifBlank { "Сервер вернул пустой ответ." }
        } finally {
            conn.disconnect()
        }
    }

    fun tts(baseUrl: String, token: String, text: String): ByteArray {
        val conn = connection(baseUrl, "/tts", token, "POST")
        conn.doOutput = true
        conn.readTimeout = 60_000
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "audio/mpeg")
        val payload = JSONObject().put("text", text).toString().toByteArray(Charsets.UTF_8)
        conn.outputStream.use { it.write(payload) }
        return try {
            if (conn.responseCode !in 200..299) {
                val body = readText(conn)
                error(serverError(conn.responseCode, body))
            }
            BufferedInputStream(conn.inputStream).use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun readText(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        return BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
    }

    private fun serverError(code: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
        return if (message.isNotBlank()) "Сервер: $message" else "HTTP $code"
    }
}
