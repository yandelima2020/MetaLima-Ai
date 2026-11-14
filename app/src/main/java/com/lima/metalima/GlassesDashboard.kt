package com.lima.metalima

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lima.metalima.camera.CameraUiState
import com.lima.metalima.wearables.WearablesUiState

@Composable internal fun GlassesDashboard(
    hardware: WearablesUiState, capture: CameraUiState, diagnose: () -> Unit, connect: () -> Unit
) {
    val device = hardware.deviceDetails.values.firstOrNull { it.linkState.name == "CONNECTED" }
    val name = device?.name?.takeIf { it.isNotBlank() } ?: "Ray-Ban Meta"
    val battery = device?.batteryLevel?.takeIf { it in 0..100 }
    val connected = hardware.hasActiveDevice
    val status = when {
        connected -> "Conectadas"
        hardware.isRegistering -> "Vinculando…"
        hardware.isRegistered -> "Esperando conexión"
        else -> "Sin vincular"
    }
    val statusColor = if (connected) Lime else Muted
    val accent = Lime
    Column(Modifier.fillMaxWidth().clip(Panel)
        .background(Brush.radialGradient(listOf(accent.copy(alpha = .12f), SurfaceTone), center = Offset(410f, 220f), radius = 550f))
        .border(1.dp, Line, Panel).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Mis gafas", style = MaterialTheme.typography.labelLarge, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Text(status, color = statusColor, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(name, style = MaterialTheme.typography.headlineMedium, maxLines = 2)
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.94f)) {
            Image(painterResource(R.drawable.rayban_meta_wayfarer), "Ray-Ban Meta Wayfarer con cámara y LED de privacidad",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            if (capture.isRecording || capture.isCapturingPhoto) {
                Box(Modifier.offset(maxWidth * .915f - 7.dp, maxHeight * .395f - 7.dp).size(14.dp)
                    .background(Brush.radialGradient(listOf(Color.White.copy(alpha = .83f), Color.Transparent)), CircleShape))
            }
        }
        HorizontalDivider(color = Line)
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BatteryStd, null, Modifier.size(19.dp), tint = if (battery != null) accent else Muted)
                Text(battery?.let { "$it% de batería" } ?: "Batería sin lectura", fontWeight = FontWeight.Medium)
            }
            Text(when {
                capture.isRecording -> "Grabando"
                capture.isCapturingPhoto -> "Capturando"
                capture.isStreaming -> "Cámara activa"
                else -> "Cámara en reposo"
            }, color = if (capture.isStreaming) accent else Muted, style = MaterialTheme.typography.labelMedium)
        }
        if (battery != null) LinearProgressIndicator(progress = { battery / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp),
            color = accent, trackColor = Line)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hardware.isRegistered) Button(onClick = connect, enabled = !hardware.isRegistering, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Bluetooth, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Vincular")
            }
            OutlinedButton(onClick = diagnose, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.MonitorHeart, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)); Text("Diagnóstico")
            }
        }
    }
}
