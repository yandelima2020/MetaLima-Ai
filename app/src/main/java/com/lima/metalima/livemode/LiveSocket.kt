package com.lima.metalima.livemode

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.lima.metalima.AppLanguage
import com.lima.metalima.Provider

private const val LIVE_ENDPOINT = "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

internal enum class LiveModel(val label: String, val id: String, val provider: Provider) {
    GEMINI_31("Gemini 3.1 Live", "gemini-3.1-flash-live-preview", Provider.GEMINI),
    GEMINI_38("Gemini 3.8 Live", "gemini-3.8-live", Provider.GEMINI),
    GEMINI_38_THINKING("Gemini 3.8 Live Extended Thinking", "gemini-3.8-live-extended-thinking", Provider.GEMINI),
    GEMINI_25("Gemini 2.5 Flash Live · Audio nativo", "gemini-2.5-flash-native-audio-preview-12-2025", Provider.GEMINI),
    GPT_LIVE("GPT-Live-1", "gpt-live-1", Provider.OPENAI),
    GPT_21("GPT-Realtime-2.1", "gpt-realtime-2.1", Provider.OPENAI),
    GPT_21_MINI("GPT-Realtime-2.1 mini", "gpt-realtime-2.1-mini", Provider.OPENAI),
    GPT_2("GPT-Realtime-2", "gpt-realtime-2", Provider.OPENAI),
    GPT_15("GPT-Realtime-1.5", "gpt-realtime-1.5", Provider.OPENAI),
    GPT_REALTIME("GPT-Realtime · Anterior", "gpt-realtime", Provider.OPENAI),
    GPT_MINI("GPT-Realtime mini · Anterior", "gpt-realtime-mini", Provider.OPENAI),
    GPT_4O("GPT-4o Realtime · Anterior", "gpt-4o-realtime", Provider.OPENAI),
    GPT_4O_MINI("GPT-4o mini Realtime · Anterior", "gpt-4o-mini-realtime", Provider.OPENAI);

    val inputRate: Int get() = if (provider == Provider.GEMINI) 16_000 else 24_000
    val supportsVisionTools: Boolean get() = this in listOf(GEMINI_31, GEMINI_38, GEMINI_38_THINKING)
}

internal class LiveConnectionError(message: String, val retryable: Boolean) : IllegalStateException(message)
internal data class Translation(val from: String, val to: String) {
    val instruction get() = "Traduce la voz de $from a $to y de $to a $from según el idioma que detectes. Devuelve solo la traducción hablada, sin comentarios ni respuestas a las preguntas. No repitas el original."
}
internal data class LiveTool(val name: String, val description: String, val parameters: JSONObject? = null)

internal class LiveSocket(key: String, private val model: LiveModel, resumptionHandle: String?,
    language: AppLanguage, translation: Translation? = null, tools: List<LiveTool> = emptyList()) {
    val ready = CompletableDeferred<Unit>()
    val messages = Channel<JSONObject>(128)
    private val socket = client.newWebSocket(
        liveRequest(model, key),
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send(liveSetup(model, resumptionHandle, language, translation, tools).toString())) {
                    fail(LiveConnectionError("No se pudo iniciar la sesión con ${model.provider.label}.", true))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = receive(bytes.utf8())
            override fun onMessage(webSocket: WebSocket, text: String) = receive(text)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
                fail(socketError(code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                fail(socketError(code, reason))

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(when (response?.code) {
                    400, 404 -> LiveConnectionError("${model.provider.label} no ofreció acceso a ${model.label}. Prueba otro modelo.", false)
                    401, 403 -> LiveConnectionError("${model.provider.label} rechazó la clave o el acceso a este modelo. Revisa tu proyecto y la clave en Ajustes.", false)
                    429 -> LiveConnectionError("Se agotó la cuota de ${model.provider.label}. Revisa los límites de tu proyecto.", false)
                    else -> LiveConnectionError("Se perdió la conexión con ${model.provider.label}. Comprueba Internet.", true)
                })
            }
        }
    )

    fun sendAudio(pcm: ByteArray, count: Int, rate: Int) {
        // Drop stale input if the uplink is backed up instead of increasing the turn delay.
        if (socket.queueSize() > 24_000) return
        val data = Base64.encodeToString(pcm, 0, count, Base64.NO_WRAP)
        val event = if (model.provider == Provider.GEMINI) {
            val audio = JSONObject().put("mimeType", "audio/pcm;rate=$rate").put("data", data)
            JSONObject().put("realtimeInput", JSONObject().put("audio", audio))
        } else JSONObject().put("type", if (model == LiveModel.GPT_LIVE) "session.input_audio.append" else "input_audio_buffer.append")
            .put("audio", data)
        send(event)
    }

    fun send(event: JSONObject): Boolean = socket.send(event.toString())

    fun sendFrame(jpeg: String): Boolean = send(JSONObject().put("realtimeInput",
        JSONObject().put("video", JSONObject().put("mimeType", "image/jpeg").put("data", jpeg))))

    fun close() {
        // A backed-up connection must close without replaying queued microphone frames.
        if (!ready.isCompleted || socket.queueSize() > 0 || !socket.close(1000, null)) socket.cancel()
        messages.cancel()
        ready.cancel()
    }

    private fun receive(raw: String) {
        val event = runCatching { JSONObject(raw) }.getOrElse {
            fail(LiveConnectionError("El proveedor devolvió un mensaje que no se pudo leer.", true))
            return
        }
        val type = event.optString("type")
        if (event.has("setupComplete") || type == "session.updated" || type == "session.started") ready.complete(Unit)
        if (type == "error" || event.has("error")) {
            val code = event.optJSONObject("error")?.optString("code").orEmpty()
            if (code != "conversation_item_truncate_error" && code != "response_cancel_not_active") fail(socketError(1008, code))
            return
        }
        if (!messages.trySend(event).isSuccess) fail(LiveConnectionError("Se saturó la conexión de audio.", true))
    }

    private fun fail(error: LiveConnectionError) {
        ready.completeExceptionally(error)
        messages.close(error)
    }

    private fun socketError(code: Int, reason: String): LiveConnectionError = when {
        reason.contains("quota", true) || reason.contains("resource exhausted", true) || reason.contains("rate_limit", true) ->
            LiveConnectionError("Se agotó la cuota de ${model.provider.label}. Revisa los límites de tu proyecto.", false)
        code == 1008 || code == 1003 || code == 1007 ->
            LiveConnectionError("${model.provider.label} rechazó la sesión de ${model.label}. Comprueba el acceso al modelo, la clave y los límites del proyecto.", false)
        else -> LiveConnectionError("Se interrumpió la conexión con ${model.provider.label} (código $code).", true)
    }

    private companion object {
        val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).followRedirects(false).build()
    }
}

