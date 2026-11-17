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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable internal fun StudioSettings(activity: MainActivity, glasses: WearablesViewModel, ai: AssistantViewModel, prefs: StudioPrefs, speaks: Boolean, setSpeaks: (Boolean) -> Unit,
    language: AppLanguage, setLanguage: (AppLanguage) -> Unit, phrase: String, setPhrase: (String) -> Unit, notify: (String) -> Unit,
    skin: Skin, setSkin: (Skin) -> Unit, streamFormat: VideoFormat, setStreamFormat: (VideoFormat) -> Unit) {
    val device by glasses.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var platform by remember { mutableStateOf(prefs.platform) }
    var url by remember(platform) { mutableStateOf(prefs.url(platform)) }
    var streamKey by remember(platform) { mutableStateOf("") }
    var quality by remember { mutableStateOf(prefs.quality) }
    var fps by remember { mutableIntStateOf(prefs.fps) }
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
        if (device.isRegistered) scope.launch {
            HandsFreeService.disable(activity)
            HandsFreeService.state.first { !it.ownsMicrophone }
            runCatching { glasses.startUnregistration(activity) }.onFailure { notify(activity.getString(R.string.no_se_pudo_abrir_meta_2)) }
        }
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
        VideoFormatControls(streamFormat, true, setStreamFormat)
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
    AssistantSettings(activity, ai, notify)
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
    Text(stringResource(R.string.app_name) + " · 0.5.0", color = Muted)
    TextButton(onClick = { openGithubRelease(activity) }) { Text(stringResource(R.string.update_releases)) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AssistantSettings(
    activity: MainActivity, ai: AssistantViewModel, notify: (String) -> Unit, initialProvider: Provider? = null
) {
    val state by ai.state.collectAsStateWithLifecycle()
    var provider by rememberSaveable(initialProvider) { mutableStateOf(initialProvider ?: state.provider) }
    var model by remember(provider) { mutableStateOf(ai.configuredModel(provider)) }
    var key by remember(provider) { mutableStateOf("") }
    var workspace by remember(provider) { mutableStateOf(ai.workspace) }
    var china by remember(provider) { mutableStateOf(ai.china) }
    var museEndpoint by remember(provider) { mutableStateOf(ai.museUrl) }
    val hasKey = provider in state.configuredProviders
    Block {
        Text(stringResource(R.string.plataforma_de_ia), color = Muted)
        AssistantChoice(provider, state, enabled = !state.busy) { provider = it }
        if (provider == Provider.MUSE) {
            Text(stringResource(R.string.muse_api_summary), color = Muted)
            TextButton(onClick = { openMuseWebsite(activity, notify) }) { Text(stringResource(R.string.muse_open_web)) }
            OutlinedTextField(museEndpoint, { museEndpoint = it }, Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.muse_api_url)) }, singleLine = true)
        }
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.modelo_de, provider.label)) }, singleLine = true)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.clave_api_de, provider.label)) },
            placeholder = { Text(stringResource(if (hasKey) R.string.clave_guardada else R.string.pega_tu_clave)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password))
        if (provider == Provider.QWEN) {
            OutlinedTextField(workspace, { workspace = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.id_del_espacio_de_alibaba)) })
            val regionSingapore = stringResource(R.string.region_singapore)
            val regionChina = stringResource(R.string.china_pekin)
            Choice(stringResource(R.string.region_del_espacio), if (china) regionChina else regionSingapore,
                listOf(regionSingapore, regionChina)) { china = it == regionChina }
        }
        val dirty = key.isNotBlank() || model != ai.configuredModel(provider) ||
            provider == Provider.MUSE && museEndpoint != ai.museUrl ||
            provider == Provider.QWEN && (workspace != ai.workspace || china != ai.china)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Button(enabled = !state.busy, onClick = {
                if (ai.configure(provider, key, model, workspace, china, museEndpoint, activate = initialProvider == null)) {
                    key = ""
                    model = ai.configuredModel(provider)
                    museEndpoint = ai.museUrl
                    workspace = ai.workspace
                }
            }) { Text(stringResource(R.string.guardar)) }
            OutlinedButton(enabled = !state.busy && hasKey && !dirty, onClick = { ai.checkConnection(provider) }) {
                Text(stringResource(R.string.probar_conexion))
            }
        }
        if (hasKey) TextButton(enabled = !state.busy, onClick = { ai.forgetKey(provider); key = "" }) {
            Text(stringResource(R.string.eliminar_clave_guardada))
        }
    }
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
