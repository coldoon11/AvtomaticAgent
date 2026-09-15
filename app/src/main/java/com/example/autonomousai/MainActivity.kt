package com.example.autonomousai

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val backend = BackendClient()
    private lateinit var launcher: AppLauncher
    private lateinit var phone: PhoneCallHelper
    private lateinit var voice: VoiceAssistantController
    private val worker = Executors.newSingleThreadExecutor()
    private val messages = mutableStateListOf<ChatMessage>()

    private var input by mutableStateOf("")
    private var status by mutableStateOf("Готов")
    private var busy by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var gestureRunning by mutableStateOf(false)
    private var voiceActive by mutableStateOf(false)
    private var notificationAccessEnabled by mutableStateOf(false)
    private var autoReplyEnabled by mutableStateOf(false)
    private var aiCallRecording by mutableStateOf(false)
    private lateinit var prefs: android.content.SharedPreferences
    private var backendUrl by mutableStateOf("")
    private var apiToken by mutableStateOf("")
    private var pendingCallTarget: String? = null
    private var pendingAiCall: AiCallCommand? = null

    private val callRegex = Regex(
        "^\\s*(?:позвони|позвонить|набери|позвоним|call)\\s+(.+?)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private data class AiCallCommand(val target: String, val task: String)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startGestureService() else status = "Для жестов нужна камера"
    }

    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoiceMode() else status = "Для голосового режима нужен микрофон"
    }

    private val callPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val target = pendingCallTarget
        pendingCallTarget = null
        if (target != null) {
            val result = runCatching { phone.placeCall(target) }
                .getOrElse { "Не удалось начать звонок: ${it.message}" }
            messages += ChatMessage(ChatMessage.Role.ASSISTANT, result)
            if (voiceActive) voice.speak(result)
        }
    }

    private val aiCallContactPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val command = pendingAiCall
        pendingAiCall = null
        if (!granted || command == null) {
            val answer = "Для AI-звонка по имени нужен доступ к контактам. Можно также произнести номер полностью."
            status = answer
            messages += ChatMessage(ChatMessage.Role.SYSTEM, answer)
            if (voiceActive) voice.speak(answer)
            return@registerForActivityResult
        }
        val answer = startAiCall(command)
        messages += ChatMessage(ChatMessage.Role.ASSISTANT, answer)
        if (voiceActive) voice.speak(answer)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        backendUrl = prefs.getString("backend_url", "http://192.168.1.2:8765").orEmpty()
        apiToken = prefs.getString("api_token", "").orEmpty()
        autoReplyEnabled = prefs.getBoolean("auto_reply_enabled", false)
        aiCallRecording = prefs.getBoolean("ai_call_recording", false)
        launcher = AppLauncher(this)
        phone = PhoneCallHelper(this)
        voice = VoiceAssistantController(
            this,
            onText = { text -> runOnUiThread { submit(text, fromVoice = true) } },
            onState = { value -> runOnUiThread { status = value } },
        )
        messages += ChatMessage(
            ChatMessage.Role.SYSTEM,
            "Autonomous AI v0.5: голос, сообщения, AI-звонки через SIP, обычные звонки, приложения и жесты.",
        )
        setContent { AppUi() }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = isAccessibilityServiceEnabled()
        gestureRunning = HandGestureService.running
        notificationAccessEnabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    override fun onDestroy() {
        voice.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun submit(raw: String, fromVoice: Boolean = false) {
        val text = raw.trim()
        if (text.isBlank() || busy) return
        messages += ChatMessage(ChatMessage.Role.USER, text)
        input = ""

        val aiCall = parseAiCall(text)
        if (aiCall != null) {
            val response = startAiCall(aiCall)
            messages += ChatMessage(ChatMessage.Role.ASSISTANT, response)
            if (voiceActive) voice.speak(response)
            return
        }

        val call = callRegex.find(text)
        if (call != null) {
            val response = handleCall(call.groupValues[1].trim())
            messages += ChatMessage(ChatMessage.Role.ASSISTANT, response)
            if (voiceActive) voice.speak(response)
            return
        }

        val local = runCatching { launcher.tryHandle(text) }.getOrNull()
        if (local != null) {
            messages += ChatMessage(ChatMessage.Role.ASSISTANT, local)
            if (voiceActive) voice.speak(local)
            return
        }

        if (backendUrl.isBlank()) {
            status = "Укажи URL backend"
            val answer = "Сначала укажи адрес backend-сервера."
            messages += ChatMessage(ChatMessage.Role.SYSTEM, answer)
            if (voiceActive && fromVoice) voice.speak(answer)
            return
        }

        busy = true
        status = "Думаю…"
        worker.execute {
            val result = runCatching {
                backend.chat(backendUrl, apiToken, text, ScreenAccessibilityService.snapshot().text)
            }
            runOnUiThread {
                busy = false
                result.onSuccess {
                    messages += ChatMessage(ChatMessage.Role.ASSISTANT, it)
                    status = "Готов"
                    if (voiceActive) voice.speak(it)
                }.onFailure {
                    status = "Ошибка: ${it.message}"
                    messages += ChatMessage(ChatMessage.Role.SYSTEM, status)
                    if (voiceActive && fromVoice) voice.speak("Не удалось связаться с сервером")
                }
            }
        }
    }

    private fun parseAiCall(text: String): AiCallCommand? {
        val original = text.trim()
        val lower = original.lowercase(Locale.getDefault())
        val prefixes = listOf("ии позвони ", "ai позвони ", "ai call ", "агент позвони ", "агент набери ")
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return null
        val rest = original.substring(prefix.length).trim()
        if (rest.isBlank()) return null
        val parts = rest.split(":", limit = 2)
        val target = parts.first().trim()
        if (target.isBlank()) return null
        val task = parts.getOrNull(1)?.trim().orEmpty().ifBlank {
            "Поздоровайся, скажи, что ты ИИ-помощник, и спроси, удобно ли сейчас разговаривать."
        }
        return AiCallCommand(target, task)
    }

    private fun startAiCall(command: AiCallCommand): String {
        if (backendUrl.isBlank()) return "Сначала укажи URL backend-сервера."

        if (!phone.looksLikeNumber(command.target) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingAiCall = command
            aiCallContactPermission.launch(Manifest.permission.READ_CONTACTS)
            return "Разреши доступ к контактам, чтобы найти номер «${command.target}»."
        }

        val number = phone.resolveNumber(command.target)
            ?: return "Не нашёл номер для «${command.target}». Скажи номер в международном формате, например +370…"

        busy = true
        status = "AI готовит звонок…"
        worker.execute {
            val result = runCatching {
                backend.aiCall(backendUrl, apiToken, number, command.task, aiCallRecording)
            }
            runOnUiThread {
                busy = false
                result.onSuccess {
                    messages += ChatMessage(ChatMessage.Role.ASSISTANT, it)
                    status = "AI-звонок запущен"
                    if (voiceActive) voice.speak(it)
                }.onFailure {
                    val error = "AI-звонок не запущен: ${it.message}"
                    messages += ChatMessage(ChatMessage.Role.SYSTEM, error)
                    status = error
                    if (voiceActive) voice.speak("Не удалось начать AI-звонок")
                }
            }
        }
        return "Передаю звонок AI-агенту. Он представится как ИИ-помощник и будет выполнять указанную цель."
    }

    private fun handleCall(target: String): String {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.CALL_PHONE
        }
        if (!phone.looksLikeNumber(target) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.READ_CONTACTS
        }
        if (needed.isNotEmpty()) {
            pendingCallTarget = target
            callPermissions.launch(needed.toTypedArray())
            return "Нужно разрешение Android для обычного звонка. Показываю запрос."
        }
        return runCatching { phone.placeCall(target) }
            .getOrElse { "Не удалось начать звонок: ${it.message}" }
    }

    private fun toggleVoice() {
        if (voiceActive) {
            voice.stop()
            voiceActive = false
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceMode()
    }

    private fun startVoiceMode() {
        voice.start()
        voiceActive = voice.isActive()
    }

    private fun startGestureService() {
        if (!accessibilityEnabled || !ScreenAccessibilityService.isConnected()) {
            status = "Сначала включи Accessibility для Autonomous AI"
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            status = "Разреши показ поверх других приложений"
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, HandGestureService::class.java))
        gestureRunning = true
        status = "Жесты активны"
    }

    private fun stopGestureService() {
        stopService(Intent(this, HandGestureService::class.java))
        gestureRunning = false
        status = "Жесты выключены"
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, ScreenAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    @Composable
    private fun AppUi() {
        MaterialTheme {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text("Autonomous AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("голос • сообщения • AI-звонки • приложения • жесты", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Card {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            OutlinedTextField(
                                value = backendUrl,
                                onValueChange = { backendUrl = it; prefs.edit().putString("backend_url", it).apply() },
                                label = { Text("URL backend") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            OutlinedTextField(
                                value = apiToken,
                                onValueChange = { apiToken = it; prefs.edit().putString("api_token", it).apply() },
                                label = { Text("Токен backend") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                    Text(if (accessibilityEnabled) "Accessibility ✓" else "Accessibility")
                                }
                                Button(onClick = { if (gestureRunning) stopGestureService() else startGestureService() }) {
                                    Text(if (gestureRunning) "Стоп жесты" else "Жесты руки")
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { toggleVoice() }) {
                                    Text(if (voiceActive) "Стоп голос" else "Голосовой режим")
                                }
                                Button(onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                                    Text(if (notificationAccessEnabled) "Уведомления ✓" else "Доступ к сообщениям")
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = autoReplyEnabled,
                                    onCheckedChange = {
                                        autoReplyEnabled = it
                                        prefs.edit().putBoolean("auto_reply_enabled", it).apply()
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Автоответ в Telegram / WhatsApp / Signal")
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = aiCallRecording,
                                    onCheckedChange = {
                                        aiCallRecording = it
                                        prefs.edit().putBoolean("ai_call_recording", it).apply()
                                    },
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Запрашивать запись AI-звонка")
                            }
                            Text(
                                "AI-звонок: «ИИ позвони Мама: узнай, сможет ли она созвониться вечером». Запись также должна быть разрешена на backend.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(status, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(messages) { msg ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        when (msg.role) {
                                            ChatMessage.Role.USER -> "Ты"
                                            ChatMessage.Role.ASSISTANT -> "AI"
                                            ChatMessage.Role.SYSTEM -> "Система"
                                        },
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(msg.text)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("Сообщение / «ИИ позвони Мама: ...»") },
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { submit(input) }, enabled = input.isNotBlank() && !busy) { Text("Отпр.") }
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_OPEN_FROM_BUBBLE = "com.example.autonomousai.OPEN_FROM_BUBBLE"
    }
}
