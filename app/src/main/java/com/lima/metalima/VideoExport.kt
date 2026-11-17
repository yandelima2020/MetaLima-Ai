package com.lima.metalima

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GaussianBlurWithFrameOverlaid
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@androidx.annotation.OptIn(UnstableApi::class)
internal suspend fun saveRecording(context: Context, source: Uri, format: VideoFormat): Uri {
    val orientation = format.orientation
    if (orientation == VideoOrientation.ORIGINAL) return withContext(Dispatchers.IO) { storeCapture(context, null, source) }

    val directory = File(context.cacheDir, "recordings").apply { mkdirs() }
    val output = File.createTempFile("formato-", ".mp4", directory)
    try {
        val frameRatio = orientation.width.toFloat() / orientation.height
        val effects = mutableListOf<Effect>()
        if (format.framing == VideoFraming.BLUR) {
            val sourceRatio = withContext(Dispatchers.IO) { videoAspectRatio(context, source) }
            effects.add(GaussianBlurWithFrameOverlaid(18f, minOf(1f, sourceRatio / frameRatio), minOf(1f, frameRatio / sourceRatio)))
        }
        effects.add(Presentation.createForWidthAndHeight(orientation.width, orientation.height, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setEffects(Effects(emptyList(), effects)).build()
        withContext(Dispatchers.Main.immediate) {
            var transformer: Transformer? = null
            try {
                suspendCancellableCoroutine<Unit> { pending ->
                    transformer = Transformer.Builder(context.applicationContext)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (pending.isActive) pending.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (pending.isActive) pending.resumeWithException(exportException)
                            }
                        }).build().also { it.start(item, output.absolutePath) }
                }
            } catch (error: CancellationException) {
                transformer?.cancel()
                throw error
            }
        }
        return withContext(Dispatchers.IO) {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", output)
            storeCapture(context, null, uri)
        }
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { output.delete() }
    }
}

private fun videoAspectRatio(context: Context, uri: Uri): Float {
    val metadata = MediaMetadataRetriever()
    try {
        metadata.setDataSource(context, uri)
        val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        check(width > 0 && height > 0) { context.interfaceString(R.string.la_grabacion_no_contiene_video) }
        val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
    } finally {
        metadata.release()
    }
}
