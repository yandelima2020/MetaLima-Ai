package com.lima.metalima.livemode

import com.lima.metalima.interfaceString
import com.lima.metalima.R
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.lima.metalima.selectGlassesDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.isActive

private const val OUTPUT_RATE = 24_000
private const val MAX_QUEUED_BYTES = OUTPUT_RATE * 2 * 30

/** One Android communications route for both directions, held until the session finishes. */
internal class LiveAudio(private val context: Context, private val inputRate: Int) : AutoCloseable {
    private val manager = context.getSystemService(AudioManager::class.java)
    private var restoreMode: Int? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private val playbackLock = Any()
    private val pending = ArrayDeque<ByteArray>()
    private val playbackReady = Channel<Unit>(Channel.CONFLATED)
    private var pendingSize = 0
    private var offset = 0
    private var writtenFrames = 0L
    @Volatile private var deviceId = -1
    private var inputDeviceType = -1

    @SuppressLint("MissingPermission")
    suspend fun open(names: List<String>, usePhone: Boolean): String = withContext(Dispatchers.IO) {
        val device = if (usePhone) manager.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } ?: error(context.interfaceString(R.string.android_no_encontro_el_altavoz))
        else selectGlassesDevice(context, manager, names, allowBle = true)
        restoreMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        check(manager.setCommunicationDevice(device)) { context.interfaceString(R.string.android_no_pudo_abrir_la) }
        check(withTimeoutOrNull(4_000) {
            while (manager.communicationDevice?.id != device.id) delay(50)
            true
        } == true) { context.interfaceString(R.string.la_conexion_de_voz_no) }
        deviceId = device.id
        inputDeviceType = if (usePhone) AudioDeviceInfo.TYPE_BUILTIN_MIC else device.type

        val inputBuffer = AudioRecord.getMinBufferSize(inputRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outputBuffer = AudioTrack.getMinBufferSize(OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(inputBuffer > 0 && outputBuffer > 0) { context.interfaceString(R.string.el_telefono_no_pudo_preparar) }
        val microphone = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(inputRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(inputBuffer * 2, inputRate / 5)).build()
        record = microphone
        val speaker = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(OUTPUT_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(outputBuffer, OUTPUT_RATE / 5))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = speaker
        check(microphone.state == AudioRecord.STATE_INITIALIZED && speaker.state == AudioTrack.STATE_INITIALIZED) {
            context.interfaceString(R.string.el_telefono_no_pudo_iniciar)
        }
        if (!usePhone) check(speaker.setPreferredDevice(device)) { context.interfaceString(R.string.android_no_pudo_seleccionar_la) }
        if (usePhone && AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching { AcousticEchoCanceler.create(microphone.audioSessionId) }.getOrNull()
            runCatching { echoCanceler?.enabled = true }
        }
        microphone.startRecording()
        check(microphone.recordingState == AudioRecord.RECORDSTATE_RECORDING) { context.interfaceString(R.string.android_no_pudo_iniciar_el) }
        check(withTimeoutOrNull(2_000) {
            val probe = ByteArray(inputRate / 25)
            while (microphone.routedDevice?.let { source ->
                source.type == inputDeviceType &&
                    (usePhone || source.address.isBlank() || device.address.isBlank() || source.address.equals(device.address, ignoreCase = true))
            } != true) {
                microphone.read(probe, 0, probe.size, AudioRecord.READ_NON_BLOCKING)
                delay(40)
            }
            true
        } == true) {
            if (usePhone) context.interfaceString(R.string.android_no_pudo_seleccionar_el)
            else context.interfaceString(R.string.android_no_esta_usando_el)
        }
        runCatching { speaker.setStartThresholdInFrames(minOf(speaker.bufferSizeInFrames, OUTPUT_RATE / 25)) }
        speaker.play()
        if (usePhone) context.interfaceString(R.string.microfono_y_altavoz_del_movil) else device.productName.toString()
    }

    suspend fun capture(send: (ByteArray, Int, Int) -> Unit) = withContext(Dispatchers.IO) {
        val microphone = checkNotNull(record)
        val frame = ByteArray(inputRate * 2 * 20 / 1000)
        var bytesRead = 0
        var nextRouteCheck = 0L
        while (coroutineContext.isActive) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (now >= nextRouteCheck) {
                verifyRoute()
                check(microphone.routedDevice?.type == inputDeviceType) {
                    context.interfaceString(R.string.el_microfono_dejo_de_usar)
                }
                nextRouteCheck = now + 100
            }
            val count = microphone.read(frame, bytesRead, frame.size - bytesRead, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0) { context.interfaceString(R.string.se_interrumpio_el_microfono_durante) }
            bytesRead += count
            if (bytesRead == frame.size) {
                send(frame, bytesRead, inputRate)
                bytesRead = 0
            }
            if (count == 0) delay(6)
        }
    }

