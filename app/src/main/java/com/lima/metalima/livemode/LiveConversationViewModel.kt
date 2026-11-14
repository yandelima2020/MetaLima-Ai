package com.lima.metalima.livemode

import android.app.Application
import android.util.Base64
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lima.metalima.AppLanguage
import com.lima.metalima.Turn
import com.lima.metalima.Provider
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class LivePhase { IDLE, CONNECTING, LISTENING, RECONNECTING, STOPPING }

internal data class LiveConversationState(
    val phase: LivePhase = LivePhase.IDLE,
    val speaking: Boolean = false,
    val device: String? = null,
    val error: String? = null,
    val setupMs: Long? = null,
    val firstReplyMs: Long? = null,
    val question: String = "",
    val answer: String = "",
    val turns: List<Turn> = emptyList()
) {
    val active get() = phase != LivePhase.IDLE
}

internal class LiveConversationViewModel(app: Application) : AndroidViewModel(app) {
    private val current = MutableStateFlow(LiveConversationState())
    val state = current.asStateFlow()
    private var session: Job? = null
    private var connection: LiveSocket? = null

    fun sendFrame(jpeg: String): Boolean =
        current.value.phase == LivePhase.LISTENING && connection?.sendFrame(jpeg) == true

    fun start(model: LiveModel, key: () -> String, deviceNames: List<String>, usePhone: Boolean, saveTurn: (Turn) -> Unit,
        language: AppLanguage, translation: Translation? = null, tools: List<LiveTool> = emptyList(),
        handleTool: suspend (String, JSONObject) -> JSONObject = { _, _ -> JSONObject() }) {
        if (current.value.active) return
        current.value = LiveConversationState(phase = LivePhase.CONNECTING)
        session = viewModelScope.launch {
            val audio = LiveAudio(getApplication(), model.inputRate)
            try {
                converse(model, key(), deviceNames, usePhone, audio, saveTurn, language, translation, tools, handleTool)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalStateException) {
                current.update { it.copy(error = error.message ?: "La conversación en directo se detuvo.") }
            } catch (_: Exception) {
                current.update { it.copy(error = "No se pudo iniciar o mantener la conversación. Revisa el micrófono, Bluetooth e Internet.") }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { audio.close() }
                current.update { it.copy(phase = LivePhase.IDLE, speaking = false) }
                session = null
            }
        }
    }

    fun stop() {
        val job = session ?: return
        current.update { it.copy(phase = LivePhase.STOPPING, speaking = false) }
        job.cancel()
    }

