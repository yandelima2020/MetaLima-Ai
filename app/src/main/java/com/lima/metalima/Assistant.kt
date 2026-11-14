package com.lima.metalima

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Provider(val label: String, val model: String) {
    OPENAI("OpenAI", "gpt-4.1-mini"),
    GEMINI("Google Gemini", "gemini-2.5-flash"),
    GROK("Grok", "grok-4-1-fast-non-reasoning"),
    QWEN("Alibaba Qwen", "qwen3-vl-flash");

    fun url(workspace: String, china: Boolean): String = when (this) {
        OPENAI -> "https://api.openai.com/v1/chat/completions"
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        GROK -> "https://api.x.ai/v1/chat/completions"
        QWEN -> {
            require(workspace.matches(Regex("[A-Za-z0-9-]{1,100}"))) { "Introduce un ID de espacio de trabajo de Alibaba válido." }
            if (china) "https://$workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
            else "https://$workspace.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
        }
    }
}

data class Turn(val user: String, val answer: String, val time: Long, val session: String)
data class AssistantState(
    val provider: Provider = Provider.OPENAI,
    val hasKey: Boolean = false,
    val turns: List<Turn> = emptyList(),
    val session: String = java.util.UUID.randomUUID().toString(),
    val busy: Boolean = false,
    val notice: String? = null,
    val hasGeminiKey: Boolean = false,
    val hasOpenAiKey: Boolean = false
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {
    private val store = application.getSharedPreferences("studio", Context.MODE_PRIVATE)
    private val secrets = Vault(application)
    private val selected = runCatching { Provider.valueOf(store.getString("provider", "OPENAI")!!) }.getOrDefault(Provider.OPENAI)
    private val current = MutableStateFlow(AssistantState(selected, secrets.exists(selected.name), loadTurns(),
        hasGeminiKey = secrets.exists(Provider.GEMINI.name), hasOpenAiKey = secrets.exists(Provider.OPENAI.name)))
    val state = current.asStateFlow()
    fun configuredModel(p: Provider): String = store.getString("model-${p.name}", p.model) ?: p.model
    val workspace: String get() = store.getString("qwen-workspace", "") ?: ""
    val china: Boolean get() = store.getBoolean("qwen-china", false)

    fun configure(provider: Provider, token: String, model: String, workspace: String, china: Boolean) {
        if (current.value.busy) return
        runCatching {
            if (token.isNotBlank()) secrets.write(provider.name, token.trim())
            store.edit().putString("provider", provider.name).putString("model-${provider.name}", model.trim().ifEmpty { provider.model })
                .putString("qwen-workspace", workspace.trim()).putBoolean("qwen-china", china).apply()
            current.update { it.copy(provider = provider, hasKey = secrets.exists(provider.name),
                hasGeminiKey = secrets.exists(Provider.GEMINI.name), hasOpenAiKey = secrets.exists(Provider.OPENAI.name),
                notice = "Configuración guardada.") }
        }.onFailure { current.update { it.copy(notice = "No se pudo guardar la configuración de forma segura.") } }
    }

    fun forgetKey(provider: Provider) {
        if (current.value.busy) return
        secrets.delete(provider.name)
        current.update { it.copy(hasKey = if (it.provider == provider) false else it.hasKey,
            hasGeminiKey = secrets.exists(Provider.GEMINI.name), hasOpenAiKey = secrets.exists(Provider.OPENAI.name),
            notice = "Clave eliminada.") }
    }

    internal fun liveKey(provider: Provider): String = secrets.read(provider.name)
        ?: error("Guarda tu clave de ${provider.label} en Ajustes para iniciar la conversación en directo.")

    internal fun saveKey(provider: Provider, key: String) {
        runCatching { secrets.write(provider.name, key.trim()) }.onSuccess {
            current.update { it.copy(hasKey = secrets.exists(it.provider.name), hasGeminiKey = secrets.exists(Provider.GEMINI.name),
                hasOpenAiKey = secrets.exists(Provider.OPENAI.name), notice = "Clave guardada para ${provider.label}.") }
        }.onFailure { current.update { it.copy(notice = "No se pudo guardar la clave de forma segura.") } }
    }

    internal fun saveLiveTurn(turn: Turn) {
        val turns = (current.value.turns + turn).takeLast(100)
        persistTurns(turns)
        current.update { it.copy(turns = turns) }
    }

    fun newSession() {
        if (!current.value.busy) current.update { it.copy(session = java.util.UUID.randomUUID().toString()) }
    }

    fun selectSession(id: String) {
        if (!current.value.busy) current.update { it.copy(session = id) }
    }

    fun deleteSessions(ids: Set<String>) {
        if (current.value.busy) return
        val remaining = current.value.turns.filterNot { it.session in ids }
        persistTurns(remaining)
        current.update { it.copy(turns = remaining, session = if (it.session in ids) java.util.UUID.randomUUID().toString() else it.session) }
    }

    fun clearHistory() {
        if (current.value.busy) return
        store.edit().remove("history").apply()
        current.update { it.copy(turns = emptyList(), notice = null) }
    }

    fun checkConnection() = request("Responde únicamente con la palabra OK.", check = true)
    internal fun ask(question: String, image: Bitmap?, continuation: Boolean, language: String, done: (String) -> Unit) =
        request(question, image, continuation, language = language, done = done)

    internal fun askAudio(wav: ByteArray, image: Bitmap?, language: AppLanguage, done: (String) -> Unit) =
        request("", image, continuation = true, wav = wav, language = language.promptName, done = done)

    private fun request(question: String, image: Bitmap? = null, continuation: Boolean = false, check: Boolean = false,
        wav: ByteArray? = null, language: String = AppLanguage.SPANISH.promptName, done: (String) -> Unit = {}) {
        val from = current.value
        if (from.busy || (wav == null && question.isBlank())) return
        current.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                val (heard, answer) = withContext(Dispatchers.IO) {
                    val key = secrets.read(from.provider.name) ?: error("Configura una clave de ${from.provider.label} en Ajustes.")
                    val model = configuredModel(from.provider)
                    val history = if (continuation) from.turns.filter { it.session == from.session }.takeLast(8) else emptyList()
                    if (wav == null) question.trim() to requestChat(from.provider, key, model, workspace, china, question.trim(), image, history, language)
                    else {
                        require(from.provider == Provider.GEMINI) { "Selecciona Google Gemini para hablar con las gafas." }
                        requestGeminiAudio(key, model, wav, image, history, language)
                    }
                }
                if (check) current.update { it.copy(notice = "Conexión verificada con ${from.provider.label}.") }
                else {
                    saveLiveTurn(Turn(heard, answer, System.currentTimeMillis(), from.session))
                    done(answer)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalStateException) {
                current.update { it.copy(notice = exception.message ?: "No se pudo completar la consulta.") }
            } catch (exception: IllegalArgumentException) {
                current.update { it.copy(notice = exception.message ?: "Revisa la configuración del proveedor.") }
            } catch (_: Exception) {
                current.update { it.copy(notice = "No se pudo contactar con el proveedor. Comprueba la conexión y vuelve a intentarlo.") }
            } finally {
                current.update { it.copy(busy = false) }
            }
        }
    }

    private fun loadTurns(): List<Turn> = runCatching {
        val data = JSONArray(store.getString("history", "[]"))
        List(data.length()) { index ->
            val item = data.getJSONObject(index)
            Turn(item.getString("user"), item.getString("answer"), item.getLong("time"), item.optString("session", "anteriores"))
        }
    }.getOrDefault(emptyList())

    private fun persistTurns(turns: List<Turn>) {
        val data = JSONArray()
        turns.forEach { data.put(JSONObject().put("user", it.user).put("answer", it.answer).put("time", it.time).put("session", it.session)) }
        store.edit().putString("history", data.toString()).apply()
    }
}

