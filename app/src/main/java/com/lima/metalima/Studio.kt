package com.lima.metalima

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.meta.wearable.dat.core.types.DeviceIdentifier
import java.io.File
import java.text.DateFormat
import java.util.Date

internal val destinations = listOf("YouTube", "Twitch", "TikTok", "Kick", "Facebook Live", "Instagram Live", "OBS / Servidor RTMP", "RTMP personalizado")

internal class StudioPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("capture", Context.MODE_PRIVATE)
    private val vault = Vault(context)
    var skin: Skin
        get() = Skin.entries.firstOrNull { it.name == prefs.getString("skin", null) } ?: Skin.GRAPHITE
        set(value) { prefs.edit().putString("skin", value.name).apply() }
    fun framePhoto(deviceId: DeviceIdentifier): String? = prefs.getString("frame-photo-${deviceId.identifier}", null)

    fun setFramePhoto(deviceId: DeviceIdentifier, photo: String?) {
        prefs.edit().putString("frame-photo-${deviceId.identifier}", photo).apply()
    }
    var platform: String
        get() = prefs.getString("platform", destinations.first()) ?: destinations.first()
        set(value) { prefs.edit().putString("platform", value).apply() }
    fun url(platform: String): String = prefs.getString("url-$platform", "").takeUnless { it.isNullOrBlank() } ?: when (platform) {
        "YouTube" -> "rtmps://a.rtmps.youtube.com/live2"
        "Twitch" -> "rtmp://live.twitch.tv/app"
        "Facebook Live" -> "rtmps://live-api-s.facebook.com:443/rtmp"
        else -> ""
    }
    fun secret(platform: String) = vault.exists("stream-$platform")
    fun streamAddress(platform: String): String = url(platform).trimEnd('/') + "/" + (vault.read("stream-$platform") ?: "")
    fun saveDestination(platform: String, url: String, key: String) {
        if (key.isNotBlank()) vault.write("stream-$platform", key.trim())
        prefs.edit().putString("platform", platform).putString("url-$platform", url.trim()).apply()
    }
    var fps: Int
        get() = prefs.getInt("fps", 24)
        set(value) { prefs.edit().putInt("fps", value).apply() }
    var liveModel: String?
        get() = prefs.getString("live-model", null)
        set(value) { prefs.edit().putString("live-model", value).apply() }
    var streamFps: Int
        get() = prefs.getInt("stream-fps", 24)
        set(value) { prefs.edit().putInt("stream-fps", value).apply() }
    var streamAudio: String
        get() = prefs.getString("stream-audio", "Automática") ?: "Automática"
        set(value) { prefs.edit().putString("stream-audio", value).apply() }
    var quality: String
        get() = prefs.getString("quality", "FLUIDA") ?: "FLUIDA"
        set(value) { prefs.edit().putString("quality", value).apply() }
    var landscape: Boolean
        get() = prefs.getBoolean("landscape", false)
        set(value) { prefs.edit().putBoolean("landscape", value).apply() }
    var streamFormat: VideoFormat
        get() = VideoFormat(if (landscape) VideoOrientation.HORIZONTAL else VideoOrientation.VERTICAL,
            VideoFraming.entries.firstOrNull { it.name == prefs.getString("stream-framing", null) } ?: VideoFraming.ORIGINAL)
        set(value) {
            prefs.edit().putBoolean("landscape", value.orientation == VideoOrientation.HORIZONTAL)
                .putString("stream-framing", value.framing.name).apply()
        }
    var recordFormat: VideoFormat
        get() = VideoFormat(VideoOrientation.entries.firstOrNull { it.name == prefs.getString("record-orientation", null) }
            ?: VideoOrientation.ORIGINAL, VideoFraming.entries.firstOrNull { it.name == prefs.getString("record-framing", null) }
            ?: VideoFraming.BLUR)
        set(value) {
            prefs.edit().putString("record-orientation", value.orientation.name).putString("record-framing", value.framing.name).apply()
        }
    fun observeVideoFormats(onChange: () -> Unit): AutoCloseable {
        val changes = setOf("record-orientation", "record-framing", "landscape", "stream-framing")
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, name -> if (name in changes) onChange() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return AutoCloseable { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var speak: Boolean
        get() = prefs.getBoolean("speak", true)
        set(value) { prefs.edit().putBoolean("speak", value).apply() }
    var handsFreeGeminiAudio: Boolean
        get() = prefs.getBoolean("hands-free-gemini-audio", false)
        set(value) { prefs.edit().putBoolean("hands-free-gemini-audio", value).apply() }
    var language: AppLanguage
        get() = AppLanguage.entries.firstOrNull { it.tag == prefs.getString("language", null) } ?: AppLanguage.SPANISH
        set(value) { prefs.edit().putString("language", value.tag).apply() }
    fun wakePhrase(language: AppLanguage): String =
        prefs.getString("wake-phrase-${language.tag}", null)
            ?: prefs.getString("wake-phrase", null)?.takeIf { it in language.wakePhrases }
            ?: language.wakePhrases.first()

    fun setWakePhrase(language: AppLanguage, phrase: String) {
        prefs.edit().putString("wake-phrase-${language.tag}", phrase).apply()
    }
    var voiceRate: Float
        get() = prefs.getFloat("voice-rate", 1f)
        set(value) { prefs.edit().putFloat("voice-rate", value).apply() }
    var pitch: Float
        get() = prefs.getFloat("voice-pitch", 1f)
        set(value) { prefs.edit().putFloat("voice-pitch", value).apply() }
}

internal fun storeCapture(context: Context, image: Bitmap?, source: Uri?): Uri {
    val video = source != null
    val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val directory = if (video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, "MetaLima-${System.currentTimeMillis()}.${if (video) "mp4" else "jpg"}")
        put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/MetaLima Ai")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val target = resolver.insert(collection, values) ?: error(context.interfaceString(R.string.no_se_pudo_abrir_la_3))
    try {
        resolver.openOutputStream(target)?.use { output ->
            if (source != null) resolver.openInputStream(source)?.use { it.copyTo(output) } ?: error(context.interfaceString(R.string.el_video_ya_no_esta))
            else check(image?.compress(Bitmap.CompressFormat.JPEG, 95, output) == true) { context.interfaceString(R.string.no_se_pudo_guardar_la_5) }
        } ?: error(context.interfaceString(R.string.no_se_pudo_escribir_la))
        resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return target
    } catch (failure: Exception) {
        resolver.delete(target, null, null)
        throw failure
    }
}

internal fun shareUri(context: Context, uri: Uri, type: String) {
    val intent = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, context.interfaceString(R.string.compartir_desde_metalima_ai)))
}

