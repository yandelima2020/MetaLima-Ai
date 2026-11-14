package com.lima.metalima.livemode

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
        Text(if (translation == null) "Conversación en directo" else "Traducción por voz",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        if (!state.active) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(selected.label) }
                DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 380.dp)) {
                    listOf("Google Gemini", "OpenAI").forEach { provider ->
                        Text(provider, color = Lime, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(12.dp))
                        LiveModel.entries.filter { it.provider.label == provider }.forEach { model ->
                            DropdownMenuItem(text = { Text(model.label) }, onClick = { select(model); expanded = false })
                        }
                    }
                }
            }
            SwitchRow("Usar micrófono y altavoz del móvil", usePhone) { choosePhone(it) }
            if (translation != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) { Choice("Idioma 1", translation.from, translationLanguages.filterNot { it == translation.to }) {
                        chooseLanguages(translation.copy(from = it))
                    } }
                    Column(Modifier.weight(1f)) { Choice("Idioma 2", translation.to, translationLanguages.filterNot { it == translation.from }) {
                        chooseLanguages(translation.copy(to = it))
                    } }
                }
            }
            if (!hasKey) TextButton(onClick = configure) { Text("Configurar clave de ${selected.provider.label}") }
        }
        val status = when (state.phase) {
            LivePhase.IDLE -> "Listo para iniciar"
            LivePhase.CONNECTING -> "Conectando…"
            LivePhase.LISTENING -> if (state.speaking) "Respondiendo · Puedes interrumpir" else "Escuchando"
            LivePhase.RECONNECTING -> "Recuperando conexión…"
            LivePhase.STOPPING -> "Cerrando sesión…"
        }
        if (state.active) Surface(color = SurfaceTone, shape = Panel, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(status, color = Lime, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(selected.label, state.device).joinToString(" · "), color = Muted)
                if (translation != null) Text("${translation.from} ↔ ${translation.to}", color = Muted)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (blocked && !state.active) Text("Espera a que termine la consulta o detén la grabación o emisión activa.", color = Muted)
        if (enableVision != null && (translation == null || state.active)) {
            visualPreview?.invoke()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = enableVision, enabled = !visionBusy) {
                    Icon(Icons.Outlined.Visibility, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (visualPreview != null) "Cerrar visión" else if (visionBusy) "Abriendo cámara…" else "Activar visión")
                }
                if (takeFrame != null) OutlinedButton(onClick = takeFrame, enabled = !visionBusy) { Text("Enviar imagen") }
            }
            if (visualPreview != null) Text(
                if (state.active) "Puedes decir «¿qué estoy viendo?» o enviar una imagen ahora."
                else "Con el visor listo, inicia la conversación para usar la imagen.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (recent.isEmpty() && state.question.isBlank() && state.answer.isBlank()) item {
                Text(if (state.active) "Habla cuando aparezca «Escuchando»." else "Idioma de conversación: ${language.label}. Pulsa Iniciar para hablar.",
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
            Text(if (state.active) "Terminar conversación" else "Iniciar conversación")
        }
    }
}

@Composable private fun Transcript(question: String, answer: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (question.isNotBlank()) Bubble("Tú", question, Lime.copy(alpha = .15f), Modifier.align(Alignment.End))
        if (answer.isNotBlank()) Bubble("IA", answer, SurfaceTone, Modifier.align(Alignment.Start))
    }
}

@Composable private fun Bubble(who: String, text: String, color: Color, modifier: Modifier) {
    Column(modifier.fillMaxWidth(.92f).background(color, Panel).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Text(who, color = Lime, style = MaterialTheme.typography.labelMedium)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}
