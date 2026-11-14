package com.lima.metalima

import androidx.compose.ui.res.stringResource
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

    Heading(stringResource(R.string.ajustes))
    Section(stringResource(R.string.idioma_y_voz))
    Block {
        val systemLanguage = stringResource(R.string.language_system)
        val interfaceLanguage = activity.selectedInterfaceLanguage()
        Choice(stringResource(R.string.language_app), interfaceLanguage?.label ?: systemLanguage,
            listOf(systemLanguage) + AppLanguage.entries.map { it.label }) { label ->
            activity.setInterfaceLanguage(AppLanguage.entries.firstOrNull { it.label == label })
        }
        Text(stringResource(R.string.language_app_summary), color = Muted)
        Choice(stringResource(R.string.idioma_del_asistente), language.label, AppLanguage.entries.map { it.label }) { label ->
            setLanguage(AppLanguage.entries.first { it.label == label })
        }
        Text(stringResource(R.string.se_aplica_a_las_respuestas), color = Muted)
        Choice(stringResource(R.string.frase_de_activacion), phrase, language.wakePhrases, select = setPhrase)
        Text(stringResource(R.string.para_escuchar_la_frase_pulsa), color = Muted)
    }
    Section(stringResource(R.string.apariencia))
    Block {
        Skin.entries.forEach { appearance ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RadioButton(selected = skin == appearance, onClick = { setSkin(appearance) })
                Text(stringResource(appearance.label), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Box(Modifier.size(18.dp).background(Color(appearance.accent), CircleShape))
                Box(Modifier.size(18.dp).background(Color(appearance.background), CircleShape))
            }
        }
    }
    Section(stringResource(R.string.gafas_y_sistema))
    ActionTile(Icons.Outlined.Bluetooth, if (device.isRegistered) stringResource(R.string.desvincular_mis_gafas) else stringResource(R.string.vincular_mis_gafas),
        if (device.isRegistering) stringResource(R.string.vinculacion_en_curso) else if (device.hasActiveDevice) stringResource(R.string.dispositivo_activo) else stringResource(R.string.conexion_con_meta_ai)) {
        if (device.isRegistered) runCatching { glasses.startUnregistration(activity) }.onFailure { notify(activity.getString(R.string.no_se_pudo_abrir_meta_2)) }
        else if (!device.isRegistering) activity.connectGlasses()
    }
    ActionTile(Icons.Outlined.Devices, stringResource(R.string.dispositivos_conectados), stringResource(R.string.detectados, device.devices.size)) { devices = !devices }
    if (devices) Block {
        if (device.devices.isEmpty()) Text(stringResource(R.string.no_hay_gafas_disponibles), color = Muted)
        device.devices.forEachIndexed { index, id ->
            val details = device.deviceDetails[id]
            Text(details?.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.gafas_meta, index + 1), style = MaterialTheme.typography.titleMedium)
            details?.let {
                val linked = when (it.linkState.name) { "CONNECTED" -> stringResource(R.string.conectadas); "CONNECTING" -> stringResource(R.string.conectando_2); else -> stringResource(R.string.desconectadas) }
                val hinge = when (it.hingeState.name) { "OPEN" -> stringResource(R.string.abiertas); "CLOSED" -> stringResource(R.string.plegadas); else -> stringResource(R.string.posicion_desconocida) }
                val charge = if (it.chargingState.name == "CHARGING") stringResource(R.string.cargando) else ""
                val battery = it.batteryLevel.takeIf { level -> level in 0..100 }?.let { level -> " · $level%" }.orEmpty()
                Text("$linked · $hinge$battery$charge", color = Muted)
                it.firmwareInfo?.takeIf { version -> version.isNotBlank() }?.let { version -> Text(stringResource(R.string.firmware, version), color = Muted) }
            }
        }
        if (device.isFirmwareUpdateRequired) TextButton(onClick = { glasses.openFirmwareUpdate(activity) }) { Text(stringResource(R.string.actualizar_firmware)) }
    }
    ActionTile(Icons.Outlined.Tune, stringResource(R.string.ajustes_avanzados_de_video), stringResource(R.string.calidad_fotogramas_y_orientacion)) { advancedCamera = !advancedCamera }
    if (advancedCamera) Block {
        val qualities = linkedMapOf(stringResource(R.string.fluida_automatica) to "FLUIDA", stringResource(R.string.baja) to "BAJA", stringResource(R.string.media) to "MEDIA", stringResource(R.string.alta) to "ALTA")
        Choice(stringResource(R.string.calidad_de_las_gafas), qualities.entries.firstOrNull { it.value == quality }?.key ?: qualities.keys.first(), qualities.keys.toList()) {
            quality = qualities.getValue(it); prefs.quality = quality
        }
        if (quality != "FLUIDA") Choice(stringResource(R.string.fotogramas_por_segundo), "$fps", listOf("15", "24", "30")) { fps = it.toInt(); prefs.fps = fps }
        else Text(stringResource(R.string.parte_de_fps_y_baja), color = Muted)
        SwitchRow(stringResource(R.string.emision_en_horizontal), landscape) { landscape = it; prefs.landscape = it }
        Text(stringResource(R.string.reabre_la_camara_para_aplicar), color = Muted)
    }
    ActionTile(Icons.Outlined.MonitorHeart, stringResource(R.string.estado_del_sistema), stringResource(R.string.permisos_bluetooth_y_bateria)) { system = !system }
    if (system) Block {
        fun allowed(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val power = activity.getSystemService(PowerManager::class.java)
        Text(stringResource(R.string.dispositivos_cercanos_2, if (allowed(Manifest.permission.BLUETOOTH_CONNECT)) stringResource(R.string.permitido) else stringResource(R.string.pendiente)))
        Text(stringResource(R.string.microfono_2, if (allowed(Manifest.permission.RECORD_AUDIO)) stringResource(R.string.permitido) else stringResource(R.string.pendiente)))
        Text(stringResource(R.string.ubicacion, if (allowed(Manifest.permission.ACCESS_COARSE_LOCATION)) stringResource(R.string.permitida) else stringResource(R.string.pendiente)))
        Text(stringResource(R.string.bateria, if (power.isIgnoringBatteryOptimizations(activity.packageName)) stringResource(R.string.sin_optimizacion) else stringResource(R.string.optimizacion_activa)))
        TextButton(onClick = { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))) }) { Text(stringResource(R.string.permisos_y_bateria_en_android)) }
    }
    Section(stringResource(R.string.inteligencia_artificial))
    Block {
        Choice(stringResource(R.string.plataforma_de_ia), provider.label, Provider.entries.map { it.label }) { name -> provider = Provider.entries.first { it.label == name } }
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.modelo_de, provider.label)) }, singleLine = true)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.clave_api_de, provider.label)) },
            placeholder = { Text(if (provider == state.provider && state.hasKey) stringResource(R.string.clave_guardada) else stringResource(R.string.pega_tu_clave)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        if (provider == Provider.QWEN) {
            OutlinedTextField(workspace, { workspace = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.id_del_espacio_de_alibaba)) })
            val regionSingapore = stringResource(R.string.region_singapore)
            val regionChina = stringResource(R.string.china_pekin)
            Choice(stringResource(R.string.region_del_espacio), if (china) regionChina else regionSingapore,
                listOf(regionSingapore, regionChina)) { china = it == regionChina }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !state.busy, onClick = {
                if (providerToConfigure == provider && key.isNotBlank()) ai.saveKey(provider, key)
                else ai.configure(provider, key, model, workspace, china)
                key = ""
            }) { Text(stringResource(R.string.guardar)) }
            OutlinedButton(enabled = !state.busy && state.hasKey && provider == state.provider && key.isBlank() && model == ai.configuredModel(provider), onClick = ai::checkConnection) { Text(stringResource(R.string.probar_conexion)) }
        }
        if (state.hasKey && state.provider == provider) TextButton(enabled = !state.busy, onClick = { ai.forgetKey(provider); key = "" }) { Text(stringResource(R.string.eliminar_clave_guardada)) }
    }
    ActionTile(Icons.Outlined.RecordVoiceOver, stringResource(R.string.ajustes_avanzados_de_ia), stringResource(R.string.lectura_de_respuestas_velocidad_y)) { advancedAi = !advancedAi }
    if (advancedAi) Block {
        SwitchRow(stringResource(R.string.leer_respuestas_en_voz_alta), speaks, change = setSpeaks)
        Text(stringResource(R.string.velocidad_de_voz, "%.1f".format(rate)))
        Slider(value = rate, valueRange = .6f..1.5f, onValueChange = { rate = it; prefs.voiceRate = it })
        Text(stringResource(R.string.tono_de_voz))
        Slider(value = pitch, valueRange = .65f..1.35f, onValueChange = { pitch = it; prefs.pitch = it })
    }
    Section(stringResource(R.string.emision_en_directo_2))
    Block {
        PlatformChoice(platform) { platform = it }
        OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.url_de_emision)) }, placeholder = { Text("rtmps://example.com/live") }, singleLine = true)
        OutlinedTextField(streamKey, { streamKey = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.clave_de_emision)) },
            placeholder = { Text(if (prefs.secret(platform)) stringResource(R.string.clave_guardada) else stringResource(R.string.la_proporciona_tu_plataforma)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
        Text(stringResource(R.string.copia_la_url_y_la), color = Muted)
        Button(onClick = {
            val result = runCatching {
                val parsed = java.net.URI(url.trim())
                require(parsed.scheme in setOf("rtmp", "rtmps") && !parsed.host.isNullOrBlank() && parsed.userInfo == null && parsed.fragment == null && parsed.query == null)
                prefs.saveDestination(platform, url, streamKey)
            }
            result.onSuccess { streamKey = ""; notify(activity.getString(R.string.destino_guardado)) }.onFailure { notify(activity.getString(R.string.introduce_una_url_rtmp_o)) }
        }) { Text(stringResource(R.string.guardar_destino)) }
    }
    ActionTile(Icons.Outlined.Tune, stringResource(R.string.estudio_de_emision), stringResource(R.string.fotogramas_y_salida_de_audio)) { streamingStudio = !streamingStudio }
    if (streamingStudio) Block {
        Choice(stringResource(R.string.fotogramas_del_directo), "$streamFps", listOf("15", "24", "30")) { streamFps = it.toInt(); prefs.streamFps = streamFps }
        val routes = mapOf(
            "Automática" to stringResource(R.string.route_auto),
            "Altavoz del móvil" to stringResource(R.string.route_phone),
            "Bluetooth" to "Bluetooth"
        )
        Choice(stringResource(R.string.salida_de_audio_del_movil), streamAudio, routes.keys.toList(), display = { routes.getValue(it) }) {
            streamAudio = it
            prefs.streamAudio = it
        }
        TextButton(onClick = { streamFps = 24; prefs.streamFps = 24; streamAudio = "Automática"; prefs.streamAudio = streamAudio }) { Text(stringResource(R.string.restablecer_valores)) }
    }
    Section(stringResource(R.string.acerca_de))
    Text("MetaLima Ai · 0.4.0", color = Muted)
}

@Composable internal fun Choice(label: String, selected: String, items: List<String>,
    display: @Composable (String) -> String = { it }, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Muted)
        Box {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { expanded = true }) {
                Text(display(selected), Modifier.weight(1f)); Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.ver_opciones))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                items.forEach { item -> DropdownMenuItem(text = { Text(display(item)) }, onClick = { expanded = false; select(item) }) }
            }
        }
    }
}

@Composable internal fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, Modifier.weight(1f)); Switch(checked = checked, enabled = enabled, onCheckedChange = change)
    }
}