private fun requestChat(provider: Provider, key: String, model: String, workspace: String, china: Boolean, question: String, image: Bitmap?, history: List<Turn>, language: String): String {
    val messages = JSONArray().put(message("system", "Responde en $language con naturalidad y precisión. Usa el idioma de destino si la consulta pide traducir. Da respuestas concisas. Si una imagen no aporta evidencia suficiente, dilo."))
    history.forEach { messages.put(message("user", it.user)).put(message("assistant", it.answer)) }
    val content = if (image == null) question else {
        JSONArray().put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + jpegBase64(image))))
    }
    messages.put(message("user", content))
    val payload = JSONObject().put("model", model).put("messages", messages)
    val response = postJson(provider, provider.url(workspace, china), "Authorization", "Bearer $key", payload)
    val answer = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
    return answer.ifEmpty { error("${provider.label} no devolvió una respuesta de texto.") }
}

private fun requestGeminiAudio(key: String, model: String, wav: ByteArray, image: Bitmap?, history: List<Turn>, language: String): Pair<String, String> {
    val id = model.removePrefix("models/")
    require(id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Revisa el identificador del modelo Gemini en Ajustes." }
    fun text(value: String) = JSONObject().put("text", value)
    fun content(role: String, parts: JSONArray) = JSONObject().put("role", role).put("parts", parts)
    fun media(mime: String, data: String) = JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data))
    val system = "Transcribe la pregunta en el idioma original y contéstala de forma breve en $language. " +
        "Usa la imagen si se incluye. Si no hay una pregunta inteligible, deja ambos campos vacíos. No inventes palabras."
    val contents = JSONArray()
    history.forEach {
        contents.put(content("user", JSONArray().put(text(it.user))))
        contents.put(content("model", JSONArray().put(text(it.answer))))
    }
    val parts = JSONArray().put(text("Escucha mi pregunta y respóndeme."))
        .put(media("audio/wav", Base64.encodeToString(wav, Base64.NO_WRAP)))
    if (image != null) parts.put(media("image/jpeg", jpegBase64(image)))
    contents.put(content("user", parts))
    val properties = JSONObject().put("transcription", JSONObject().put("type", "STRING"))
        .put("answer", JSONObject().put("type", "STRING"))
    val schema = JSONObject().put("type", "OBJECT").put("properties", properties)
        .put("required", JSONArray().put("transcription").put("answer"))
    val payload = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(text(system))))
        .put("contents", contents)
        .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema))
    val response = postJson(Provider.GEMINI, "https://generativelanguage.googleapis.com/v1beta/models/$id:generateContent",
        "x-goog-api-key", key, payload)
    val returned = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
    val result = buildString {
        if (returned != null) for (i in 0 until returned.length()) {
            val part = returned.optJSONObject(i) ?: continue
            if (!part.optBoolean("thought")) append(part.optString("text"))
        }
    }
    val reply = runCatching { JSONObject(result) }.getOrElse {
        error("Gemini no devolvió una respuesta de voz interpretable. Revisa que el modelo admita audio.")
    }
    val heard = reply.optString("transcription").trim()
    val answer = reply.optString("answer").trim()
    check(heard.isNotEmpty()) { "Gemini no entendió la pregunta. Vuelve a hablar más cerca del micrófono de las gafas." }
    check(answer.isNotEmpty()) { "Gemini reconoció tu voz, pero no devolvió una respuesta." }
    return heard to answer
}

