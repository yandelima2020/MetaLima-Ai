package com.lima.metalima

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.pedro.common.ConnectChecker
import com.pedro.library.rtmp.RtmpDisplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class BroadcastService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var display: RtmpDisplay? = null
    private var recording: File? = null
    private var routedAudio = false
    private var previousAudioMode = AudioManager.MODE_NORMAL

    companion object {
        const val ACTION_STOP = "com.lima.metalima.ALTO_DIRECTO"
        private val current = MutableStateFlow(Transmission())
        val state = current.asStateFlow()
        data class Transmission(val active: Boolean = false, val message: String = "", val recording: Boolean = false, val saved: Uri? = null)
        fun dismissSaved() { current.value = current.value.copy(saved = null) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish("Directo finalizado.")
            return START_NOT_STICKY
        }
        if (intent == null || display != null) return START_NOT_STICKY
        val token = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("consent", Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>("consent")
        val url = intent.getStringExtra("endpoint")
        val record = intent.getBooleanExtra("record", false)
        if (token == null || (!record && url.isNullOrBlank())) {
            finish("Faltan el permiso de pantalla o el destino del directo.")
            return START_NOT_STICKY
        }
        val audio = intent.getBooleanExtra("audio", false)
        try {
            startForeground(204, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                if (audio) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            if (audio) routeAudio(intent.getStringExtra("route").orEmpty())
            current.value = Transmission(true, if (record) "Grabando visor…" else "Conectando con la plataforma…", record)
            val encoder = RtmpDisplay(this, false, object : ConnectChecker {
                override fun onConnectionStarted(url: String) = Unit
                override fun onConnectionSuccess() { main.post { if (display != null) current.value = Transmission(true, "Emisión en directo.") } }
                override fun onConnectionFailed(reason: String) { main.post { if (display != null) finish("El servidor rechazó la emisión o no está disponible.") } }
                override fun onDisconnect() { main.post { if (display != null) finish("Se ha interrumpido la emisión.") } }
                override fun onAuthError() { main.post { if (display != null) finish("La plataforma ha rechazado la clave de emisión.") } }
                override fun onAuthSuccess() = Unit
            })
            display = encoder
            val wide = intent.getBooleanExtra("landscape", false)
            val height = if (wide) 720 else 1280
            val width = if (wide) 1280 else 720
            encoder.setMediaProjectionCallback(object : MediaProjection.Callback() {
                override fun onStop() { main.post { if (display != null) finish("Se ha detenido la captura de pantalla.") } }
            })
            check(encoder.prepareVideo(width, height, intent.getIntExtra("fps", 24), 3_000_000, 0, resources.displayMetrics.densityDpi))
            if (audio) check(encoder.prepareAudio(96_000, 44100, false))
            encoder.setIntentResult(Activity.RESULT_OK, token)
            if (record) {
                val dir = File(cacheDir, "recordings").apply { mkdirs() }
                val target = File.createTempFile("interactiva-", ".mp4", dir)
                recording = target
                encoder.startRecord(target.absolutePath)
            } else encoder.startStream(requireNotNull(url))
        } catch (_: Exception) {
            finish("No se pudo iniciar el directo. Comprueba los permisos y el formato de vídeo.")
        }
        return START_NOT_STICKY
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("live", "Emisiones en directo", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 204, Intent(this, BroadcastService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "live").setSmallIcon(R.drawable.smart_glasses_icon)
            .setContentTitle("MetaLima Ai · Directo").setContentText("Compartiendo una captura de pantalla")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Detener", stop).build()).build()
    }

    private fun routeAudio(route: String) {
        val manager = getSystemService(AudioManager::class.java)
        val type = when (route) {
            "Altavoz del móvil" -> AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            "Bluetooth" -> AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            else -> return
        }
        val target = manager.availableCommunicationDevices.firstOrNull {
            it.type == type || (route == "Bluetooth" && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        } ?: return
        previousAudioMode = manager.mode
        manager.mode = AudioManager.MODE_IN_COMMUNICATION
        routedAudio = manager.setCommunicationDevice(target)
        if (!routedAudio) manager.mode = previousAudioMode
    }

    private fun release() {
        val encoder = display
        display = null
        if (routedAudio) {
            val manager = getSystemService(AudioManager::class.java)
            manager.clearCommunicationDevice()
            manager.mode = previousAudioMode
            routedAudio = false
        }
        runCatching {
            if (recording != null) encoder?.stopRecord() else encoder?.stopStream()
        }
    }

    private fun finish(message: String) {
        release()
        val file = recording
        recording = null
        if (file == null) current.value = Transmission(false, message)
        else {
            current.value = Transmission(false, "Guardando grabación interactiva…")
            val app = applicationContext
            CoroutineScope(Dispatchers.IO).launch {
                val saved = file.takeIf { it.length() > 1024 }?.let { target ->
                    runCatching {
                        val path = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", target)
                        storeCapture(app, null, path)
                    }.getOrNull()
                }
                file.delete()
                main.post {
                    val previous = current.value
                    current.value = previous.copy(
                        message = if (previous.active) previous.message else if (saved == null) "No se pudo guardar el vídeo interactivo." else "Grabación interactiva guardada.",
                        saved = saved
                    )
                }
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        release()
        recording?.delete()
        recording = null
        if (current.value.active) current.value = Transmission(false, "Directo finalizado.")
        super.onDestroy()
    }
}
