package com.example.autonomousai

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
    private var wakeEnabled by mutableStateOf(false)
    private var notificationAccessEnabled by mutableStateOf(false)
    private var autoReplyEnabled by mutableStateOf(false)
    private var aiCallRecording by mutableStateOf(false)
    private var wakeWord by mutableStateOf("Астра")
    private lateinit var prefs: android.content.SharedPreferences
    private var backendUrl by mutableStateOf("")
    private var apiToken by mutableStateOf("")
    private var pendingCallTarget: String? = null
    private var pendingAiCall: AiCallCommand? = null
    private var resumeWakeAfterVoice = false

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

    private val wakePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val micGranted = result[Manifest.permission.RECORD_AUDIO] ?: (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
        if (micGranted) startWakeService() else status = "Для фоновой Astra нужен доступ к микрофону"
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
        wakeWord = prefs.getString("wake_word", "Астра").orEmpty().ifBlank { "Астра" }
        launcher = AppLauncher(this)
        phone = PhoneCallHelper(this)
        voice = VoiceAssistantController(
            this,
            onText = { text -> runOnUiThread { submit(text, fromVoice = true) } },
            onState = { value -> runOnUiThread { status = value } },
        )
        messages += ChatMessage(
            ChatMessage.Role.SYSTEM,
            "Astra v0.6 готова. Включи фоновый режим и обращайся по имени «$wakeWord».",
        )
        setContent { AppUi() }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = isAccessibilityServiceEnabled()
        gestureRunning = HandGestureService.running
        wakeEnabled = WakeWordService.running
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
            val answer = "Сначала укажи адрес backend-сервера в настройках."
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

    private fun toggleWake() {
        if (wakeEnabled || WakeWordService.running) {
            stopWakeService()
            return
        }
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isNotEmpty()) {
            wakePermissions.launch(needed.toTypedArray())
        } else {
            startWakeService()
        }
    }

    private fun startWakeService() {
        if (voiceActive) {
            voice.stop()
            voiceActive = false
        }
        prefs.edit().putBoolean("wake_enabled", true).apply()
        ContextCompat.startForegroundService(this, Intent(this, WakeWordService::class.java))
        wakeEnabled = true
        status = "Фоновая Astra включена"
    }

    private fun stopWakeService() {
        prefs.edit().putBoolean("wake_enabled", false).apply()
        stopService(Intent(this, WakeWordService::class.java))
        wakeEnabled = false
        resumeWakeAfterVoice = false
        status = "Фоновая Astra выключена"
    }

    private fun toggleVoice() {
        if (voiceActive) {
            voice.stop()
            voiceActive = false
            status = "Голосовой разговор выключен"
            if (resumeWakeAfterVoice) {
                resumeWakeAfterVoice = false
                startWakeService()
            }
            return
        }
        if (wakeEnabled || WakeWordService.running) {
            resumeWakeAfterVoice = true
            prefs.edit().putBoolean("wake_enabled", false).apply()
            stopService(Intent(this, WakeWordService::class.java))
            wakeEnabled = false
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
            status = "Сначала включи Accessibility для Astra AI"
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
        var showSettings by remember { mutableStateOf(false) }
        val colors = if (isSystemInDarkTheme()) {
            darkColorScheme(
                primary = Color(0xFF9BB7FF),
                background = Color(0xFF0B0D10),
                surface = Color(0xFF12151A),
                surfaceVariant = Color(0xFF1B2027),
            )
        } else {
            lightColorScheme(
                primary = Color(0xFF345D9D),
                background = Color(0xFFF7F8FA),
                surface = Color(0xFFFFFFFF),
                surfaceVariant = Color(0xFFF0F2F5),
            )
        }

        MaterialTheme(colorScheme = colors) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text("Astra", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            Text("личный AI-агент", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showSettings = true }) { Text("Настройки") }
                    }

                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(28.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    modifier = Modifier.size(12.dp),
                                    shape = CircleShape,
                                    color = if (wakeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                ) {}
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    if (wakeEnabled) "Фоновый помощник активен" else if (busy) "Выполняю запрос" else "Готова к работе",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Text(
                                if (wakeEnabled) "Скажи «$wakeWord», затем команду. После ответа Astra снова перейдёт в ожидание."
                                else "Включи фоновый режим, чтобы Astra слышала ключевую фразу даже после сворачивания приложения.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = { toggleWake() },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                shape = RoundedCornerShape(18.dp),
                            ) {
                                Text(if (wakeEnabled) "Выключить фоновый режим" else "Включить «$wakeWord»")
                            }
                            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { toggleVoice() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text(if (voiceActive) "Стоп разговор" else "Разговор") }
                        OutlinedButton(
                            onClick = { if (gestureRunning) stopGestureService() else startGestureService() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text(if (gestureRunning) "Стоп жесты" else "Жесты") }
                    }

                    ElevatedCard(shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            FeatureToggle(
                                title = "Автоответы",
                                subtitle = if (notificationAccessEnabled) "Telegram • WhatsApp • Signal" else "Нужен доступ к уведомлениям",
                                checked = autoReplyEnabled,
                                onCheckedChange = {
                                    autoReplyEnabled = it
                                    prefs.edit().putBoolean("auto_reply_enabled", it).apply()
                                    if (it && !notificationAccessEnabled) {
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                                    }
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("Доступ к экрану", fontWeight = FontWeight.Medium)
                                    Text(
                                        if (accessibilityEnabled) "Accessibility включён" else "Нужен для жестов и контекста экрана",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                    Text(if (accessibilityEnabled) "Включён" else "Настроить")
                                }
                            }
                        }
                    }

                    Text("Диалог", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(messages) { msg -> MessageBubble(msg) }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Спроси или дай команду…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp),
                            maxLines = 4,
                        )
                        Button(
                            onClick = { submit(input) },
                            enabled = input.isNotBlank() && !busy,
                            modifier = Modifier.height(56.dp),
                            shape = RoundedCornerShape(18.dp),
                        ) { Text("→") }
                    }
                }
            }

            if (showSettings) {
                SettingsDialog(onDismiss = { showSettings = false })
            }
        }
    }

    @Composable
    private fun FeatureToggle(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun MessageBubble(msg: ChatMessage) {
        val user = msg.role == ChatMessage.Role.USER
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 340.dp),
                shape = RoundedCornerShape(18.dp),
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(
                        when (msg.role) {
                            ChatMessage.Role.USER -> "Ты"
                            ChatMessage.Role.ASSISTANT -> "Astra"
                            ChatMessage.Role.SYSTEM -> "Система"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(msg.text)
                }
            }
        }
    }

    @Composable
    private fun SettingsDialog(onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(26.dp),
            title = { Text("Настройки Astra") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = wakeWord,
                        onValueChange = {
                            wakeWord = it.take(32)
                            prefs.edit().putString("wake_word", wakeWord.trim()).apply()
                        },
                        label = { Text("Ключевое имя") },
                        supportingText = { Text("Например: Астра. Необычные слова могут распознаваться хуже.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = backendUrl,
                        onValueChange = {
                            backendUrl = it
                            prefs.edit().putString("backend_url", it).apply()
                        },
                        label = { Text("URL backend") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = apiToken,
                        onValueChange = {
                            apiToken = it
                            prefs.edit().putString("api_token", it).apply()
                        },
                        label = { Text("Токен backend") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    FeatureToggle(
                        title = "Запись AI-звонков",
                        subtitle = "Работает только если разрешена на backend",
                        checked = aiCallRecording,
                        onCheckedChange = {
                            aiCallRecording = it
                            prefs.edit().putBoolean("ai_call_recording", it).apply()
                        },
                    )
                    Text(
                        "Ожидание ключевой фразы работает локально на телефоне. Фоновый микрофон всегда сопровождается системным уведомлением Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
        )
    }

    companion object {
        const val ACTION_OPEN_FROM_BUBBLE = "com.example.autonomousai.OPEN_FROM_BUBBLE"
    }
}
