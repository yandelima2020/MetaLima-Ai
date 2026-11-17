package com.lima.metalima

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.ui.res.stringResource
import com.lima.metalima.livemode.displayLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.lima.metalima.camera.CameraUiState
import com.lima.metalima.livemode.LiveConversationState
import com.lima.metalima.livemode.LiveModel
import com.lima.metalima.wearables.WearablesUiState
import kotlinx.coroutines.delay

private data class AudioInventory(val nearby: Boolean, val microphone: Boolean,
    val bluetooth: Boolean?, val bluetoothSupported: Boolean, val routes: List<AudioDeviceInfo>)

@SuppressLint("MissingPermission")
private fun MainActivity.audioInventory(): AudioInventory {
    val nearby = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    val microphone = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val adapter = getSystemService(BluetoothManager::class.java)?.adapter
    val audio = getSystemService(AudioManager::class.java)
    val bluetooth = if (nearby) runCatching { adapter?.isEnabled }.getOrNull() else null
    val routes = if (nearby) runCatching {
        audio.availableCommunicationDevices.filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    }.getOrDefault(emptyList()) else emptyList()
    return AudioInventory(nearby, microphone, bluetooth, adapter != null, routes)
}

@Composable internal fun DiagnosticsScreen(
    activity: MainActivity, hardware: WearablesUiState, camera: CameraUiState, conversation: LiveConversationState, model: LiveModel,
    audioResult: String?, audioTesting: Boolean, apiResult: String?, apiTesting: Boolean,
    testAudio: (Boolean) -> Unit, testApi: () -> Unit, testCamera: () -> Unit, updateGlasses: () -> Unit
) {
    var inventory by remember(activity) { mutableStateOf(activity.audioInventory()) }
    LaunchedEffect(activity) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                inventory = activity.audioInventory()
                delay(1200)
            }
        }
    }
    Heading(stringResource(R.string.diagnostico), stringResource(R.string.comprueba_la_conexion_el_sonido))
    Block {
        Text(stringResource(R.string.gafas_y_permisos), style = MaterialTheme.typography.titleLarge)
        DiagnosticValue(stringResource(R.string.bluetooth_de_meta), if (hardware.hasActiveDevice) stringResource(R.string.gafas_disponibles) else stringResource(R.string.sin_conexion_activa))
        DiagnosticValue("Bluetooth", stringResource(when {
            !inventory.bluetoothSupported -> R.string.bt_unavailable
            inventory.bluetooth == true -> R.string.bt_on
            inventory.bluetooth == false -> R.string.bt_off
            else -> R.string.bt_unknown
        }))
        DiagnosticValue(stringResource(R.string.dispositivos_cercanos), stringResource(if (inventory.nearby) R.string.permitido else R.string.pendiente))
        DiagnosticValue(stringResource(R.string.microfono), stringResource(if (inventory.microphone) R.string.permitido else R.string.pendiente))
        if (inventory.bluetooth == false) TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
            Text(stringResource(R.string.bt_settings))
        }
        if (hardware.isFirmwareUpdateRequired) Text(stringResource(R.string.meta_indica_que_el_firmware), color = Muted)
    }
    Block {
        Text(stringResource(R.string.audio_diagnostics), style = MaterialTheme.typography.titleLarge)
        if (inventory.bluetooth == true) {
            Text(stringResource(R.string.bt_routes), style = MaterialTheme.typography.titleMedium)
            if (inventory.routes.isEmpty()) Text(stringResource(R.string.bt_no_routes), color = Muted)
            inventory.routes.forEach { route ->
                val protocol = if (route.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) "SCO" else "LE Audio"
                Text("${route.productName} · $protocol", color = Muted)
            }
        }
        Text(audioResult ?: stringResource(R.string.habla_durante_la_prueba_y), color = Muted)
        OutlinedButton(enabled = !audioTesting, onClick = { testAudio(false) }) { Text(stringResource(R.string.audio_test_glasses)) }
        OutlinedButton(enabled = !audioTesting, onClick = { testAudio(true) }) { Text(stringResource(R.string.audio_test_phone)) }
    }
    Block {
        Text(stringResource(R.string.camara), style = MaterialTheme.typography.titleLarge)
        val status = when {
            camera.needsGlassesAppUpdate -> stringResource(R.string.meta_pide_revisar_conexiones_de)
            camera.isStreaming && camera.hasReceivedFirstFrame -> stringResource(R.string.llegan_fotogramas_de_las_gafas)
            camera.isStreaming -> stringResource(R.string.esperando_el_primer_fotograma)
            camera.isBusy -> stringResource(R.string.abriendo_conexion_con_las_gafas)
            else -> stringResource(R.string.abre_el_visor_para_probar)
        }
        Text(status, color = Muted)
        if (camera.needsGlassesAppUpdate) OutlinedButton(onClick = updateGlasses) { Text(stringResource(R.string.revisar_conexion_en_meta_ai)) }
        OutlinedButton(onClick = testCamera) { Text(stringResource(R.string.abrir_prueba_de_camara)) }
    }
    Block {
        Text(model.displayLabel(), style = MaterialTheme.typography.titleLarge)
        apiResult?.let { Text(it, color = Muted) }
        conversation.setupMs?.let { DiagnosticValue(stringResource(R.string.inicio_de_la_ultima_sesion), "$it ms") }
        conversation.firstReplyMs?.let { DiagnosticValue(stringResource(R.string.primera_respuesta_desde_la_voz), "$it ms") }
        OutlinedButton(enabled = !apiTesting, onClick = testApi) { Text(if (apiTesting) stringResource(R.string.comprobando) else stringResource(R.string.probar_conexion_live)) }
    }
}

@Composable private fun DiagnosticValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Muted)
        Text(value, modifier = Modifier.weight(1f))
    }
}
