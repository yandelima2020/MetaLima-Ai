package com.lima.metalima

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.lima.metalima.livemode.LiveAudio
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

internal class HandsFreeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listening: Job? = null

    companion object {
        const val STOP = "com.lima.metalima.STOP_HANDS_FREE"
        const val GLASSES = "glasses"
        private const val NOTICE_ID = 205
        private const val RATE = 16_000
        private val current = MutableStateFlow(HandsFreeStatus())
        val state = current.asStateFlow()

        fun disable(context: Context) {
            if (!current.value.enabled) return
            current.value = current.value.copy(enabled = false, stopping = true, message = R.string.handsfree_stopping)
            if (!context.stopService(Intent(context, HandsFreeService::class.java))) {
                current.value = current.value.copy(stopping = false, message = null)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            val job = listening
            if (job == null) {
                current.value = HandsFreeStatus()
                stopSelf()
            } else {
                current.value = current.value.copy(enabled = false, stopping = true, message = R.string.handsfree_stopping)
                job.cancel()
            }
            return START_NOT_STICKY
        }
        if (listening != null) return START_NOT_STICKY
        try {
            startForeground(NOTICE_ID, notification(R.string.handsfree_preparing), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (_: RuntimeException) {
            current.value = HandsFreeStatus(message = R.string.handsfree_error)
            stopSelf()
            return START_NOT_STICKY
        }
        current.value = HandsFreeStatus(true, R.string.handsfree_preparing)
        val names = intent?.getStringArrayListExtra(GLASSES).orEmpty()
        listening = scope.launch(start = CoroutineStart.UNDISPATCHED) { listen(names) }
        return START_NOT_STICKY
    }

    private suspend fun listen(names: List<String>) {
        val audio = LiveAudio(this, RATE)
        val ready = CompletableDeferred<Int>()
        val speaker = TextToSpeech(applicationContext) { ready.complete(it) }
        val wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ok-lima")
        var status = R.string.handsfree_preparing
        var error: Int? = null
        try {
            wakeLock.acquire()
            val folder = spanishVoiceModel(applicationContext)
            val model = withContext(Dispatchers.IO) { Model(folder.absolutePath) }
            model.use {
                Recognizer(model, RATE.toFloat()).use { recognizer ->
                    val vocabulary = JSONArray(listOf("okay lima", "okey lima", "ok lima", "o que lima", "hockey lima", "[unk]")).toString()
                    Recognizer(model, RATE.toFloat(), vocabulary).use { wakeWord ->
                        wakeWord.setWords(true)
                        wakeWord.setEndpointerMode(Recognizer.EndpointerMode.SHORT)
                        val voiceReady = withTimeoutOrNull(5_000) { ready.await() == TextToSpeech.SUCCESS } == true
                        if (voiceReady) speaker.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        status = R.string.handsfree_waiting
                        audio.open(names, usePhone = false)
                        show(status)
                        recognize(audio, recognizer, wakeWord, speaker.takeIf { voiceReady })
                    }
                }
            }
        } catch (stop: CancellationException) {
            throw stop
        } catch (_: Exception) {
            error = if (status == R.string.handsfree_preparing) R.string.handsfree_model_error else R.string.handsfree_route_error
        } finally {
            withContext(NonCancellable) {
                try {
                    runCatching { speaker.stop() }
                    runCatching { speaker.shutdown() }
                    withContext(Dispatchers.IO) { audio.close() }
                } finally {
                    if (wakeLock.isHeld) wakeLock.release()
                    Log.i("MetaLimaHandsFree", "Audio route released")
                    current.value = current.value.copy(enabled = false, stopping = false, message = error)
                    listening = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private suspend fun recognize(audio: LiveAudio, recognition: Recognizer, wakeWord: Recognizer, speaker: TextToSpeech?) = coroutineScope {
        val requests = Channel<VoiceTurn>(1)
        val replying = AtomicBoolean(false)
        val reset = AtomicBoolean(false)
        val prefs = StudioPrefs(this@HandsFreeService)
        val assistant = AssistantViewModel.shared(application)
        val capture = launch {
            var awaitQuestionUntil = 0L
            var questionAudio: ByteArrayOutputStream? = null
            audio.capture { bytes, count, _ ->
                if (replying.get()) return@capture
                val now = SystemClock.elapsedRealtime()
                val awaitingQuestion = now < awaitQuestionUntil
                if (reset.getAndSet(false)) {
                    recognition.reset()
                    wakeWord.reset()
                    questionAudio = if (awaitingQuestion && prefs.handsFreeGeminiAudio &&
                        assistant.state.value.provider == Provider.GEMINI) ByteArrayOutputStream() else null
                }
                if (!awaitingQuestion) questionAudio = null
                questionAudio?.write(bytes, 0, count)
                val text = if (recognition.acceptWaveForm(bytes, count)) JSONObject(recognition.result).optString("text").trim() else ""
                var command = limaQuestion(text)
                if (now >= awaitQuestionUntil && wakeWord.acceptWaveForm(bytes, count)) {
                    val result = JSONObject(wakeWord.result)
                    if (command == null && confidentWake(result)) command = ""
                }
                val question = command ?: text.takeIf { it.isNotBlank() && now < awaitQuestionUntil } ?: return@capture
                val pcm = questionAudio?.takeIf { question.isNotBlank() }?.toByteArray()
                questionAudio = null
                awaitQuestionUntil = if (question.isBlank()) now + 10_500 else 0L
                replying.set(true)
                if (!requests.trySend(VoiceTurn(question, pcm)).isSuccess) replying.set(false)
            }
        }
        var followUp: Job? = null
        try {
            for ((question, pcm) in requests) {
                followUp?.cancel()
                if (question.isBlank()) {
                    Log.i("MetaLimaHandsFree", "Wake phrase detected")
                    show(R.string.handsfree_followup)
                    audio.playCue()
                    reset.set(true)
                    replying.set(false)
                    followUp = launch { delay(10_000); show(R.string.handsfree_waiting) }
                    continue
                }
                Log.i("MetaLimaHandsFree", "Voice request detected")
                show(R.string.handsfree_answering, question)
                val language = prefs.language
                val reply = async(start = CoroutineStart.UNDISPATCHED) {
                    val command = videoVoiceCommand(question)
                    if (command == null) {
                        val wav = pcm?.takeIf { prefs.handsFreeGeminiAudio }?.let { pcmToWav(it, RATE) }
                        assistant.askFromGlasses(question, language, wav) { heard -> show(R.string.handsfree_answering, heard) }
                    } else {
                        if (command.target == VideoTarget.STREAM) {
                            prefs.streamFormat = prefs.streamFormat.copy(orientation = command.orientation)
                        } else {
                            prefs.recordFormat = prefs.recordFormat.copy(orientation = command.orientation)
                        }
                        interfaceString(R.string.video_format_changed, interfaceString(command.orientation.label))
                    }
                }
                audio.playCue()
                val answer = reply.await()
                if (speaker != null) speak(speaker, answer, language, prefs.voiceRate, prefs.pitch)
                reset.set(true)
                replying.set(false)
                show(R.string.handsfree_waiting)
            }
        } finally {
            followUp?.cancel()
            capture.cancel()
            requests.cancel()
        }
    }

    private data class VoiceTurn(val text: String, val pcm: ByteArray?)

    private fun confidentWake(result: JSONObject): Boolean {
        if (limaQuestion(result.optString("text")) != "") return false
        val words = result.optJSONArray("result") ?: return false
        if (words.length() < 2) return false
        val lima = words.optJSONObject(words.length() - 1) ?: return false
        // The first word is often split or transcribed as a homophone. Require a clear "Lima".
        return lima.optString("word") == "lima" && lima.optDouble("conf", 0.0) >= 0.78 &&
            (0 until words.length() - 1).all { (words.optJSONObject(it)?.optDouble("conf", 0.0) ?: 0.0) >= 0.25 }
    }

    private suspend fun speak(speaker: TextToSpeech, answer: String, language: AppLanguage, rate: Float, pitch: Float) {
        if (speaker.setLanguage(language.locale) < TextToSpeech.LANG_AVAILABLE) return
        runCatching {
            speaker.voices.orEmpty().asSequence()
                .filter {
                    it.locale.language == language.locale.language && !it.isNetworkConnectionRequired &&
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                }
                .sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == language.locale }
                    .thenByDescending { it.quality }.thenBy { it.latency })
                .firstOrNull()?.let { speaker.voice = it }
        }
        speaker.setSpeechRate(rate)
        speaker.setPitch(pitch)
        val completed = CompletableDeferred<Unit>()
        speaker.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { completed.complete(Unit) }
            @Deprecated("Required by Android")
            override fun onError(utteranceId: String?) { completed.complete(Unit) }
        })
        val spoken = speakableReply(answer, language)
        if (spoken.isNotEmpty() && speaker.speak(spoken.take(1200), TextToSpeech.QUEUE_FLUSH, null, "lima-answer") == TextToSpeech.SUCCESS) {
            try { withTimeoutOrNull(30_000) { completed.await() } } finally { speaker.stop() }
        }
    }

    private fun show(message: Int, heard: String? = null) {
        if (current.value.stopping) return
        current.value = current.value.copy(enabled = true, message = message, heard = heard ?: current.value.heard)
        getSystemService(NotificationManager::class.java).notify(NOTICE_ID, notification(message))
    }

    private fun notification(message: Int): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("lima-wake", interfaceString(R.string.handsfree_title), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 205, Intent(this, HandsFreeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 206, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "lima-wake").setSmallIcon(R.drawable.smart_glasses_icon)
            .setContentTitle(interfaceString(R.string.handsfree_title)).setContentText(interfaceString(message)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, interfaceString(R.string.handsfree_stop), stop).build()).build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal fun speakableReply(answer: String, language: AppLanguage): String {
    val plain = answer.replace(Regex("\\[([^]]+)]\\(https?://[^)]+\\)"), "$1")
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("(?m)^\\s*(?:#{1,6}|[-+>])\\s+"), "")
        .replace(Regex("[`*]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (language == AppLanguage.SPANISH) {
        plain.replace(Regex("\\b16:9\\b"), "dieciséis a nueve")
            .replace(Regex("\\b9:16\\b"), "nueve a dieciséis")
    } else plain
}

internal data class HandsFreeStatus(
    val enabled: Boolean = false, val message: Int? = null, val stopping: Boolean = false, val heard: String? = null
) {
    val ownsMicrophone get() = enabled || stopping
}
