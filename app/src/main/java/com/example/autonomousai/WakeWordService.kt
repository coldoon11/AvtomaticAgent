package com.example.autonomousai

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.util.Locale
import java.util.concurrent.Executors

class WakeWordService : Service(), RecognitionListener, TextToSpeech.OnInitListener {
    private enum class Mode { WAITING, COMMAND, PROCESSING }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val backend = BackendClient()
    private lateinit var launcher: AppLauncher
    private lateinit var phone: PhoneCallHelper
    private lateinit var prefs: android.content.SharedPreferences

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var mode = Mode.WAITING
    private var commandTimeout: Runnable? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var lastWakeAt = 0L

    override fun onCreate() {
        super.onCreate()
        running = true
        prefs = getSharedPreferences("assistant_settings", MODE_PRIVATE)
        launcher = AppLauncher(this)
        phone = PhoneCallHelper(this)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Запуск локального распознавания…"))
        tts = TextToSpeech(applicationContext, this)
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        loadModel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("wake_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!prefs.getBoolean("wake_enabled", true)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        commandTimeout?.let(main::removeCallbacks)
        commandTimeout = null
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        model?.close()
        model = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun loadModel() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            updateNotification("Нет разрешения на микрофон")
            stopSelf()
            return
        }
        StorageService.unpack(
            this,
            "model-ru",
            "vosk-wake-model",
            { loaded ->
                model = loaded
                startListening()
            },
            { error ->
                updateNotification("Ошибка голосовой модели: ${error.message ?: "неизвестно"}")
                main.postDelayed({ stopSelf() }, 3000)
            },
        )
    }

    private fun startListening() {
        val loaded = model ?: return
        if (speechService != null) return
        try {
            val recognizer = Recognizer(loaded, 16_000f)
            speechService = SpeechService(recognizer, 16_000f).also { it.startListening(this) }
            mode = Mode.WAITING
            updateNotification("Скажи «${wakeWord()}»")
        } catch (error: Exception) {
            updateNotification("Микрофон недоступен: ${error.message ?: "ошибка"}")
        }
    }

    private fun wakeWord(): String = prefs.getString("wake_word", "Астра")
        .orEmpty()
        .trim()
        .ifBlank { "Астра" }
        .lowercase(Locale.getDefault())

    override fun onPartialResult(hypothesis: String?) {
        val text = jsonText(hypothesis, "partial")
        if (text.isBlank()) return
        when (mode) {
            Mode.WAITING -> detectWake(text, final = false)
            Mode.COMMAND -> {
                val clean = stripWake(text)
                if (clean.isNotBlank()) armCommandTimeout(1400L, clean)
            }
            Mode.PROCESSING -> Unit
        }
    }

    override fun onResult(hypothesis: String?) {
        val text = jsonText(hypothesis, "text")
        if (text.isBlank()) return
        when (mode) {
            Mode.WAITING -> detectWake(text, final = true)
            Mode.COMMAND -> {
                val command = stripWake(text)
                if (command.isNotBlank()) processCommand(command) else armCommandTimeout(7000L, null)
            }
            Mode.PROCESSING -> Unit
        }
    }

    override fun onFinalResult(hypothesis: String?) = onResult(hypothesis)

    override fun onError(exception: Exception?) {
        updateNotification("Распознавание: ${exception?.message ?: "ошибка"}")
        restartListening(1200L)
    }

    override fun onTimeout() {
        restartListening(500L)
    }

    private fun detectWake(text: String, final: Boolean) {
        val wake = wakeWord()
        val normalized = normalize(text)
        val index = normalized.indexOf(wake)
        if (index < 0) return
        val now = System.currentTimeMillis()
        if (!final && now - lastWakeAt < 1300L) return
        lastWakeAt = now

        val after = normalized.substring(index + wake.length).trim(' ', ',', '.', ':', '!', '?', '-')
        if (after.isNotBlank() && final) {
            processCommand(after)
            return
        }
        enterCommandMode()
    }