internal fun exportHistory(context: Context, turns: List<Turn>): Uri {
    val directory = File(context.cacheDir, "recordings").apply { mkdirs() }
    val target = File(directory, "MetaLima-historial-${System.currentTimeMillis()}.pdf")
    val document = PdfDocument()
    try {
        val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; color = Color.rgb(38, 49, 57) }
        val strong = Paint(normal).apply { typeface = Typeface.DEFAULT_BOLD }
        var pageNumber = 1
        var page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
        var y = 80f
        fun heading(canvas: Canvas) {
            val mark = Paint(strong).apply { textSize = 24f; color = Color.rgb(19, 52, 44) }
            canvas.drawText(context.interfaceString(R.string.metalima_ai_historial), 40f, 49f, mark)
            canvas.drawText(context.interfaceString(R.string.pagina, pageNumber), 492f, 49f, normal)
        }
        fun line(value: String, paint: Paint = normal) {
            if (y > 800) {
                document.finishPage(page)
                page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
                heading(page.canvas)
                y = 80f
            }
            page.canvas.drawText(value, 40f, y, paint)
            y += 16
        }
        fun paragraph(input: String) {
            input.lineSequence().forEach { raw ->
                var remaining = raw.trim()
                while (remaining.isNotEmpty()) {
                    val measured = normal.breakText(remaining, true, 515f, null).coerceAtLeast(1)
                    val divide = if (measured < remaining.length) remaining.take(measured).lastIndexOf(' ').takeIf { it > 0 } ?: measured else measured
                    line(remaining.take(divide)); remaining = remaining.drop(divide).trimStart()
                }
                if (raw.isBlank()) line("")
            }
        }
        heading(page.canvas)
        val date = DateFormat.getDateTimeInstance(DateFormat.DEFAULT, DateFormat.DEFAULT, context.interfaceLocale())
        turns.forEach { turn ->
            line(date.format(Date(turn.time)), strong)
            paragraph(context.interfaceString(R.string.tu_2, turn.user))
            paragraph(context.interfaceString(R.string.ia, turn.answer))
            line("")
        }
        document.finishPage(page)
        target.outputStream().use(document::writeTo)
    } finally { document.close() }
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
}