internal fun jpegBase64(image: Bitmap, maxSide: Int = 1280): String {
    val data = ByteArrayOutputStream()
    val factor = maxSide.toFloat() / maxOf(image.width, image.height)
    val resized = if (factor < 1) Bitmap.createScaledBitmap(image, maxOf(1, (factor * image.width).toInt()), maxOf(1, (factor * image.height).toInt()), true) else image
    try { resized.compress(Bitmap.CompressFormat.JPEG, 82, data) } finally { if (resized !== image) resized.recycle() }
    return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
}

private fun postJson(provider: Provider, url: String, authHeader: String, auth: String, payload: JSONObject): JSONObject {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 45000
        connection.doOutput = true
        connection.setRequestProperty(authHeader, auth)
        connection.setRequestProperty("Content-Type", "application/json")
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        connection.setFixedLengthStreamingMode(bytes.size)
        connection.outputStream.use { it.write(bytes) }
        val status = connection.responseCode
        check(status in 200..299) {
            when (status) {
                400, 404 -> "${provider.label} rechazó la solicitud ($status). Revisa el modelo y si admite la función solicitada."
                401, 403 -> "${provider.label} rechazó la clave o los permisos ($status). Revisa Ajustes."
                402, 429 -> "${provider.label} indicó un límite de uso o un problema de saldo ($status)."
                else -> "${provider.label} devolvió un error ($status). Inténtalo de nuevo."
            }
        }
        return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    } finally { connection.disconnect() }
}

private fun message(role: String, content: Any) = JSONObject().put("role", role).put("content", content)

internal class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    fun exists(name: String) = prefs.contains(name)
    fun delete(name: String) { prefs.edit().remove(name).apply() }
    fun write(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
    }
    fun read(name: String): String? {
        val encoded = prefs.getString(name, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }.getOrElse { error("No se pudo recuperar la clave guardada. Vuelve a introducirla en Ajustes.") }
    }
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("metalima-vault", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("metalima-vault", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
