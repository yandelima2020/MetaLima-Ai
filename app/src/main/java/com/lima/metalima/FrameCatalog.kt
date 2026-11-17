package com.lima.metalima

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.json.JSONArray

internal data class FrameVariant(val id: String, val model: String, val frame: String, val lenses: String, val photo: Int)

internal fun frameCatalog(context: Context): List<FrameVariant> {
    val resources = context.resources
    val data = resources.openRawResource(R.raw.frame_catalog).bufferedReader().use { JSONArray(it.readText()) }
    return (0 until data.length()).mapNotNull { index ->
        val row = data.getJSONObject(index)
        val id = row.getString("asset")
        val photo = resources.getIdentifier(id, "drawable", context.packageName)
        if (photo == 0) null else FrameVariant(id, row.getString("model"), row.getString("frame"), row.getString("lenses"), photo)
    }
}

@Composable internal fun FrameCatalogDialog(
    variants: List<FrameVariant>, selected: FrameVariant?, deviceName: String, close: () -> Unit, select: (FrameVariant?) -> Unit
) {
    val groups = remember(variants) { variants.groupBy { it.model } }
    val models = groups.keys.toList()
    var model by remember(selected, deviceName) {
        mutableStateOf(selected?.model ?: models.firstOrNull { deviceName.contains(it, ignoreCase = true) }
            ?: "Wayfarer (Gen 2)".takeIf { it in groups } ?: models.firstOrNull().orEmpty())
    }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.frame_catalog)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(stringResource(R.string.frame_model), model, models) { model = it }
                key(model) {
                    LazyColumn(Modifier.heightIn(max = 315.dp)) {
                        items(groups[model].orEmpty(), key = { it.id }) { item ->
                            ListItem(
                                headlineContent = { Text(item.frame) },
                                supportingContent = { Text(item.lenses) },
                                leadingContent = {
                                    Image(painterResource(item.photo), null, Modifier.size(width = 65.dp, height = 43.dp)
                                        .clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                                },
                                trailingContent = { RadioButton(selected = item == selected, onClick = null) },
                                modifier = Modifier.clickable { select(item) }
                            )
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.cerrar)) } },
        dismissButton = {
            if (selected != null) TextButton(onClick = { select(null) }) { Text(stringResource(R.string.frame_not_set)) }
        })
}
