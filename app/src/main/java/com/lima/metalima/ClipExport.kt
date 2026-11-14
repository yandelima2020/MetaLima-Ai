package com.lima.metalima

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer

internal suspend fun exportMarkedClip(context: Context, source: Uri, markMs: Long): Uri {
    val extractor = MediaExtractor()
    val temporary = File.createTempFile("fragmento-", ".mp4", File(context.cacheDir, "recordings").apply { mkdirs() })
    try {
        extractor.setDataSource(context, source, null)
        val video = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: error("La grabación no contiene vídeo.")
        val videoFormat = extractor.getTrackFormat(video)
        val end = (markMs + 5000) * 1000
        extractor.selectTrack(video)
        extractor.seekTo(((markMs - 3000).coerceAtLeast(0)) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val start = extractor.sampleTime
        check(start >= 0 && start < end) { "No se encontró vídeo alrededor de esta marca." }
        val audio = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (audio != null) extractor.selectTrack(audio)
        extractor.seekTo(start, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val muxer = MediaMuxer(temporary.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            val tracks = mutableMapOf(video to muxer.addTrack(videoFormat))
            if (audio != null) tracks[audio] = muxer.addTrack(extractor.getTrackFormat(audio))
            muxer.start()
            started = true
            val info = MediaCodec.BufferInfo()
            var buffer = ByteBuffer.allocateDirect(512 * 1024)
            var wroteVideo = false
            while (extractor.sampleTrackIndex >= 0) {
                currentCoroutineContext().ensureActive()
                val track = extractor.sampleTrackIndex
                val time = extractor.sampleTime
                if (time > end) break
                if (time >= start) {
                    val length = extractor.sampleSize
                    check(length in 0..16_777_216L) { "La muestra de vídeo es demasiado grande." }
                    if (buffer.capacity() < length) buffer = ByteBuffer.allocateDirect(length.toInt())
                    buffer.clear()
                    val count = extractor.readSampleData(buffer, 0)
                    val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    if (count >= 0 && (track != video || wroteVideo || sync)) {
                        info.set(0, count, time - start, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(tracks.getValue(track), buffer, info)
                        if (track == video) wroteVideo = true
                    }
                }
                if (!extractor.advance()) break
            }
            check(wroteVideo) { "No se encontró un fotograma inicial para el fragmento." }
            started = false
            muxer.stop()
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release()
        }
        val tempUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temporary)
        return storeCapture(context, null, tempUri)
    } finally {
        extractor.release()
        temporary.delete()
    }
}
