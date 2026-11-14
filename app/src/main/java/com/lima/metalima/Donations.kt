package com.lima.metalima

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val donationUrl = Uri.parse("https://www.paypal.com/cgi-bin/webscr").buildUpon()
    .appendQueryParameter("cmd", "_donations")
    .appendQueryParameter("business", "carloslimaviega@outlook.com")
    .appendQueryParameter("item_name", "MetaLima Ai")
    .appendQueryParameter("currency_code", "EUR")
    .build()

@Composable internal fun Donations(activity: MainActivity, notify: (String) -> Unit) {
    Heading("Donaciones")
    Block {
        Text("No quiero llenarte de publicidad la app. Quiero que puedas sacar el máximo provecho. Si consideras que vale la pena el esfuerzo, dona lo que tú quieras.")
        Button(modifier = Modifier.fillMaxWidth(), onClick = {
            try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, donationUrl))
            } catch (_: ActivityNotFoundException) {
                notify("Android necesita un navegador para abrir PayPal.")
            }
        }) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Donar con PayPal")
                Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