    fun enqueue(bytes: ByteArray) = synchronized(playbackLock) {
        check(pendingSize + bytes.size <= MAX_QUEUED_BYTES) { context.interfaceString(R.string.demasiado_audio_en_espera_reinicia) }
        pending.addLast(bytes)
        pendingSize += bytes.size
        playbackReady.trySend(Unit)
    }

    fun flush() = synchronized(playbackLock) {
        val speaker = checkNotNull(track)
        pending.clear()
        pendingSize = 0
        offset = 0
        speaker.pause()
        speaker.flush()
        writtenFrames = 0
        speaker.play()
    }

    fun playedFrames(): Long = synchronized(playbackLock) {
        checkNotNull(track).playbackHeadPosition.toUInt().toLong()
    }

    fun enqueuedFrames(): Long = synchronized(playbackLock) { writtenFrames + pendingSize / 2 }

    suspend fun testSignal(): Boolean = withContext(Dispatchers.IO) {
        val microphone = checkNotNull(record)
        val speaker = checkNotNull(track)
        val samples = ShortArray(inputRate / 20)
        var peak = 0
        val until = android.os.SystemClock.elapsedRealtime() + 1600
        while (android.os.SystemClock.elapsedRealtime() < until && coroutineContext.isActive) {
            verifyRoute()
            val count = microphone.read(samples, 0, samples.size, AudioRecord.READ_NON_BLOCKING)
            check(count >= 0) { context.interfaceString(R.string.se_interrumpio_la_lectura_del) }
            for (i in 0 until count) peak = maxOf(peak, kotlin.math.abs(samples[i].toInt()))
            delay(24)
        }
        val tone = ShortArray(OUTPUT_RATE / 3) { i ->
            val edge = minOf(1f, i / 240f, (OUTPUT_RATE / 3 - i) / 240f)
            (kotlin.math.sin(i * 2 * Math.PI * 660 / OUTPUT_RATE) * 6000 * edge).toInt().toShort()
        }
        check(speaker.write(tone, 0, tone.size, AudioTrack.WRITE_BLOCKING) > 0) { context.interfaceString(R.string.android_no_pudo_reproducir_el) }
        delay(400)
        peak > 300
    }

    suspend fun play(speaking: (Boolean) -> Unit) = withContext(Dispatchers.IO) {
        val speaker = checkNotNull(track)
        var wasPlaying = false
        while (coroutineContext.isActive) {
            val (playing, wait) = synchronized(playbackLock) {
                val next = pending.peekFirst()
                val wrote = if (next != null) {
                    verifyRoute()
                    val count = speaker.write(next, offset, next.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                    check(count >= 0) { context.interfaceString(R.string.se_interrumpio_el_audio_de) }
                    offset += count
                    pendingSize -= count
                    writtenFrames += count / 2
                    if (offset == next.size) {
                        pending.removeFirst()
                        offset = 0
                    }
                    count > 0
                } else false
                (pendingSize > 0 || writtenFrames > speaker.playbackHeadPosition.toUInt().toLong()) to !wrote
            }
            if (playing != wasPlaying) {
                wasPlaying = playing
                speaking(playing)
            }
            if (!playing) playbackReady.receive()
            else if (wait) delay(8)
        }
    }

    private fun verifyRoute() {
        check(manager.communicationDevice?.id == deviceId) {
            context.interfaceString(R.string.se_perdio_la_ruta_de)
        }
    }

    override fun close() {
        echoCanceler?.let { runCatching { it.release() } }
        echoCanceler = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        track?.let { runCatching { it.release() } }
        track = null
        restoreMode?.let { mode ->
            runCatching { manager.clearCommunicationDevice() }
            runCatching { manager.mode = mode }
        }
        restoreMode = null
    }
}
