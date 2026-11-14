package com.lima.metalima

import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CellTower
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable internal fun PlatformIcon(platform: String) {
    val logo = when (platform) {
        "YouTube" -> R.drawable.platform_youtube
        "Twitch" -> R.drawable.platform_twitch
        "TikTok" -> R.drawable.platform_tiktok
        "Kick" -> R.drawable.platform_kick
        "Facebook Live" -> R.drawable.platform_facebook
        "Instagram Live" -> R.drawable.platform_instagram
        else -> null
    }
    Box(Modifier.size(32.dp).background(Color(0xFF141820), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        if (logo != null) Image(painterResource(logo), null, Modifier.size(19.dp))
        else Icon(
            if (platform == "OBS / Servidor RTMP") Icons.Outlined.Computer else Icons.Outlined.CellTower,
            null, Modifier.size(19.dp), tint = Color.White
        )
    }
}

@Composable internal fun PlatformChoice(selected: String, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(stringResource(R.string.plataforma_de_emision), color = Muted)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                PlatformIcon(selected)
                Text(platformLabel(selected), Modifier.weight(1f))
                Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.ver_plataformas))
            }
            DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 400.dp)) {
                destinations.forEach { platform ->
                    DropdownMenuItem(
                        text = { Text(platformLabel(platform)) },
                        leadingIcon = { PlatformIcon(platform) },
                        onClick = { expanded = false; select(platform) }
                    )
                }
            }
        }
    }
}

@Composable internal fun platformLabel(platform: String): String = when (platform) {
    "OBS / Servidor RTMP" -> stringResource(R.string.destination_obs)
    "RTMP personalizado" -> stringResource(R.string.destination_custom)
    else -> platform
}