    private fun enterCommandMode() {
        if (mode == Mode.PROCESSING) return
        mode = Mode.COMMAND
        speechService?.setPause(true)
        updateNotification("Слушаю команду…")
        runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55).apply {
            startTone(ToneGenerator.TONE_PROP_BEEP, 90)
            main.postDelayed({ release() }, 140)
        } }
        main.postDelayed({
            if (mode == Mode.COMMAND) speechService?.setPause(false)
        }, 220L)
        armCommandTimeout(8000L, null)
    }

    private fun armCommandTimeout(delay: Long, fallback: String?) {
        commandTimeout?.let(main::removeCallbacks)
        val action = Runnable {
            commandTimeout = null
            if (mode != Mode.COMMAND) return@Runnable
            if (!fallback.isNullOrBlank()) processCommand(fallback) else returnToWake("Команда не услышана")
        }
        commandTimeout = action
        main.postDelayed(action, delay)
    }

    private fun processCommand(raw: String) {
        val command = raw.trim()
        if (command.isBlank() || mode == Mode.PROCESSING) return
        commandTimeout?.let(main::removeCallbacks)
        commandTimeout = null
        mode = Mode.PROCESSING
        speechService?.setPause(true)
        updateNotification("Выполняю: $command")

        worker.execute {
            val answer = runCatching { routeCommand(command) }
                .getOrElse { "Не получилось выполнить запрос: ${it.message ?: "ошибка"}" }
            main.post { speakThenReturn(answer) }
        }
    }

    private fun routeCommand(command: String): String {
        parseAiCall(command)?.let { (target, task) ->
            val baseUrl = prefs.getString("backend_url", "").orEmpty()
            if (baseUrl.isBlank()) return "Сначала укажи адрес сервера в настройках приложения."
            val number = phone.resolveNumber(target)
                ?: return "Не нашёл номер для $target. Разреши доступ к контактам или назови номер полностью."
            val token = prefs.getString("api_token", "").orEmpty()
            val record = prefs.getBoolean("ai_call_recording", false)
            return backend.aiCall(baseUrl, token, number, task, record)
        }

        val ordinaryCall = Regex(
            "^\\s*(?:позвони|позвонить|набери|call)\\s+(.+?)\\s*$",
            RegexOption.IGNORE_CASE,
        ).find(command)
        if (ordinaryCall != null) return phone.placeCall(ordinaryCall.groupValues[1].trim())

        runCatching { launcher.tryHandle(command) }.getOrNull()?.let { return it }

        val baseUrl = prefs.getString("backend_url", "").orEmpty()
        if (baseUrl.isBlank()) return "Сначала укажи адрес backend в настройках приложения."
        val token = prefs.getString("api_token", "").orEmpty()
        return backend.chat(baseUrl, token, command, ScreenAccessibilityService.snapshot().text)
    }

    private fun parseAiCall(text: String): Pair<String, String>? {
        val original = text.trim()
        val lower = original.lowercase(Locale.getDefault())
        val prefixes = listOf("ии позвони ", "ai позвони ", "ai call ", "агент позвони ", "агент набери ")
        val prefix = prefixes.firstOrNull { lower.startsWith(it) } ?: return null
        val rest = original.substring(prefix.length).trim()
        if (rest.isBlank()) return null
        val parts = rest.split(":", limit = 2)
        val target = parts[0].trim()
        val task = parts.getOrNull(1)?.trim().orEmpty().ifBlank {
            "Поздоровайся, скажи, что ты ИИ-помощник, и спроси, удобно ли сейчас разговаривать."
        }
        return target to task
    }

    private fun speakThenReturn(text: String) {
        if (text.isBlank()) {
            returnToWake("Готов")
            return
        }
        if (!ttsReady || tts == null) {
            returnToWake(text.take(90))
            return
        }
        updateNotification(text.take(110))
        val result = tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, TTS_ID)
        if (result == TextToSpeech.ERROR) returnToWake("Готов")
    }

    private fun returnToWake(message: String? = null) {
        mode = Mode.WAITING
        speechService?.setPause(false)
        updateNotification(message ?: "Скажи «${wakeWord()}»")
        if (message != null) {
            main.postDelayed({
                if (mode == Mode.WAITING) updateNotification("Скажи «${wakeWord()}»")
            }, 1600L)
        }
    }

    private fun restartListening(delay: Long) {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        mode = Mode.WAITING
        main.postDelayed({ if (prefs.getBoolean("wake_enabled", false)) startListening() }, delay)
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        val engine = tts ?: return
        val ru = Locale("ru", "RU")
        val result = engine.setLanguage(ru)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.getDefault()
        }
        engine.setSpeechRate(1.02f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { returnToWake() } }
            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) { main.post { returnToWake() } }
        })
    }

    private fun jsonText(raw: String?, key: String): String {
        if (raw.isNullOrBlank()) return ""
        return runCatching { JSONObject(raw).optString(key).trim() }.getOrDefault("")
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.getDefault())
        .replace('ё', 'е')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun stripWake(value: String): String {
        val normalized = normalize(value)
        val wake = normalize(wakeWord())
        return normalized.replace(Regex("^.*?\\b${Regex.escape(wake)}\\b"), "").trim(' ', ',', '.', ':', '!', '?', '-')
            .ifBlank { normalized.takeUnless { it == wake }.orEmpty() }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Фоновый голосовой помощник", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показывает, когда локальное ключевое слово активно"
                setSound(null, null)
            },
        )
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            11,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Astra • фоновый голос")
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "com.example.autonomousai.STOP_WAKE_WORD"
        private const val CHANNEL_ID = "wake_word_service"
        private const val NOTIFICATION_ID = 3401
        private const val TTS_ID = "wake_answer"
        @Volatile var running: Boolean = false
            private set
    }
}