private fun liveRequest(model: LiveModel, key: String): Request {
    val request = Request.Builder()
    return when {
        model.provider == Provider.GEMINI -> request.url(LIVE_ENDPOINT).header("x-goog-api-key", key)
        model == LiveModel.GPT_LIVE -> request.url("wss://api.openai.com/v1/live/sessions").header("Authorization", "Bearer $key")
        else -> request.url("wss://api.openai.com/v1/realtime?model=${model.id}").header("Authorization", "Bearer $key")
    }.build()
}

internal fun liveSetup(model: LiveModel, handle: String?, language: AppLanguage,
    translation: Translation? = null, tools: List<LiveTool> = emptyList()): JSONObject {
    val assistant = "Eres el asistente de MetaLima Ai. Responde en ${language.promptName}, con frases breves y tono conversacional. Respeta cualquier idioma de destino que el usuario pida para una traducción."
    val instructions = translation?.instruction ?: if (tools.isNotEmpty()) "$assistant " +
        "Usa ver_gafas para solicitudes explícitas sobre lo que el usuario está viendo y guardar_nota solo cuando te pidan guardar una nota. " +
        "Si una herramienta devuelve un error, explica el error sin afirmar que la acción se realizó." else assistant
    if (model.provider == Provider.OPENAI) {
        fun pcm() = JSONObject().put("type", "audio/pcm").put("rate", 24_000)
        if (model == LiveModel.GPT_LIVE) return JSONObject().put("type", "session.start")
            .put("session", JSONObject().put("model", model.id)
                .put("instructions", "$instructions Responde directamente en la medida de lo posible.")
                .put("audio", JSONObject().put("format", pcm()).put("output", JSONObject().put("voice", "marin"))))
        val vad = JSONObject().put("type", "server_vad").put("silence_duration_ms", 430)
            .put("prefix_padding_ms", 300).put("create_response", true).put("interrupt_response", true)
        val transcription = JSONObject().put("model", "gpt-4o-mini-transcribe")
        if (translation == null) transcription.put("language", language.locale.language)
        val input = JSONObject().put("format", pcm()).put("turn_detection", vad).put("transcription", transcription)
        val audio = JSONObject().put("input", input).put("output", JSONObject().put("format", pcm()).put("voice", "alloy"))
        return JSONObject().put("type", "session.update").put("session", JSONObject().put("type", "realtime")
            .put("instructions", instructions).put("output_modalities", JSONArray().put("audio")).put("audio", audio))
    }
    val generation = JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
    val resumption = JSONObject().apply { if (handle != null) put("handle", handle) }
    val input = JSONObject().put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
    val setup = JSONObject().put("model", "models/${model.id}")
        .put("generationConfig", generation)
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", instructions))))
        .put("realtimeInputConfig", input)
        .put("inputAudioTranscription", JSONObject()).put("outputAudioTranscription", JSONObject())
        .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
        .put("sessionResumption", resumption)
    if (tools.isNotEmpty()) {
        val declarations = JSONArray()
        tools.forEach { tool ->
            val declaration = JSONObject().put("name", tool.name).put("description", tool.description)
            tool.parameters?.let { declaration.put("parameters", it) }
            declarations.put(declaration)
        }
        setup.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
    }
    return JSONObject().put("setup", setup)
}
