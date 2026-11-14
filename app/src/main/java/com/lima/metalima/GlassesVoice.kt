package com.lima.metalima

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.lima.metalima.stream.AudioInputHandler
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TURN_DURATION_MS = 12_000L

internal class GlassesVoice(private val context: Context) {
    private val app = context.applicationContext
    private val audioManager = app.getSystemService(AudioManager::class.java)

    fun selectHeadset(names: List<String>): AudioDeviceInfo = selectGlassesDevice(context, audioManager, names)

    suspend fun record(headset: AudioDeviceInfo, finish: CompletableDeferred<Unit>, ready: () -> Unit): ByteArray {
        val samples = ByteArrayOutputStream()
        val firstBuffer = CompletableDeferred<Unit>()
        val input = AudioInputHandler(app)
        val maxBytes = (TURN_DURATION_MS * AudioInputHandler.SAMPLE_RATE * 2 / 1000).toInt()
        withContext(Dispatchers.IO) {
            try {
                check(input.prepare(headset) && input.startCapture(firstBuffer, samples, maxBytes)) {
                    context.interfaceString(R.string.no_se_pudo_abrir_el_2)
                }
                coroutineScope {
                    val interruption = launch {
                        input.wasInterrupted.first { it }
                        error(context.interfaceString(R.string.se_interrumpio_el_microfono_bluetooth))
                    }
                    try {
                        check(withTimeoutOrNull(4_000) { firstBuffer.await(); true } == true) {
                            context.interfaceString(R.string.el_microfono_bluetooth_no_envio)
                        }
                        withContext(Dispatchers.Main) { ready() }
                        withTimeoutOrNull(TURN_DURATION_MS) { finish.await() }
                    } finally {
                        interruption.cancel()
                    }
                }
            } finally {
                input.cleanup()
            }
        }
        val pcm = synchronized(samples) { samples.toByteArray() }
        check(pcm.size >= AudioInputHandler.SAMPLE_RATE / 2) {
            context.interfaceString(R.string.la_grabacion_es_muy_corta)
        }
        return pcmToWav(pcm)
    }

    private fun AudioInputHandler.startCapture(first: CompletableDeferred<Unit>, samples: ByteArrayOutputStream, maxBytes: Int): Boolean {
        pcmDataCallback = { buffer, offset, length ->
            synchronized(samples) {
                samples.write(buffer, offset, minOf(length, maxBytes - samples.size()))
                first.complete(Unit)
            }
        }
        return startRecording()
    }

    suspend fun speak(speaker: TextToSpeech, answer: String, headset: AudioDeviceInfo) {
        val file = File.createTempFile("voice-answer-", ".wav", app.cacheDir)
        try {
            val id = file.name
            val synthesized = withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine { result ->
                    fun complete(success: Boolean) {
                        if (result.isActive) result.resume(success)
                    }
                    speaker.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) { if (utteranceId == id) complete(true) }
                        @Deprecated("Requerido por Android")
                        override fun onError(utteranceId: String?) { if (utteranceId == id) complete(false) }
                    })
                    if (speaker.synthesizeToFile(answer.take(1800), Bundle(), file, id) != TextToSpeech.SUCCESS) complete(false)
                }
            }
            check(synthesized == true && file.length() > 44) { context.interfaceString(R.string.android_no_pudo_generar_el) }
            play(file, headset)
        } finally {
            speaker.stop()
            speaker.setOnUtteranceProgressListener(null)
            file.delete()
        }
    }

    private suspend fun play(file: File, headset: AudioDeviceInfo) {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val output = outputs.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                (it.productName.toString() == headset.productName.toString() ||
                    headset.address.isNotBlank() && it.address.equals(headset.address, ignoreCase = true))
        } ?: error(context.interfaceString(R.string.activa_audio_multimedia_para_las))
        val player = MediaPlayer()
        try {
            val played = withTimeoutOrNull(180_000) {
                suspendCancellableCoroutine { result ->
                    fun complete(success: Boolean) { if (result.isActive) result.resume(success) }
                    player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    player.setDataSource(file.absolutePath)
                    check(player.setPreferredDevice(output)) { context.interfaceString(R.string.android_no_pudo_seleccionar_las) }
                    player.setOnErrorListener { _, _, _ -> complete(false); true }
                    player.setOnCompletionListener { complete(true) }
                    player.setOnPreparedListener {
                        if (result.isActive) runCatching { it.start() }.onFailure { complete(false) }
                    }
                    player.prepareAsync()
                }
            }
            check(played == true) { context.interfaceString(R.string.no_se_pudo_reproducir_la_2) }
        } finally {
            player.release()
        }
    }
}

internal fun selectGlassesDevice(context: Context, manager: AudioManager, names: List<String>, allowBle: Boolean = false): AudioDeviceInfo {
    val headsets = manager.availableCommunicationDevices.filter {
        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || (allowBle && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }
    val matched = headsets.filter { headset ->
        names.any { it.isNotBlank() && it.equals(headset.productName.toString(), ignoreCase = true) }
    }
    return matched.singleOrNull() ?: headsets.singleOrNull()
        ?: error(if (headsets.isEmpty()) context.interfaceString(R.string.conecta_las_gafas_por_bluetooth)
            else context.interfaceString(R.string.hay_varias_salidas_bluetooth_desconecta))
}

private fun pcmToWav(pcm: ByteArray): ByteArray {
    val rate = AudioInputHandler.SAMPLE_RATE
    return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
        .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        .put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).put(pcm).array()
}
