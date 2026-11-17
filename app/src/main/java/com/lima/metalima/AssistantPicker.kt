package com.lima.metalima

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable internal fun AssistantIcon(provider: Provider, size: Int = 23) {
    val icon = when (provider) {
        Provider.OPENAI -> R.drawable.assistant_chatgpt
        Provider.GEMINI -> R.drawable.assistant_gemini
        Provider.MUSE -> R.drawable.assistant_muse
        Provider.GROK -> R.drawable.assistant_grok
        Provider.QWEN -> R.drawable.assistant_qwen
    }
    Box(Modifier.size((size + 10).dp).background(Color(0xFFF8FAFC), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center) {
        Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(size.dp))
    }
}

@Composable internal fun AssistantChoice(
    provider: Provider, state: AssistantState, enabled: Boolean = true, onSelect: (Provider) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp)) {
            AssistantIcon(provider, 19)
            Spacer(Modifier.width(10.dp))
            Text(provider.label, Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.ver_opciones))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            Provider.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Column {
                        Text(option.label)
                        Text(assistantStatus(option, state), color = Muted, style = MaterialTheme.typography.labelSmall)
                    } },
                    leadingIcon = { AssistantIcon(option, 19) },
                    onClick = { expanded = false; onSelect(option) }
                )
            }
        }
    }
}

@Composable internal fun AssistantHub(
    state: AssistantState, openChat: () -> Unit, openGemini: () -> Unit, openMuse: () -> Unit
) {
    Block {
        Text(stringResource(R.string.inteligencia_artificial), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistantShortcut(Modifier.weight(1f), Provider.OPENAI, Provider.OPENAI.label,
                assistantStatus(Provider.OPENAI, state), !state.busy, openChat)
            val geminiStatus = stringResource(if (Provider.GEMINI in state.configuredProviders)
                R.string.clave_guardada else R.string.assistant_needs_key)
            AssistantShortcut(Modifier.weight(1f), Provider.GEMINI, "Gemini Live", geminiStatus, true, openGemini)
            AssistantShortcut(Modifier.weight(1f), Provider.MUSE, Provider.MUSE.label,
                assistantStatus(Provider.MUSE, state), !state.busy, openMuse)
        }
    }
}

@Composable private fun AssistantShortcut(
    modifier: Modifier, provider: Provider, label: String, status: String, enabled: Boolean, open: () -> Unit
) {
    Surface(onClick = open, enabled = enabled, modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 7.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistantIcon(provider)
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(status, color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable private fun assistantStatus(provider: Provider, state: AssistantState) = stringResource(when {
    provider in state.verifiedProviders -> R.string.assistant_verified
    provider in state.configuredProviders -> R.string.clave_guardada
    provider == Provider.MUSE -> R.string.muse_web_mode
    else -> R.string.assistant_needs_key
})

internal fun openMuseWebsite(activity: MainActivity, notify: (String) -> Unit) {
    runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://muse.ai/"))) }
        .onFailure { notify(activity.getString(R.string.no_se_pudo_abrir_el)) }
}
