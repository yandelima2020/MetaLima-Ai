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
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

enum class Provider(val label: String, val model: String) {
    OPENAI("ChatGPT", "gpt-4.1-mini"),
    GEMINI("Google Gemini", "gemini-3.5-flash-lite"),
    GROK("Grok", "grok-4-1-fast-non-reasoning"),
    QWEN("Alibaba Qwen", "qwen3-vl-flash"),
    MUSE("Muse AI", "muse-spark-1.3");

    internal fun url(context: Context, workspace: String, china: Boolean, museUrl: String): String = when (this) {
        OPENAI -> "https://api.openai.com/v1/chat/completions"
        GEMINI -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        GROK -> "https://api.x.ai/v1/chat/completions"
        MUSE -> museUrl
        QWEN -> {
            require(workspace.matches(Regex("[A-Za-z0-9-]{1,100}"))) { context.interfaceString(R.string.introduce_un_id_de_espacio) }
            if (china) "https://$workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
            else "https://$workspace.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1/chat/completions"
        }
    }
}

private const val DEFAULT_MUSE_URL = "https://api.meta.ai/v1/chat/completions"

data class Turn(
    val user: String, val answer: String, val time: Long, val session: String,
    val provider: Provider? = null, val museEndpoint: String? = null
)
data class AssistantState(
    val provider: Provider = Provider.OPENAI,
    val configuredProviders: Set<Provider> = emptySet(),
    val turns: List<Turn> = emptyList(),
    val session: String = java.util.UUID.randomUUID().toString(),
    val busy: Boolean = false,
    val notice: String? = null,
    val verifiedProviders: Set<Provider> = emptySet()
) {
    val hasKey: Boolean get() = provider in configuredProviders
}

class AssistantViewModel(private val application: Application) : AndroidViewModel(application) {
    companion object {
        private var instance: AssistantViewModel? = null

        @Synchronized internal fun shared(application: Application): AssistantViewModel =
            instance ?: AssistantViewModel(application).also { instance = it }
    }
    private val store = application.getSharedPreferences("studio", Context.MODE_PRIVATE)
    private val secrets = Vault(application)
    private val selected = runCatching { Provider.valueOf(store.getString("provider", "OPENAI")!!) }.getOrDefault(Provider.OPENAI)
    private val current = MutableStateFlow(AssistantState(selected, configuredProviders(), loadTurns()))
    val state = current.asStateFlow()
    private fun configuredProviders() = Provider.entries.filterTo(mutableSetOf()) { secrets.exists(secretName(it)) }
    fun configuredModel(p: Provider): String = store.getString("model-${p.name}", p.model) ?: p.model
    val museUrl: String get() = store.getString("muse-url", DEFAULT_MUSE_URL) ?: DEFAULT_MUSE_URL
    val workspace: String get() = store.getString("qwen-workspace", "") ?: ""
    val china: Boolean get() = store.getBoolean("qwen-china", false)

    private fun secretName(provider: Provider, endpoint: String = museUrl): String {
        if (provider != Provider.MUSE) return provider.name
        val digest = MessageDigest.getInstance("SHA-256").digest(endpoint.toByteArray(Charsets.UTF_8))
        return "MUSE-" + Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    fun selectProvider(provider: Provider) {
        val previous = current.value
        if (previous.busy || provider == previous.provider) return
        store.edit().putString("provider", provider.name).apply()
        current.update { it.copy(provider = provider, session = java.util.UUID.randomUUID().toString(), notice = null) }
    }

    fun configure(provider: Provider, token: String, model: String, workspace: String, china: Boolean,
        museEndpoint: String, activate: Boolean): Boolean {
        if (current.value.busy) return false
        return runCatching {
            val endpoint = museEndpoint.trim().ifEmpty { DEFAULT_MUSE_URL }
            val existingSecret = secretName(provider)
            val updatedSecret = secretName(provider, endpoint)
            if (provider == Provider.MUSE) {
                val uri = runCatching { URI(endpoint) }.getOrNull()
                require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                    !uri.rawPath.isNullOrBlank() && uri.rawPath != "/") {
                    application.interfaceString(R.string.muse_invalid_url)
                }
                require(existingSecret == updatedSecret || token.isNotBlank() || !secrets.exists(existingSecret)) {
                    application.interfaceString(R.string.muse_new_endpoint_key)
                }
            }
            if (token.isNotBlank()) secrets.write(updatedSecret, token.trim())
            store.edit().putString("model-${provider.name}", model.trim().ifEmpty { provider.model }).apply {
                if (provider == Provider.QWEN) putString("qwen-workspace", workspace.trim()).putBoolean("qwen-china", china)
                if (provider == Provider.MUSE) putString("muse-url", endpoint)
            }.apply()
            if (existingSecret != updatedSecret) secrets.delete(existingSecret)
            if (activate) selectProvider(provider)
            current.update { it.copy(configuredProviders = configuredProviders(), verifiedProviders = it.verifiedProviders - provider,
                notice = application.interfaceString(R.string.configuracion_guardada)) }
        }.onFailure { error ->
            current.update { it.copy(notice = (error as? IllegalArgumentException)?.message
                ?: application.interfaceString(R.string.no_se_pudo_guardar_la_4)) }
        }.isSuccess
    }

