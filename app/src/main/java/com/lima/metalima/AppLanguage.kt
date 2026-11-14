package com.lima.metalima

import java.util.Locale

internal enum class AppLanguage(
    val tag: String, val label: String, val promptName: String, val markCommand: String,
    val wakePhrases: List<String>
) {
    SPANISH("es-ES", "Español", "español", "Marca este momento", listOf("Hola Lima", "Oye Lima", "Hola asistente")),
    ENGLISH("en-US", "English", "inglés", "Mark this moment", listOf("Hello Lima", "Hey Lima", "Hello assistant")),
    GERMAN("de-DE", "Deutsch", "alemán", "Markiere diesen Moment", listOf("Hallo Lima", "Hey Lima", "Hallo Assistent")),
    ITALIAN("it-IT", "Italiano", "italiano", "Segna questo momento", listOf("Ciao Lima", "Ehi Lima", "Ciao assistente")),
    CHINESE("zh-CN", "简体中文", "chino mandarín simplificado", "标记这个时刻", listOf("你好利马", "嘿利马", "你好助手"));

    val locale: Locale get() = Locale.forLanguageTag(tag)
}

internal val translationLanguages = listOf("Español", "Inglés", "Alemán", "Italiano", "Chino mandarín", "Francés", "Portugués", "Árabe", "Japonés")
