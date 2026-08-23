package com.pokedex

import com.intellij.DynamicBundle
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * Localised strings.
 *
 * The language follows the IDE by default and can be pinned to a specific
 * locale from the settings page, since a user may run an English IDE while
 * preferring their companion to speak something else.
 *
 * Two lookups beyond the usual single-key one are supported:
 *
 *  - [messages] collects a numbered family of keys into a list, which is how
 *    the reaction table stores several interchangeable lines per event.
 *  - Both lookups accept a variant suffix, used to give each personality its
 *    own wording while falling back to the shared phrasing when a variant is
 *    not defined.
 */
object PokedexBundle {

    private const val PATH = "messages.PokedexBundle"

    /** Set from settings. Empty means follow the IDE. */
    @Volatile
    var forcedLanguage: String = ""

    private val control: ResourceBundle.Control =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

    private fun locale(): Locale = when (forcedLanguage) {
        "en" -> Locale.ENGLISH
        "es" -> Locale.of("es")
        else -> runCatching { DynamicBundle.getLocale() }.getOrElse { Locale.getDefault() }
    }

    private fun bundle(): ResourceBundle =
        ResourceBundle.getBundle(PATH, locale(), PokedexBundle::class.java.classLoader, control)

    private fun lookup(key: String): String? = runCatching { bundle().getString(key) }.getOrNull()

    /** Resolves [key], substituting `{0}`-style placeholders. */
    fun message(key: String, vararg params: Any): String {
        val raw = lookup(key) ?: return key
        return if (params.isEmpty()) raw else MessageFormat.format(raw, *params)
    }

    /**
     * Resolves `key.1`, `key.2`, ... until a number is missing.
     *
     * A [variant] is tried first as `key.variant.n`, so a personality can
     * override some lines without having to restate the rest.
     */
    fun messages(key: String, variant: String? = null, vararg params: Any): List<String> {
        if (variant != null) {
            val variantLines = collect("$key.$variant", *params)
            if (variantLines.isNotEmpty()) return variantLines
        }
        return collect(key, *params)
    }

    private fun collect(key: String, vararg params: Any): List<String> {
        val out = mutableListOf<String>()
        var i = 1
        while (true) {
            val raw = lookup("$key.$i") ?: break
            out += if (params.isEmpty()) raw else MessageFormat.format(raw, *params)
            i++
        }
        return out
    }
}
