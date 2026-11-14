package com.lima.metalima

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID

internal data class StudioNote(val id: String, val text: String, val time: Long, val hasPhoto: Boolean)

internal class NoteStore(context: Context) {
    private val directory = File(context.filesDir, "notes")
    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
    var notes by mutableStateOf(read())
        private set

    suspend fun save(text: String, jpeg: String? = null) {
        require(text.isNotBlank()) { "Escribe o dicta una nota primero." }
        val id = UUID.randomUUID().toString()
        if (jpeg != null) {
            withContext(Dispatchers.IO) {
                check(directory.isDirectory || directory.mkdirs()) { "No se pudo abrir la carpeta de notas." }
                photo(id).writeBytes(Base64.decode(jpeg, Base64.NO_WRAP))
            }
        }
        write(listOf(StudioNote(id, text.trim().take(4000), System.currentTimeMillis(), jpeg != null)) + notes)
    }

    fun delete(note: StudioNote) {
        write(notes.filterNot { it.id == note.id })
        if (note.hasPhoto) photo(note.id).delete()
    }

    fun photo(id: String) = File(directory, "$id.jpg")

    private fun read(): List<StudioNote> = runCatching {
        val array = JSONArray(prefs.getString("entries", "[]"))
        List(array.length()) { index ->
            val entry = array.getJSONObject(index)
            StudioNote(entry.getString("id"), entry.getString("text"), entry.getLong("time"), entry.getBoolean("photo"))
        }
    }.getOrDefault(emptyList())

    private fun write(updated: List<StudioNote>) {
        val array = JSONArray()
        updated.forEach { array.put(JSONObject().put("id", it.id).put("text", it.text).put("time", it.time).put("photo", it.hasPhoto)) }
        prefs.edit().putString("entries", array.toString()).apply()
        notes = updated
    }
}

@Composable internal fun NotesScreen(store: NoteStore, draft: String, edit: (String) -> Unit, dictate: () -> Unit,
    attach: () -> Unit, attachment: String?, removePhoto: () -> Unit, saving: Boolean, save: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Heading("Notas", "Guarda ideas, dictados y fotos de tus gafas.")
    Block {
        OutlinedTextField(draft, edit, modifier = Modifier.fillMaxWidth(), label = { Text("Nueva nota") }, maxLines = 5)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = dictate, enabled = !saving) { Text("Dictar") }
            OutlinedButton(onClick = attach, enabled = !saving) { Text("Adjuntar foto") }
        }
        if (attachment != null) {
            val bitmap by produceState<android.graphics.Bitmap?>(null, attachment) {
                value = withContext(Dispatchers.Default) {
                    val bytes = Base64.decode(attachment, Base64.NO_WRAP)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            bitmap?.let { Image(it.asImageBitmap(), "Foto adjunta", Modifier.fillMaxWidth().height(140.dp), contentScale = ContentScale.Fit) }
            TextButton(onClick = removePhoto, enabled = !saving) { Text("Quitar foto") }
        }
        Button(enabled = draft.isNotBlank() && !saving, onClick = save) { Text("Guardar nota") }
    }
    if (store.notes.isNotEmpty()) {
        OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Buscar notas") })
    }
    store.notes.filter { it.text.contains(query.trim(), ignoreCase = true) }.take(30).forEach { note ->
        key(note.id) {
            var expanded by remember { mutableStateOf(false) }
            var confirm by remember { mutableStateOf(false) }
            Block {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.time)),
                        modifier = Modifier.weight(1f), color = Muted)
                    IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.DeleteOutline, "Eliminar nota") }
                }
                Text(note.text)
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(note.text)) }) { Text("Copiar nota") }
                if (note.hasPhoto) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Ocultar foto" else "Ver foto adjunta") }
                if (expanded) {
                    val image by produceState<android.graphics.Bitmap?>(null, note.id) {
                        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(store.photo(note.id).absolutePath) }
                    }
                    image?.let { Image(it.asImageBitmap(), "Foto adjunta", Modifier.fillMaxWidth().heightIn(max = 230.dp), contentScale = ContentScale.Fit) }
                }
            }
            if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("¿Eliminar esta nota?") },
                confirmButton = { TextButton(onClick = { store.delete(note); confirm = false }) { Text("Eliminar") } },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancelar") } })
        }
    }
}
