package com.lima.metalima

import com.lima.metalima.livemode.LiveTool
import org.json.JSONArray
import org.json.JSONObject

private fun parameters(vararg fields: Pair<String, JSONObject>) =
    JSONObject().put("type", "OBJECT").put("properties", JSONObject().apply {
        fields.forEach { (name, field) -> put(name, field) }
    })

internal val studioLiveTools = listOf(
    LiveTool("ver_gafas",
        "Solo si el usuario pregunta explícitamente por lo que está viendo o pide leer o traducir algo a través de la cámara. Recibe una foto nueva de las gafas."),
    LiveTool("guardar_nota",
        "Guarda una nota local solo si el usuario lo pide explícitamente. adjuntar_foto debe ser true solo si pide adjuntar una foto de las gafas.",
        parameters("texto" to JSONObject().put("type", "STRING"), "adjuntar_foto" to JSONObject().put("type", "BOOLEAN"))
            .put("required", JSONArray().put("texto")))
)

internal fun liveActionResult(message: String, jpeg: String? = null): JSONObject =
    JSONObject().put("response", JSONObject().put("resultado", message)).apply {
        if (jpeg != null) put("parts", JSONArray().put(JSONObject().put("inlineData",
            JSONObject().put("mimeType", "image/jpeg").put("data", jpeg))))
    }
