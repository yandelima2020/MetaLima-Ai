package com.lima.metalima

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.text.Normalizer
import java.util.Locale

internal fun dictationRequest(language: AppLanguage) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.tag)

/** An explicitly armed command owns at most one recognition request. */
@Composable internal fun VoiceCommand(
    context: Context, enabled: Boolean, phrase: String, language: AppLanguage,
    finished: (question: String?, message: String?) -> Unit
) {
    val owner = LocalLifecycleOwner.current
    val onFinish by rememberUpdatedState(finished)
    DisposableEffect(context, owner, enabled, phrase, language) {
        if (!enabled) return@DisposableEffect onDispose {}
        val recognizer = runCatching {
            check(SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }.getOrNull()
        if (recognizer == null) {
            onFinish(null, "Android no ofrece reconocimiento de voz sin conexión en este móvil.")
            return@DisposableEffect onDispose {}
        }

        val main = Handler(Looper.getMainLooper())
        var active = true
        var started = false
        fun finish(question: String? = null, message: String? = null) {
            if (!active) return
            active = false
            onFinish(question, message)
        }
        val timeout = Runnable { finish(message = "Terminó el tiempo de escucha. Pulsa para volver a intentarlo.") }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (active && !started) {
                    started = true
                    main.postDelayed(timeout, 10_000)
                    runCatching { recognizer.startListening(dictationRequest(language)) }
                        .onFailure { finish(message = "No se pudo abrir el reconocimiento de voz.") }
                }
                Lifecycle.Event.ON_PAUSE -> {
                    finish()
                    recognizer.cancel()
                }
                else -> Unit
            }
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val question = commandQuestion(heard, phrase, language == AppLanguage.CHINESE)
                finish(question, if (question == null) "No se reconoció «$phrase». Pulsa para volver a intentarlo." else null)
            }
            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Concede permiso de micrófono a MetaLima Ai."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Comprueba si Android ofrece reconocimiento sin conexión para ${language.label}."
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se reconoció «$phrase». Pulsa para volver a intentarlo."
                    else -> "El reconocimiento de voz se interrumpió. Pulsa para volver a intentarlo."
                }
                finish(message = message)
            }
        })
        owner.lifecycle.addObserver(observer)
        onDispose {
            active = false
            owner.lifecycle.removeObserver(observer)
            main.removeCallbacks(timeout)
            recognizer.destroy()
        }
    }
}

private val word = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*")
private val accents = Regex("\\p{M}+")

private fun commandQuestion(heard: String, phrase: String, unspaced: Boolean): String? {
    val tokens = word.findAll(heard).toList()
    val expected = word.findAll(phrase).map { it.value }.toList()
    if (unspaced) {
        val prefix = expected.joinToString("")
        val letters = tokens.flatMap { it.range.toList() }
        if (letters.take(prefix.length).map { heard[it] }.joinToString("") != prefix) return null
        return heard.drop(letters[prefix.lastIndex] + 1).trimStart { !it.isLetterOrDigit() }
    }
    fun fold(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(accents, "").lowercase(Locale.ROOT)
    if (tokens.take(expected.size).map { fold(it.value) } != expected.map(::fold)) return null
    return heard.drop(tokens[expected.lastIndex].range.last + 1).trimStart { !it.isLetterOrDigit() }
}
