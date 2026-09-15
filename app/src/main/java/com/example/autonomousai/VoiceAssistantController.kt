package com.example.autonomousai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceAssistantController(
    context: Context,
    private val onText: (String) -> Unit,
    private val onState: (String) -> Unit,
) : RecognitionListener, TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var speaking = false
    private var ttsReady = false
    private val tts = TextToSpeech(appContext, this)

    fun start() {
        if (active) return
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onState("Распознавание речи недоступно на этом телефоне")
            return
        }
        active = true
        ensureRecognizer()
        onState("Слушаю…")
        listen()
    }

    fun stop() {
        active = false
        speaking = false
        recognizer?.cancel()
        tts.stop()
        onState("Голос выключен")
    }

    fun isActive(): Boolean = active

    fun speak(text: String) {
        if (!active || text.isBlank()) return
        main.post {
            speaking = true
            recognizer?.cancel()
            if (ttsReady) {
                val params = Bundle().apply {
                    putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
                }
                val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
                if (result == TextToSpeech.ERROR) {
                    speaking = false
                    scheduleListen(350)
                } else {
                    onState("Отвечаю…")
                }
            } else {
                speaking = false
                scheduleListen(350)
            }
        }
    }

    fun destroy() {
        active = false
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        tts.shutdown()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ttsReady = true
        val ru = Locale("ru", "RU")
        val result = tts.setLanguage(ru)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.language = Locale.getDefault()
        }
        tts.setSpeechRate(1.0f)
        tts.setPitch(1.0f)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                speaking = false
                scheduleListen(250)
            }

            @Deprecated("Deprecated in Android")
            override fun onError(utteranceId: String?) {
                speaking = false
                scheduleListen(350)
            }
        })
    }

    private fun ensureRecognizer() {
        if (recognizer != null) return
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(this)
        }
    }

    private fun listen() {
        if (!active || speaking) return
        ensureRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
        }
        runCatching { recognizer?.startListening(intent) }
            .onSuccess { onState("Слушаю…") }
            .onFailure {
                onState("Ошибка микрофона: ${it.message}")
                scheduleListen(1200)
            }
    }

    private fun scheduleListen(delayMs: Long) {
        if (!active) return
        main.postDelayed({ if (active && !speaking) listen() }, delayMs)
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() { onState("Слышу тебя…") }
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() { onState("Думаю…") }

    override fun onError(error: Int) {
        if (!active || speaking) return
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> scheduleListen(500)
            else -> {
                onState("Речь: ошибка $error")
                scheduleListen(1000)
            }
        }
    }

    override fun onResults(results: Bundle?) {
        if (!active) return
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isBlank()) {
            scheduleListen(400)
            return
        }
        onState("Понял: $text")
        onText(text)
    }

    override fun onPartialResults(partialResults: Bundle?) = Unit
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    companion object {
        private const val UTTERANCE_ID = "autonomous_ai_reply"
    }
}
