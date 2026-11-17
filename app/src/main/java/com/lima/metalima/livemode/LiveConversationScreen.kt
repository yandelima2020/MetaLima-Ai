package com.lima.metalima.livemode

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lima.metalima.*
import com.lima.metalima.R

@Composable internal fun LiveConversationScreen(
    state: LiveConversationState, selected: LiveModel, usePhone: Boolean, hasKey: Boolean, blocked: Boolean,
    select: (LiveModel) -> Unit, choosePhone: (Boolean) -> Unit, configure: () -> Unit, start: () -> Unit, stop: () -> Unit,
    translation: Translation? = null, chooseLanguages: (Translation) -> Unit = {}, language: AppLanguage,
    enableVision: (() -> Unit)? = null, takeFrame: (() -> Unit)? = null, visionBusy: Boolean = false,
    visualPreview: (@Composable () -> Unit)? = null
) {
    val scroll = rememberLazyListState()
    val recent = state.turns
    val hasPartial = state.question.isNotBlank() || state.answer.isNotBlank()
    LaunchedEffect(recent.lastOrNull(), hasPartial) {
        val last = recent.lastIndex + if (hasPartial) 1 else 0
        if (last >= 0) scroll.scrollToItem(last)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 12.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (translation == null) stringResource(R.string.conversacion_en_directo) else stringResource(R.string.traduccion_por_voz),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (!state.active) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) {
                    AssistantIcon(selected.provider, 19)
                    Spacer(Modifier.width(8.dp))
                    Text(selected.displayLabel())
                }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 380.dp)) {
                    listOf(Provider.GEMINI, Provider.OPENAI).forEach { provider ->
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistantIcon(provider, 19)
                            Text(provider.label, color = Lime, style = MaterialTheme.typography.labelMedium)
                        }
                        LiveModel.entries.filter { it.provider == provider }.forEach { model ->
                            DropdownMenuItem(text = { Text(model.displayLabel()) }, onClick = { select(model); expanded = false })
                        }
                    }
                }
            }
            SwitchRow(stringResource(R.string.usar_microfono_y_altavoz_del), usePhone) { choosePhone(it) }
            if (translation != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Choice(stringResource(R.string.idioma_2), translation.from, translationLanguages.filterNot { it == translation.to }, display = { translationLabel(it) }) {
                        chooseLanguages(translation.copy(from = it))
                    } }
                    Column(Modifier.weight(1f)) { Choice(stringResource(R.string.idioma), translation.to, translationLanguages.filterNot { it == translation.from }, display = { translationLabel(it) }) {
                        chooseLanguages(translation.copy(to = it))
                    } }
                }
            }
            if (!hasKey) TextButton(onClick = configure) { Text(stringResource(R.string.configurar_clave_de, selected.provider.label)) }
        }
        val status = when (state.phase) {
            LivePhase.IDLE -> stringResource(R.string.listo_para_iniciar)
            LivePhase.CONNECTING -> stringResource(R.string.conectando)
            LivePhase.LISTENING -> if (state.speaking) stringResource(R.string.respondiendo_puedes_interrumpir) else stringResource(R.string.escuchando)
            LivePhase.RECONNECTING -> stringResource(R.string.recuperando_conexion)
            LivePhase.STOPPING -> stringResource(R.string.cerrando_sesion)
        }
        if (state.active) Surface(color = SurfaceTone, shape = Panel, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status, color = Lime, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(selected.displayLabel(), state.device).joinToString(" · "), color = Muted)
                if (translation != null) Text("${translationLabel(translation.from)} ↔ ${translationLabel(translation.to)}", color = Muted)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (blocked && !state.active) Text(stringResource(R.string.espera_a_que_termine_la), color = Muted)
        if (enableVision != null && (translation == null || state.active)) {
            visualPreview?.invoke()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = enableVision, enabled = !visionBusy) {
                    Icon(Icons.Outlined.Visibility, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (visualPreview != null) stringResource(R.string.cerrar_vision) else if (visionBusy) stringResource(R.string.abriendo_camara) else stringResource(R.string.activar_vision))
                }
                if (takeFrame != null) OutlinedButton(onClick = takeFrame, enabled = !visionBusy) { Text(stringResource(R.string.enviar_imagen)) }
            }
            if (visualPreview != null) Text(
                if (state.active) stringResource(R.string.puedes_decir_que_estoy_viendo)
                else stringResource(R.string.con_el_visor_listo_inicia),
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (recent.isEmpty() && state.question.isBlank() && state.answer.isBlank()) item {
                Text(if (state.active) stringResource(R.string.habla_cuando_aparezca_escuchando) else stringResource(R.string.idioma_de_conversacion_pulsa_iniciar, language.label),
                    color = Muted, modifier = Modifier.padding(vertical = 12.dp))
            }
            items(recent) {
                Transcript(it.user, it.answer)
            }
            if (hasPartial) {
                item { Transcript(state.question, state.answer) }
            }
        }
        Button(onClick = if (state.active) stop else start,
            enabled = if (state.active) state.phase != LivePhase.STOPPING else hasKey && !blocked,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(if (state.active) Icons.Outlined.Stop else Icons.Outlined.Mic, null)
            Spacer(Modifier.width(8.dp))
            Text(if (state.active) stringResource(R.string.terminar_conversacion) else stringResource(R.string.iniciar_conversacion))
        }
    }
}

@Composable internal fun LiveModel.displayLabel(): String {
    val qualifier = when {
        this == LiveModel.GEMINI_25 -> R.string.model_native_audio
        label.endsWith(" · Anterior") -> R.string.model_legacy
        else -> return label
    }
    return label.substringBeforeLast(" · ") + " · " + stringResource(qualifier)
}

@Composable private fun Transcript(question: String, answer: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (question.isNotBlank()) Bubble(stringResource(R.string.tu), question, Lime.copy(alpha = .15f), Modifier.align(Alignment.End))
        if (answer.isNotBlank()) Bubble(stringResource(R.string.ai_speaker), answer, SurfaceTone, Modifier.align(Alignment.Start))
    }
}

@Composable private fun Bubble(who: String, text: String, color: Color, modifier: Modifier) {
    Column(modifier.fillMaxWidth(.92f).background(color, Panel).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(who, color = Lime, style = MaterialTheme.typography.labelMedium)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