    private suspend fun converse(
        model: LiveModel, key: String, names: List<String>, usePhone: Boolean, audio: LiveAudio, saveTurn: (Turn) -> Unit,
        language: AppLanguage, translation: Translation?, tools: List<LiveTool>, handleTool: suspend (String, JSONObject) -> JSONObject
    ) {
        val sessionId = UUID.randomUUID().toString()
        var handle: String? = null
        var reconnectAttempts = 0
        var device: String? = null
        var question = ""
        var answer = ""
        var turnStartedAt: Long? = null
        fun keepTurn() {
            if (question.isNotBlank() || answer.isNotBlank()) {
                val turn = Turn(question.trim(), answer.trim(), System.currentTimeMillis(), sessionId)
                saveTurn(turn)
                current.update { it.copy(turns = (it.turns + turn).takeLast(40), question = "", answer = "") }
            }
            question = ""
            answer = ""
            turnStartedAt = null
        }
        try {
            while (true) {
                val openedAt = SystemClock.elapsedRealtime()
                val socket = LiveSocket(key, model, handle, language, translation, tools)
                connection = socket
                try {
                    check(withTimeoutOrNull(12_000) { socket.ready.await(); true } == true) {
                        "${model.provider.label} tardó demasiado en iniciar. Comprueba Internet y vuelve a intentarlo."
                    }
                    if (device == null) device = audio.open(names, usePhone)
                    current.update { it.copy(phase = LivePhase.LISTENING, device = device, setupMs = SystemClock.elapsedRealtime() - openedAt) }
                    coroutineScope {
                        var outputId = ""
                        var outputIndex = 0
                        var outputStart = 0L
                        var cutOutputId = ""
                        fun appendAudio(value: String) {
                            if (value.isEmpty()) return
                            if (current.value.firstReplyMs == null) turnStartedAt?.let { start ->
                                current.update { it.copy(firstReplyMs = SystemClock.elapsedRealtime() - start) }
                            }
                            audio.enqueue(Base64.decode(value, Base64.DEFAULT))
                        }
                        val capture = launch { audio.capture(socket::sendAudio) }
                        val player = launch { audio.play { playing -> current.update { it.copy(speaking = playing) } } }
                        try {
                            for (message in socket.messages) {
                                if (model.provider == Provider.OPENAI) {
                                    when (message.optString("type")) {
                                        "session.closed" -> throw LiveConnectionError("La sesión de OpenAI terminó. Pulsa Iniciar para volver a hablar.", false)
                                        "session.delegation.created" -> {
                                            val id = message.optJSONObject("delegation")?.optString("id").orEmpty()
                                            if (id.isNotBlank()) socket.send(JSONObject().put("type", "session.thinking.append")
                                                .put("delegation_id", id).put("text", "Responde directamente con lo que sabes. No hay herramientas externas activas."))
                                        }
                                        "session.input_transcript.delta" -> {
                                            if (answer.isNotBlank()) keepTurn()
                                            question += message.optString("delta")
                                        }
                                        "session.output_transcript.delta" -> answer += message.optString("delta")
                                        "session.output_audio.delta" -> appendAudio(message.optString("delta"))
                                        "input_audio_buffer.speech_started" -> {
                                            if (outputId.isNotBlank() && audio.enqueuedFrames() > audio.playedFrames()) {
                                                val endMs = (audio.playedFrames() - outputStart).coerceAtLeast(0L) * 1000 / 24_000
                                                audio.flush()
                                                socket.send(JSONObject().put("type", "conversation.item.truncate").put("item_id", outputId)
                                                    .put("content_index", outputIndex).put("audio_end_ms", endMs))
                                                cutOutputId = outputId
                                                outputId = ""
                                                current.update { it.copy(speaking = false) }
                                            }
                                            if (answer.isNotBlank()) keepTurn()
                                            turnStartedAt = SystemClock.elapsedRealtime()
                                        }
                                        "conversation.item.input_audio_transcription.completed" -> question = message.optString("transcript")
                                        "response.output_audio_transcript.delta" -> {
                                            if (message.optString("item_id") != cutOutputId) answer += message.optString("delta")
                                        }
                                        "response.output_audio.delta" -> {
                                            val id = message.optString("item_id")
                                            if (id != cutOutputId) {
                                                if (id != outputId) {
                                                    outputId = id
                                                    outputIndex = message.optInt("content_index")
                                                    outputStart = audio.enqueuedFrames()
                                                }
                                                appendAudio(message.optString("delta"))
                                            }
                                        }
                                        "response.done" -> {
                                            val response = message.optJSONObject("response")
                                            if (response?.optString("status") == "failed") {
                                                val reason = response.optJSONObject("status_details")?.optJSONObject("error")?.optString("code").orEmpty()
                                                val notice = if (reason.contains("quota") || reason.contains("rate_limit")) "OpenAI alcanzó el límite del proyecto."
                                                    else "OpenAI no pudo generar la respuesta de voz. Prueba otro modelo disponible en tu proyecto."
                                                throw LiveConnectionError(notice, false)
                                            }
                                            if (answer.isNotBlank()) keepTurn()
                                        }
                                    }
                                    current.update { it.copy(question = question, answer = answer) }
                                    continue
                                }
                                if (message.has("goAway")) {
                                    if (handle == null) continue
                                    throw LiveConnectionError("Gemini está renovando la conexión.", true)
                                }
                                val update = message.optJSONObject("sessionResumptionUpdate")
                                if (update?.optBoolean("resumable") == true) {
                                    update.optString("newHandle").takeIf(String::isNotBlank)?.let { handle = it }
                                }
                                val calls = message.optJSONObject("toolCall")?.optJSONArray("functionCalls")
                                if (calls != null) {
                                    val responses = JSONArray()
                                    for (i in 0 until calls.length()) {
                                        val call = calls.optJSONObject(i) ?: continue
                                        val name = call.optString("name")
                                        val result = try {
                                            if (tools.none { it.name == name }) error("Acción no habilitada.")
                                            handleTool(name, call.optJSONObject("args") ?: JSONObject())
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: Exception) {
                                            JSONObject().put("response", JSONObject().put("error", error.message ?: "No se pudo completar la acción."))
                                        }
                                        responses.put(result.put("id", call.optString("id")).put("name", name))
                                    }
                                    socket.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)))
                                }
                                val content = message.optJSONObject("serverContent") ?: continue
                                if (content.optBoolean("interrupted")) {
                                    audio.flush()
                                    current.update { it.copy(speaking = false) }
                                    if (answer.isNotBlank()) keepTurn()
                                }
                                val spoken = content.optJSONObject("inputTranscription")?.optString("text").orEmpty()
                                if (spoken.isNotBlank() && turnStartedAt == null) turnStartedAt = SystemClock.elapsedRealtime()
                                question += spoken
                                answer += content.optJSONObject("outputTranscription")?.optString("text").orEmpty()
                                current.update { it.copy(question = question, answer = answer) }
                                val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
                                if (parts != null && !content.optBoolean("interrupted")) {
                                    for (i in 0 until parts.length()) {
                                        val blob = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                                        if (blob.optString("mimeType").startsWith("audio/pcm")) {
                                            appendAudio(blob.optString("data"))
                                        }
                                    }
                                }
                                if (content.optBoolean("turnComplete") && !content.optBoolean("interrupted")) keepTurn()
                            }
                        } finally {
                            capture.cancel()
                            player.cancel()
                        }
                    }
                    return
                } catch (error: LiveConnectionError) {
                    if (!error.retryable || handle == null || device == null || reconnectAttempts == 2) throw error
                    reconnectAttempts++
                    audio.flush()
                    keepTurn()
                    current.update { it.copy(phase = LivePhase.RECONNECTING, speaking = false) }
                } finally {
                    if (connection === socket) connection = null
                    socket.close()
                }
                delay(reconnectAttempts * 750L)
            }
        } finally {
            keepTurn()
        }
    }
}
