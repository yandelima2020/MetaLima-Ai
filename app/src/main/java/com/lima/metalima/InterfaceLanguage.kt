package com.lima.metalima

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import java.util.Locale

private const val UI_LOCALE = "app-locale"

internal fun Context.selectedInterfaceLanguage(): AppLanguage? {
    val tag = if (Build.VERSION.SDK_INT >= 33) {
        getSystemService(LocaleManager::class.java).applicationLocales[0]?.toLanguageTag()
    } else {
        getSharedPreferences(UI_LOCALE, Context.MODE_PRIVATE).getString(UI_LOCALE, null)
    }
    return tag?.let { saved -> AppLanguage.entries.firstOrNull { it.locale.language == saved.substringBefore('-') } }
}

internal fun MainActivity.setInterfaceLanguage(language: AppLanguage?) {
    if (selectedInterfaceLanguage() == language) return
    if (Build.VERSION.SDK_INT >= 33) {
        getSystemService(LocaleManager::class.java).applicationLocales =
            LocaleList.forLanguageTags(language?.interfaceTag.orEmpty())
    } else {
        getSharedPreferences(UI_LOCALE, Context.MODE_PRIVATE).edit()
            .putString(UI_LOCALE, language?.interfaceTag).apply()
        recreate()
    }
}

internal fun Context.interfaceContext(): Context {
    if (Build.VERSION.SDK_INT >= 33) return this
    val language = selectedInterfaceLanguage() ?: return this
    if (resources.configuration.locales[0]?.toLanguageTag() == language.interfaceTag) return this
    return createConfigurationContext(Configuration().apply {
        setLocales(LocaleList.forLanguageTags(language.interfaceTag))
    })
}

internal fun Context.interfaceString(@StringRes id: Int, vararg args: Any): String {
    val localized = interfaceContext()
    return if (args.isEmpty()) localized.getString(id) else localized.getString(id, *args)
}

internal fun Context.interfaceLocale(): Locale = interfaceContext().resources.configuration.locales[0] ?: Locale.getDefault()

private val AppLanguage.interfaceTag: String
    get() = if (this == AppLanguage.CHINESE) "zh-Hans" else locale.language
