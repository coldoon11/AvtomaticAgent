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
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val backend = BackendClient()
    private lateinit var launcher: AppLauncher
    private val worker = Executors.newSingleThreadExecutor()
    private val messages = mutableStateListOf<ChatMessage>()

    private var input by mutableStateOf("")
    private var status by mutableStateOf("Готов")
    private var busy by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)
    private var gestureRunning by mutableStateOf(false)
    private lateinit var prefs: android.content.SharedPreferences
    private var backendUrl by mutableStateOf("")
    private var apiToken by mutableStateOf("")

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startGestureService() else status = "Для жестов нужна камера"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        backendUrl = prefs.getString("backend_url", "http://192.168.1.2:8765").orEmpty()
        apiToken = prefs.getString("api_token", "").orEmpty()
        launcher = AppLauncher(this)
        messages += ChatMessage(ChatMessage.Role.SYSTEM, "Autonomous AI v0.3: чат, запуск приложений и управление рукой.")
        setContent { AppUi() }
    }

    override fun onResume() {
        super.onResume()
        accessibilityEnabled = isAccessibilityServiceEnabled()
        gestureRunning = HandGestureService.running
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun submit(raw: String) {
        val text = raw.trim()
        if (text.isBlank() || busy) return
        messages += ChatMessage(ChatMessage.Role.USER, text)
        input = ""

        val local = runCatching { launcher.tryHandle(text) }.getOrNull()
        if (local != null) {
            messages += ChatMessage(ChatMessage.Role.ASSISTANT, local)
            return
        }
        if (backendUrl.isBlank() || apiToken.isBlank()) {
            status = "Укажи URL backend и токен"
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
                }.onFailure {
                    status = "Ошибка: ${it.message}"
                    messages += ChatMessage(ChatMessage.Role.SYSTEM, status)
                }
            }
        }
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
                    Text("чат • приложения • жесты руки", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Card {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(
                                value = backendUrl,
                                onValueChange = { backendUrl = it; prefs.edit().putString("backend_url", it).apply() },
                                label = { Text("URL backend") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                            OutlinedTextField(
                                value = apiToken,
                                onValueChange = { apiToken = it; prefs.edit().putString("api_token", it).apply() },
                                label = { Text("Токен") },
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
                            label = { Text("Сообщение") },
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
