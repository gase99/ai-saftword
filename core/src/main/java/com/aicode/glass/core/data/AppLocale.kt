package com.aicode.glass.core.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The UI language has to be known while the activity is still attaching, before DataStore can be
 * read asynchronously. A small SharedPreferences mirror keeps that read synchronous and cheap.
 *
 * The language is applied by localizing the activity's own resources
 * (`MainActivity.attachBaseContext`). Do **not** replace `LocalContext` with the result of
 * `createConfigurationContext` inside Compose: that value is a plain `ContextImpl`, and
 * `hiltViewModel()` requires an `Activity` context, so every navigation destination used to crash
 * with "Expected an activity context for creating a HiltViewModelFactory".
 */
object AppLocale {
    const val ENGLISH = "en"
    const val PERSIAN = "fa"
    private const val FILE = "ui_locale"
    private const val KEY = "language"

    fun normalize(value: String?): String = if (value == PERSIAN) PERSIAN else ENGLISH

    /** Synchronous language read; safe on the main thread after [warmUp]. */
    fun read(context: Context): String = normalize(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, ENGLISH)
    )

    fun write(context: Context, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, normalize(value)).apply()
    }

    /** Loads the settings file once in `Application.onCreate`, before StrictMode watches I/O. */
    fun warmUp(context: Context) { read(context) }

    fun localize(base: Configuration, language: String): Configuration =
        Configuration(base).apply { setLocale(Locale.forLanguageTag(normalize(language))) }
}
