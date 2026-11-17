package com.lima.metalima

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale

internal enum class AppLanguage(
    val tag: String, val label: String, val promptName: String, val markCommand: String,
    val wakePhrases: List<String>
) {
    SPANISH("es-ES", "Español", "español", "Marca este momento", listOf("OK Lima", "Hola Lima", "Oye Lima", "Hola asistente")),
    ENGLISH("en-US", "English", "inglés", "Mark this moment", listOf("Hello Lima", "Hey Lima", "Hello assistant", "OK Lima")),
    GERMAN("de-DE", "Deutsch", "alemán", "Markiere diesen Moment", listOf("Hallo Lima", "Hey Lima", "Hallo Assistent", "OK Lima")),
    ITALIAN("it-IT", "Italiano", "italiano", "Segna questo momento", listOf("Ciao Lima", "Ehi Lima", "Ciao assistente", "OK Lima")),
    CHINESE("zh-CN", "简体中文", "chino mandarín simplificado", "标记这个时刻", listOf("你好利马", "嘿利马", "你好助手", "OK Lima"));

    val locale: Locale get() = Locale.forLanguageTag(tag)
}

private val translationLabels = linkedMapOf(
    "Español" to R.string.language_spanish, "Inglés" to R.string.language_english,
    "Alemán" to R.string.language_german, "Italiano" to R.string.language_italian,
    "Chino mandarín" to R.string.language_chinese, "Francés" to R.string.language_french,
    "Portugués" to R.string.language_portuguese, "Árabe" to R.string.language_arabic,
    "Japonés" to R.string.language_japanese
)
internal val translationLanguages = translationLabels.keys.toList()

@Composable internal fun translationLabel(language: String): String =
    translationLabels[language]?.let { stringResource(it) } ?: language
