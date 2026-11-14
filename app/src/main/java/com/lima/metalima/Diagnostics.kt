package com.lima.metalima

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lima.metalima.camera.CameraUiState
import com.lima.metalima.livemode.LiveConversationState
import com.lima.metalima.livemode.LiveModel
import com.lima.metalima.wearables.WearablesUiState

@Composable internal fun DiagnosticsScreen(
    hardware: WearablesUiState, camera: CameraUiState, conversation: LiveConversationState, model: LiveModel,
    nearbyAllowed: Boolean, micAllowed: Boolean, audioResult: String?, audioTesting: Boolean, apiResult: String?, apiTesting: Boolean,
    testAudio: () -> Unit, testApi: () -> Unit, testCamera: () -> Unit, updateGlasses: () -> Unit
) {
    Heading("Diagnóstico", "Comprueba la conexión, el sonido y la cámara por separado.")
    Block {
        Text("Gafas y permisos", style = MaterialTheme.typography.titleLarge)
        DiagnosticValue("Bluetooth de Meta", if (hardware.hasActiveDevice) "Gafas disponibles" else "Sin conexión activa")
        DiagnosticValue("Dispositivos cercanos", if (nearbyAllowed) "Permitido" else "Pendiente")
        DiagnosticValue("Micrófono", if (micAllowed) "Permitido" else "Pendiente")
        if (hardware.isFirmwareUpdateRequired) Text("Meta indica que el firmware requiere una actualización.", color = Muted)
    }
    Block {
        Text("Sonido en las gafas", style = MaterialTheme.typography.titleLarge)
        Text(audioResult ?: "Habla durante la prueba y comprueba si oyes el tono en las gafas.", color = Muted)
        OutlinedButton(enabled = !audioTesting, onClick = testAudio) { Text(if (audioTesting) "Probando sonido…" else "Probar micrófono y altavoz") }
    }
    Block {
        Text("Cámara", style = MaterialTheme.typography.titleLarge)
        val status = when {
            camera.needsGlassesAppUpdate -> "Meta pide revisar Conexiones de aplicaciones."
            camera.isStreaming && camera.hasReceivedFirstFrame -> "Llegan fotogramas de las gafas."
            camera.isStreaming -> "Esperando el primer fotograma."
            camera.isBusy -> "Abriendo conexión con las gafas…"
            else -> "Abre el visor para probar la imagen."
        }
        Text(status, color = Muted)
        if (camera.needsGlassesAppUpdate) OutlinedButton(onClick = updateGlasses) { Text("Revisar conexión en Meta AI") }
        OutlinedButton(onClick = testCamera) { Text("Abrir prueba de cámara") }
    }
    Block {
        Text(model.label, style = MaterialTheme.typography.titleLarge)
        apiResult?.let { Text(it, color = Muted) }
        conversation.setupMs?.let { DiagnosticValue("Inicio de la última sesión", "$it ms") }
        conversation.firstReplyMs?.let { DiagnosticValue("Primera respuesta desde la voz detectada", "$it ms") }
        OutlinedButton(enabled = !apiTesting, onClick = testApi) { Text(if (apiTesting) "Comprobando…" else "Probar conexión Live") }
    }
}

@Composable private fun DiagnosticValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.weight(1f), color = Muted)
        Text(value)
    }
}
