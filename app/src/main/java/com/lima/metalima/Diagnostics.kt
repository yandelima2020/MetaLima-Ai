package com.lima.metalima

import androidx.compose.ui.res.stringResource
import com.lima.metalima.livemode.displayLabel
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
    Heading(stringResource(R.string.diagnostico), stringResource(R.string.comprueba_la_conexion_el_sonido))
    Block {
        Text(stringResource(R.string.gafas_y_permisos), style = MaterialTheme.typography.titleLarge)
        DiagnosticValue(stringResource(R.string.bluetooth_de_meta), if (hardware.hasActiveDevice) stringResource(R.string.gafas_disponibles) else stringResource(R.string.sin_conexion_activa))
        DiagnosticValue(stringResource(R.string.dispositivos_cercanos), if (nearbyAllowed) stringResource(R.string.permitido) else stringResource(R.string.pendiente))
        DiagnosticValue(stringResource(R.string.microfono), if (micAllowed) stringResource(R.string.permitido) else stringResource(R.string.pendiente))
        if (hardware.isFirmwareUpdateRequired) Text(stringResource(R.string.meta_indica_que_el_firmware), color = Muted)
    }
    Block {
        Text(stringResource(R.string.sonido_en_las_gafas), style = MaterialTheme.typography.titleLarge)
        Text(audioResult ?: stringResource(R.string.habla_durante_la_prueba_y), color = Muted)
        OutlinedButton(enabled = !audioTesting, onClick = testAudio) { Text(if (audioTesting) stringResource(R.string.probando_sonido) else stringResource(R.string.probar_microfono_y_altavoz)) }
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
        Text(value)
    }
}
