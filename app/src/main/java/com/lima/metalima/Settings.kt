package com.lima.metalima

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lima.metalima.wearables.WearablesViewModel

@Composable internal fun StudioSettings(activity: MainActivity, glasses: WearablesViewModel, ai: AssistantViewModel, prefs: StudioPrefs, speaks: Boolean, setSpeaks: (Boolean) -> Unit,
    language: AppLanguage, setLanguage: (AppLanguage) -> Unit, phrase: String, setPhrase: (String) -> Unit, notify: (String) -> Unit, providerToConfigure: Provider? = null,
    skin: Skin, setSkin: (Skin) -> Unit) {
    val device by glasses.uiState.collectAsStateWithLifecycle()
    val state by ai.state.collectAsStateWithLifecycle()
    var provider by remember(state.provider, providerToConfigure) { mutableStateOf(providerToConfigure ?: state.provider) }
    var model by remember(provider) { mutableStateOf(ai.configuredModel(provider)) }
    var key by remember(provider) { mutableStateOf("") }
    var workspace by remember { mutableStateOf(ai.workspace) }
    var china by remember { mutableStateOf(ai.china) }
    var platform by remember { mutableStateOf(prefs.platform) }
    var url by remember(platform) { mutableStateOf(prefs.url(platform)) }
    var streamKey by remember(platform) { mutableStateOf("") }
    var quality by remember { mutableStateOf(prefs.quality) }
    var fps by remember { mutableIntStateOf(prefs.fps) }
    var landscape by remember { mutableStateOf(prefs.landscape) }
    var rate by remember { mutableFloatStateOf(prefs.voiceRate) }
    var pitch by remember { mutableFloatStateOf(prefs.pitch) }
    var advancedCamera by rememberSaveable { mutableStateOf(false) }
    var advancedAi by rememberSaveable { mutableStateOf(false) }
    var system by rememberSaveable { mutableStateOf(false) }
    var devices by rememberSaveable { mutableStateOf(false) }
    var streamingStudio by rememberSaveable { mutableStateOf(false) }
    var streamFps by remember { mutableIntStateOf(prefs.streamFps) }
    var streamAudio by remember { mutableStateOf(prefs.streamAudio) }

    Heading("Ajustes")
    Section("Idioma y voz")
    Block {
        Choice("Idioma del asistente", language.label, AppLanguage.entries.map { it.label }) { label ->
            setLanguage(AppLanguage.entries.first { it.label == label })
        }
        Text("Se aplica a las respuestas, el dictado y la lectura en voz alta.", color = Muted)
        Choice("Frase de activación", phrase, language.wakePhrases, setPhrase)
        Text("Para escuchar la frase, pulsa Activar frase de voz en Inicio. Usa el reconocimiento sin conexión de Android.", color = Muted)
    }
    Section("Apariencia")
    Block {
        Skin.entries.forEach { appearance ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioButton(selected = skin == appearance, onClick = { setSkin(appearance) })
                Text(appearance.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box(Modifier.size(18.dp).background(Color(appearance.accent), CircleShape))
                Box(Modifier.size(18.dp).background(Color(appearance.background), CircleShape))
            }
        }
    }
    Section("Gafas y sistema")
    ActionTile(Icons.Outlined.Bluetooth, if (device.isRegistered) "Desvincular mis gafas" else "Vincular mis gafas",
        if (device.isRegistering) "Vinculación en curso" else if (device.hasActiveDevice) "Dispositivo activo" else "Conexión con Meta AI") {
        if (device.isRegistered) runCatching { glasses.startUnregistration(activity) }.onFailure { notify("No se pudo abrir Meta AI.") }
        else if (!device.isRegistering) activity.connectGlasses()
    }
    ActionTile(Icons.Outlined.Devices, "Dispositivos conectados", "${device.devices.size} detectados") { devices = !devices }
    if (devices) Block {
        if (device.devices.isEmpty()) Text("No hay gafas disponibles.", color = Muted)
        device.devices.forEachIndexed { index, id ->
            val details = device.deviceDetails[id]
            Text(details?.name?.takeIf { it.isNotBlank() } ?: "Gafas Meta ${index + 1}", style = MaterialTheme.typography.titleMedium)
            details?.let {
                val linked = when (it.linkState.name) { "CONNECTED" -> "Conectadas"; "CONNECTING" -> "Conectando"; else -> "Desconectadas" }
                val hinge = when (it.hingeState.name) { "OPEN" -> "Abiertas"; "CLOSED" -> "Plegadas"; else -> "Posición desconocida" }
                val charge = if (it.chargingState.name == "CHARGING") " · Cargando" else ""
                val battery = it.batteryLevel.takeIf { level -> level in 0..100 }?.let { level -> " · $level%" }.orEmpty()
                Text("$linked · $hinge$battery$charge", color = Muted)
                it.firmwareInfo?.takeIf { version -> version.isNotBlank() }?.let { version -> Text("Firmware $version", color = Muted) }
            }
        }
        if (device.isFirmwareUpdateRequired) TextButton(onClick = { glasses.openFirmwareUpdate(activity) }) { Text("Actualizar firmware") }
    }
    ActionTile(Icons.Outlined.Tune, "Ajustes avanzados de vídeo", "Calidad, fotogramas y orientación") { advancedCamera = !advancedCamera }
    if (advancedCamera) Block {
        val qualities = linkedMapOf("Fluida · Automática" to "FLUIDA", "Baja" to "BAJA", "Media" to "MEDIA", "Alta" to "ALTA")
        Choice("Calidad de las gafas", qualities.entries.firstOrNull { it.value == quality }?.key ?: qualities.keys.first(), qualities.keys.toList()) {
            quality = qualities.getValue(it); prefs.quality = quality
        }
        if (quality != "FLUIDA") Choice("Fotogramas por segundo", "$fps", listOf("15", "24", "30")) { fps = it.toInt(); prefs.fps = fps }
        else Text("Parte de 24 FPS y baja a 15 FPS si se acumula retardo, mientras no estés grabando.", color = Muted)
        SwitchRow("Emisión en horizontal (16:9)", landscape) { landscape = it; prefs.landscape = it }
        Text("Reabre la cámara para aplicar cambios de calidad o fotogramas.", color = Muted)
    }
    ActionTile(Icons.Outlined.MonitorHeart, "Estado del sistema", "Permisos, Bluetooth y batería") { system = !system }
    if (system) Block {
        fun allowed(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val power = activity.getSystemService(PowerManager::class.java)
        Text("Dispositivos cercanos · ${if (allowed(Manifest.permission.BLUETOOTH_CONNECT)) "Permitido" else "Pendiente"}")
        Text("Micrófono · ${if (allowed(Manifest.permission.RECORD_AUDIO)) "Permitido" else "Pendiente"}")
        Text("Ubicación · ${if (allowed(Manifest.permission.ACCESS_COARSE_LOCATION)) "Permitida" else "Pendiente"}")
        Text("Batería · ${if (power.isIgnoringBatteryOptimizations(activity.packageName)) "Sin optimización" else "Optimización activa"}")
        TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }) { Text("Permisos y batería en Android") }
    }
    Section("Inteligencia artificial")
    Block {
        Choice("Plataforma de IA", provider.label, Provider.entries.map { it.label }) { name -> provider = Provider.entries.first { it.label == name } }
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("Modelo de ${provider.label}") }, singleLine = true)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text("Clave API de ${provider.label}") },
            placeholder = { Text(if (provider == state.provider && state.hasKey) "Clave guardada" else "Pega tu clave") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (provider == Provider.QWEN) {
            OutlinedTextField(workspace, { workspace = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("ID del espacio de Alibaba") })
            Choice("Región del espacio", if (china) "China (Pekín)" else "Singapur", listOf("Singapur", "China (Pekín)")) { china = it != "Singapur" }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.busy, onClick = {
                if (providerToConfigure == provider && key.isNotBlank()) ai.saveKey(provider, key)
                else ai.configure(provider, key, model, workspace, china)
                key = ""
            }) { Text("Guardar") }
            OutlinedButton(enabled = !state.busy && state.hasKey && provider == state.provider && key.isBlank() && model == ai.configuredModel(provider), onClick = ai::checkConnection) { Text("Probar conexión") }
        }
        if (state.hasKey && state.provider == provider) TextButton(enabled = !state.busy, onClick = { ai.forgetKey(provider); key = "" }) { Text("Eliminar clave guardada") }
    }
    ActionTile(Icons.Outlined.RecordVoiceOver, "Ajustes avanzados de IA", "Lectura de respuestas, velocidad y tono") { advancedAi = !advancedAi }
    if (advancedAi) Block {
        SwitchRow("Leer respuestas en voz alta", speaks, change = setSpeaks)
        Text("Velocidad de voz · ${"%.1f".format(rate)}×")
        Slider(value = rate, valueRange = .6f..1.5f, onValueChange = { rate = it; prefs.voiceRate = it })
        Text("Tono de voz")
        Slider(value = pitch, valueRange = .65f..1.35f, onValueChange = { pitch = it; prefs.pitch = it })
    }
    Section("Emisión en directo")
    Block {
        PlatformChoice(platform) { platform = it }
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("URL de emisión") }, placeholder = { Text("rtmps://servidor/aplicación") }, singleLine = true)
        OutlinedTextField(streamKey, { streamKey = it }, Modifier.fillMaxWidth(), label = { Text("Clave de emisión") },
            placeholder = { Text(if (prefs.secret(platform)) "Clave guardada" else "La proporciona tu plataforma") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text("Copia la URL y la clave del panel de emisión de tu plataforma.", color = Muted)
        Button(onClick = {
            val result = runCatching {
                val parsed = java.net.URI(url.trim())
                require(parsed.scheme in setOf("rtmp", "rtmps") && !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.fragment == null && parsed.query == null)
                prefs.saveDestination(platform, url, streamKey)
            }
            result.onSuccess { streamKey = ""; notify("Destino guardado.") }.onFailure { notify("Introduce una URL RTMP o RTMPS válida y pon la clave en su campo.") }
        }) { Text("Guardar destino") }
    }
    ActionTile(Icons.Outlined.Tune, "Estudio de emisión", "Fotogramas y salida de audio") { streamingStudio = !streamingStudio }
    if (streamingStudio) Block {
        Choice("Fotogramas del directo", "$streamFps", listOf("15", "24", "30")) { streamFps = it.toInt(); prefs.streamFps = streamFps }
        Choice("Salida de audio del móvil", streamAudio, listOf("Automática", "Altavoz del móvil", "Bluetooth")) { streamAudio = it; prefs.streamAudio = it }
        TextButton(onClick = { streamFps = 24; prefs.streamFps = 24; streamAudio = "Automática"; prefs.streamAudio = streamAudio }) { Text("Restablecer valores") }
    }
    Section("Acerca de")
    Text("MetaLima Ai · 0.3.0", color = Muted)
}

@Composable internal fun Choice(label: String, selected: String, items: List<String>, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Muted)
        Box {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { expanded = true }) {
                Text(selected, Modifier.weight(1f)); Icon(Icons.Outlined.KeyboardArrowDown, "Ver opciones")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { expanded = false; select(item) }) }
            }
        }
    }
}

@Composable internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.weight(1f)); Switch(checked = checked, enabled = enabled, onCheckedChange = change)
    }
}