    fun forgetKey(provider: Provider) {
        if (current.value.busy) return
        secrets.delete(secretName(provider))
        current.update { it.copy(configuredProviders = configuredProviders(), verifiedProviders = it.verifiedProviders - provider,
            notice = application.interfaceString(R.string.clave_eliminada)) }
    }

    internal fun liveKey(provider: Provider): String = secrets.read(provider.name)
        ?: error(application.interfaceString(R.string.guarda_tu_clave_de_en, provider.label))

    internal fun saveLiveTurn(turn: Turn) {
        val turns = (current.value.turns + turn).takeLast(100)
        persistTurns(turns)
        current.update { it.copy(turns = turns) }
    }

    fun newSession() {
        if (!current.value.busy) current.update { it.copy(session = java.util.UUID.randomUUID().toString()) }
    }

    fun selectSession(id: String) {
        val state = current.value
        if (state.busy) return
        val previous = state.turns.lastOrNull { it.session == id }?.provider
        if (previous == Provider.MUSE || state.provider == Provider.MUSE) selectProvider(previous ?: Provider.OPENAI)
        current.update { it.copy(session = id) }
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

    fun checkConnection(provider: Provider) = request("Reply with only the word OK.", check = true, testProvider = provider)
    internal fun ask(question: String, image: Bitmap?, continuation: Boolean, language: String, done: (String) -> Unit) =
        request(question, image, continuation, language = language, done = done)

    internal fun askAudio(wav: ByteArray, image: Bitmap?, language: AppLanguage, done: (String) -> Unit) =
        request("", image, continuation = true, wav = wav, language = language.promptName, done = done)

    internal suspend fun askFromGlasses(question: String, language: AppLanguage, wav: ByteArray? = null,
        onHeard: (String) -> Unit = {}): String {
        val provider = state.value.provider
        var answer: String? = null
        val job = request(question, continuation = true, wav = wav.takeIf { provider == Provider.GEMINI },
            language = language.promptName, spoken = true, onHeard = onHeard) { answer = it }
            ?: return application.interfaceString(R.string.handsfree_assistant_busy)
        try {
            val finished = withTimeoutOrNull(11_000) { job.join(); true } == true
            if (!finished) return application.interfaceString(R.string.assistant_timeout, provider.label)
            return answer ?: state.value.notice ?: application.interfaceString(R.string.no_se_pudo_completar_la)
        } finally {
            if (job.isActive) job.cancel()
        }
    }

    private fun request(question: String, image: Bitmap? = null, continuation: Boolean = false, check: Boolean = false,
        wav: ByteArray? = null, language: String = AppLanguage.SPANISH.promptName, testProvider: Provider? = null,
        spoken: Boolean = false, onHeard: (String) -> Unit = {}, done: (String) -> Unit = {}): Job? {
        val from = current.value
        val provider = testProvider ?: from.provider
        val museEndpoint = museUrl.takeIf { provider == Provider.MUSE }
        if (from.busy || (wav == null && question.isBlank())) return null
        current.update { it.copy(busy = true, notice = null) }
        return viewModelScope.launch {
            try {
                val (heard, answer) = withContext(Dispatchers.IO) {
                    val key = secrets.read(secretName(provider, museEndpoint.orEmpty()))
                        ?: error(application.interfaceString(R.string.configura_una_clave_de_en, provider.label))
                    val model = configuredModel(provider)
                    val history = if (continuation) from.turns.filter {
                        it.session == from.session && (it.provider == Provider.MUSE) == (provider == Provider.MUSE) &&
                            (museEndpoint == null || it.museEndpoint == museEndpoint)
                    }.takeLast(8) else emptyList()
                    val recent = if (spoken) history.takeLast(3) else history
                    if (wav == null) {
                        val url = provider.url(application, workspace, china, museEndpoint.orEmpty())
                        question.trim() to requestChat(application, provider, url, key, model, question.trim(), image, recent, language, spoken)
                    }
                    else {
                        require(provider == Provider.GEMINI) { application.interfaceString(R.string.selecciona_google_gemini_para_hablar) }
                        requestGeminiAudio(application, key, model, wav, image, recent, language, spoken)
                    }
                }
                if (check) current.update { it.copy(verifiedProviders = it.verifiedProviders + provider,
                    notice = application.interfaceString(R.string.conexion_verificada_con, provider.label)) }
                else {
                    current.update { it.copy(verifiedProviders = it.verifiedProviders + provider) }
                    onHeard(heard)
                    saveLiveTurn(Turn(heard, answer, System.currentTimeMillis(), from.session, provider, museEndpoint))
                    done(answer)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: IllegalStateException) {
                current.update { it.copy(verifiedProviders = it.verifiedProviders - provider,
                    notice = exception.message ?: application.interfaceString(R.string.no_se_pudo_completar_la)) }
            } catch (exception: IllegalArgumentException) {
                current.update { it.copy(notice = exception.message ?: application.interfaceString(R.string.revisa_la_configuracion_del_proveedor)) }
            } catch (_: InterruptedIOException) {
                current.update { it.copy(verifiedProviders = it.verifiedProviders - provider,
                    notice = application.interfaceString(R.string.assistant_timeout, provider.label)) }
            } catch (_: IOException) {
                current.update { it.copy(verifiedProviders = it.verifiedProviders - provider,
                    notice = application.interfaceString(R.string.assistant_network, provider.label)) }
            } catch (_: Exception) {
                current.update { it.copy(verifiedProviders = it.verifiedProviders - provider, notice = application.interfaceString(R.string.no_se_pudo_contactar_con)) }
            } finally {
                current.update { it.copy(busy = false) }
            }
        }
    }

    private fun loadTurns(): List<Turn> = runCatching {
        val data = JSONArray(store.getString("history", "[]"))
        List(data.length()) { index ->
            val item = data.getJSONObject(index)
            Turn(item.getString("user"), item.getString("answer"), item.getLong("time"), item.optString("session", "anteriores"),
                Provider.entries.firstOrNull { it.name == item.optString("provider") },
                item.optString("museEndpoint").takeIf { it.isNotEmpty() })
        }
    }.getOrDefault(emptyList())

    private fun persistTurns(turns: List<Turn>) {
        val data = JSONArray()
        turns.forEach { data.put(JSONObject().put("user", it.user).put("answer", it.answer).put("time", it.time)
            .put("session", it.session).put("provider", it.provider?.name).put("museEndpoint", it.museEndpoint)) }
        store.edit().putString("history", data.toString()).apply()
    }
}

private suspend fun requestChat(context: Context, provider: Provider, url: String, key: String, model: String, question: String, image: Bitmap?, history: List<Turn>, language: String, spoken: Boolean): String {
    val instructions = "Responde en $language con naturalidad y precisión. Usa el idioma de destino si la consulta pide traducir. Da respuestas concisas."
    val format = if (spoken) "Responde a la última pregunta en una o dos frases naturales, sin Markdown. " +
        "La pregunta se ha transcrito desde el micrófono. Si el texto no se entiende, pide que lo repitan."
        else "Si una imagen no aporta evidencia suficiente, dilo."
    val messages = JSONArray().put(message("system", "$instructions $format"))
    history.forEach { messages.put(message("user", it.user)).put(message("assistant", it.answer)) }
    val content = if (image == null) question else {
        JSONArray().put(JSONObject().put("type", "text").put("text", question))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + jpegBase64(image))))
    }
    messages.put(message("user", content))
    val payload = JSONObject().put("model", model).put("messages", messages)
    if (spoken && provider == Provider.GEMINI) {
        if (model in setOf("gemini-2.5-flash", "gemini-2.5-flash-lite")) {
            payload.put("reasoning_effort", "none").put("max_tokens", 200)
        } else if (model == "gemini-3.5-flash-lite") {
            payload.put("reasoning_effort", "minimal")
        }
    }
    val response = postJson(context, provider, url, "Authorization", "Bearer $key", payload, spoken)
    val answer = response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
    return answer.ifEmpty { error(context.interfaceString(R.string.no_devolvio_una_respuesta_de, provider.label)) }
}

