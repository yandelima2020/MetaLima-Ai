package com.lima.metalima

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.text.Normalizer
import java.util.Locale

internal enum class VideoOrientation(val label: Int, val width: Int, val height: Int) {
    ORIGINAL(R.string.video_original, 0, 0),
    VERTICAL(R.string.video_vertical, 720, 1280),
    HORIZONTAL(R.string.video_horizontal, 1280, 720)
}

internal enum class VideoFraming(val label: Int) {
    ORIGINAL(R.string.video_framing_original),
    BLUR(R.string.video_blur),
    CROP(R.string.video_crop)
}

internal data class VideoFormat(val orientation: VideoOrientation, val framing: VideoFraming)

@Composable internal fun VideoFormatControls(
    value: VideoFormat, streaming: Boolean, change: (VideoFormat) -> Unit,
    voice: (() -> Unit)? = null, listening: Boolean = false
) {
    val orientations = VideoOrientation.entries.filter { !streaming || it != VideoOrientation.ORIGINAL }
    val framing = VideoFraming.entries.filter { streaming || it != VideoFraming.ORIGINAL }
    Choice(stringResource(if (streaming) R.string.video_stream_format else R.string.video_record_format),
        value.orientation.name, orientations.map { it.name }, display = { stringResource(VideoOrientation.valueOf(it).label) }) { selected ->
        change(value.copy(orientation = VideoOrientation.valueOf(selected)))
    }
    if (value.orientation != VideoOrientation.ORIGINAL) {
        Choice(stringResource(R.string.video_framing), value.framing.name, framing.map { it.name },
            display = { stringResource(VideoFraming.valueOf(it).label) }) { selected ->
            change(value.copy(framing = VideoFraming.valueOf(selected)))
        }
    }
    if (voice != null) {
        OutlinedButton(onClick = voice, modifier = Modifier.heightIn(min = 44.dp)) {
            Text(stringResource(if (listening) R.string.cancelar_escucha else R.string.video_format_voice))
        }
        if (listening) Text(stringResource(R.string.video_format_voice_hint), color = Muted)
    }
}

internal enum class VideoTarget { RECORDING, STREAM }
internal data class VideoVoiceCommand(val target: VideoTarget, val orientation: VideoOrientation)

private val horizontalWords = Regex("""\b(horizontal|landscape|orizzontale|querformat)\b|横屏|(?<!\d)16\s*[:x/ -]\s*9(?!\d)|dieciseis\s+(?:a\s+|por\s+)?nueve""")
private val verticalWords = Regex("""\b(verticale?|portrait|short|hochformat)\b|竖屏|(?<!\d)9\s*[:x/ -]\s*16(?!\d)|nueve\s+(?:a\s+|por\s+)?dieciseis""")
private val streamWords = Regex("""\b(directo|retransmision|emision|stream|streaming|livestream|diretta|ubertragung)\b|直播""")
private val recordingWords = Regex("""\b(grabacion|grabar|recording|registrazione|aufnahme)\b|录制""")
private val changeWords = Regex("""\b(cambia(?:r)?(?:me)?|pon(?:me)?|ajusta|configura|change|set|switch|imposta|andere|wechsel)\b|切换|更改""")

internal fun videoVoiceCommand(heard: String, defaultTarget: VideoTarget? = null): VideoVoiceCommand? {
    val text = Normalizer.normalize(heard.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    if (defaultTarget == null && !changeWords.containsMatchIn(text)) return null
    val wide = horizontalWords.containsMatchIn(text)
    val tall = verticalWords.containsMatchIn(text)
    if (wide == tall) return null
    val stream = streamWords.containsMatchIn(text)
    val recording = recordingWords.containsMatchIn(text)
    if (stream && recording) return null
    val target = when {
        stream -> VideoTarget.STREAM
        recording -> VideoTarget.RECORDING
        else -> defaultTarget ?: return null
    }
    return VideoVoiceCommand(target, if (wide) VideoOrientation.HORIZONTAL else VideoOrientation.VERTICAL)
}
