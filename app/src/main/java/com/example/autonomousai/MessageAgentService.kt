package com.example.autonomousai

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class MessageAgentService : NotificationListenerService() {
    private val backend = BackendClient()
    private val worker = Executors.newSingleThreadExecutor()
    private val recent = ConcurrentHashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val item = sbn ?: return
        val prefs = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        if (!prefs.getBoolean("auto_reply_enabled", false)) return
        if (item.packageName !in SUPPORTED_MESSENGERS) return

        val notification = item.notification ?: return
        val replyAction = notification.actions
            ?.firstOrNull { action -> action.remoteInputs?.isNotEmpty() == true }
            ?: return

        val extras = notification.extras
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isBlank()) return

        val key = "${item.packageName}|$sender|$text"
        val now = System.currentTimeMillis()
        val previous = recent.put(key, now)
        if (previous != null && now - previous < 120_000L) return
        recent.entries.removeIf { now - it.value > 10 * 60_000L }

        val baseUrl = prefs.getString("backend_url", "").orEmpty()
        val token = prefs.getString("api_token", "").orEmpty()
        if (baseUrl.isBlank()) return

        worker.execute {
            val prompt = buildString {
                append("Ты отвечаешь на входящее сообщение в мессенджере от имени владельца телефона. ")
                append("Ответ должен быть естественным, коротким и по делу. Не обещай действий, которых не было. ")
                append("Не сообщай пароли, коды, банковские данные и другие секреты. ")
                append("Если сообщение просит оплату, перевод денег, пароль, код подтверждения или другое рискованное действие, ответь, что владелец ответит лично. ")
                if (sender.isNotBlank()) append("Отправитель: $sender. ")
                append("Сообщение: $text\n")
                append("Верни только текст ответа без кавычек и пояснений.")
            }

            val reply = runCatching {
                backend.chat(baseUrl, token, prompt, "")
            }.getOrNull()?.trim()?.take(800).orEmpty()

            if (reply.isNotBlank()) {
                runCatching { sendReply(replyAction, reply) }
            }
        }
    }

    private fun sendReply(action: Notification.Action, reply: String) {
        val remoteInputs = action.remoteInputs ?: return
        if (remoteInputs.isEmpty()) return
        val intent = Intent()
        val results = Bundle()
        remoteInputs.forEach { input -> results.putCharSequence(input.resultKey, reply) }
        RemoteInput.addResultsToIntent(remoteInputs, intent, results)
        action.actionIntent.send(this, 0, intent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private val SUPPORTED_MESSENGERS = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.whatsapp",
            "org.thoughtcrime.securesms",
            "com.facebook.orca",
        )
    }
}