private suspend fun requestGeminiAudio(context: Context, key: String, model: String, wav: ByteArray, image: Bitmap?,
    history: List<Turn>, language: String, spoken: Boolean): Pair<String, String> {
    val id = model.removePrefix("models/")
    require(id.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { context.interfaceString(R.string.revisa_el_identificador_del_modelo) }
    fun text(value: String) = JSONObject().put("text", value)
    fun content(role: String, parts: JSONArray) = JSONObject().put("role", role).put("parts", parts)
    fun media(mime: String, data: String) = JSONObject().put("inlineData", JSONObject().put("mimeType", mime).put("data", data))
    val system = "Transcribe el audio de la última pregunta en su idioma original y responde a lo que se oye en $language. " +
        "Usa la imagen si se incluye. Si no hay una pregunta inteligible, deja ambos campos vacíos. No inventes palabras." +
        if (spoken) " La respuesta se leerá en voz alta. Da una o dos frases naturales, sin Markdown." else ""
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
    val generation = JSONObject().put("responseMimeType", "application/json").put("responseSchema", schema)
    if (spoken && id in setOf("gemini-2.5-flash", "gemini-2.5-flash-lite")) {
        generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
    } else if (spoken && id == "gemini-3.5-flash-lite") {
        generation.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
    }
    val payload = JSONObject().put("systemInstruction", JSONObject().put("parts", JSONArray().put(text(system))))
        .put("contents", contents).put("generationConfig", generation)
    val response = postJson(context, Provider.GEMINI, "https://generativelanguage.googleapis.com/v1beta/models/$id:generateContent",
        "x-goog-api-key", key, payload, spoken)
    val returned = response.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
    val result = buildString {
        if (returned != null) for (i in 0 until returned.length()) {
            val part = returned.optJSONObject(i) ?: continue
            if (!part.optBoolean("thought")) append(part.optString("text"))
        }
    }
    val reply = runCatching { JSONObject(result) }.getOrElse {
        error(context.interfaceString(R.string.gemini_no_devolvio_una_respuesta))
    }
    val heard = reply.optString("transcription").trim()
    val answer = reply.optString("answer").trim()
    check(heard.isNotEmpty()) { context.interfaceString(R.string.gemini_no_entendio_la_pregunta) }
    check(answer.isNotEmpty()) { context.interfaceString(R.string.gemini_reconocio_tu_voz_pero) }
    return heard to answer
}

internal fun jpegBase64(image: Bitmap, maxSide: Int = 1280): String {
    val data = ByteArrayOutputStream()
    val factor = maxSide.toFloat() / maxOf(image.width, image.height)
    val resized = if (factor < 1) Bitmap.createScaledBitmap(image, maxOf(1, (factor * image.width).toInt()), maxOf(1, (factor * image.height).toInt()), true) else image
    try { resized.compress(Bitmap.CompressFormat.JPEG, 82, data) } finally { if (resized !== image) resized.recycle() }
    return Base64.encodeToString(data.toByteArray(), Base64.NO_WRAP)
}

private class GeminiUnavailable(val retryAfterSeconds: Long?) : IOException()

private val assistantHttp = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
private val spokenHttp = assistantHttp.newBuilder().callTimeout(9, TimeUnit.SECONDS).build()

private suspend fun postJson(context: Context, provider: Provider, url: String, authHeader: String, auth: String,
    payload: JSONObject, spoken: Boolean = false): JSONObject {
    val request = Request.Builder().url(url)
        .header(authHeader, auth)
        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()
    val http = if (spoken) spokenHttp else assistantHttp
    for (attempt in 0..2) {
        try {
            return sendJson(context, provider, http.newCall(request))
        } catch (unavailable: GeminiUnavailable) {
            val retryAfter = unavailable.retryAfterSeconds
            if (attempt == 2 || retryAfter != null && retryAfter > 6) {
                error(context.interfaceString(R.string.gemini_temporarily_unavailable))
            }
            val backoff = (1L shl attempt) * 1000L
            delay(maxOf(backoff, (retryAfter ?: 0L) * 1000L) + kotlin.random.Random.nextLong(100L, 351L))
        }
    }
    error(context.interfaceString(R.string.gemini_temporarily_unavailable))
}

private suspend fun sendJson(context: Context, provider: Provider, call: Call): JSONObject =
    suspendCancellableCoroutine { pending ->
        pending.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (pending.isActive) pending.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching { response.use { decodeAssistantResponse(context, provider, it) } }
                if (pending.isActive) pending.resumeWith(result)
            }
        })
    }

private fun decodeAssistantResponse(context: Context, provider: Provider, response: Response): JSONObject {
    val status = response.code
    if (provider == Provider.GEMINI && status == 503) {
        throw GeminiUnavailable(response.header("Retry-After")?.toLongOrNull()?.takeIf { it >= 0 })
    }
    check(response.isSuccessful) {
        when (status) {
            400, 404 -> context.interfaceString(R.string.rechazo_la_solicitud_revisa_el, provider.label, status)
            401, 403 -> context.interfaceString(R.string.rechazo_la_clave_o_los, provider.label, status)
            402, 429 -> context.interfaceString(R.string.indico_un_limite_de_uso, provider.label, status)
            else -> context.interfaceString(R.string.devolvio_un_error_intentalo_de, provider.label, status)
        }
    }
    return JSONObject(response.body?.string().orEmpty())
}

private fun message(role: String, content: Any) = JSONObject().put("role", role).put("content", content)

internal class Vault(private val context: Context) {
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
        }.getOrElse { error(context.interfaceString(R.string.no_se_pudo_recuperar_la)) }
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
