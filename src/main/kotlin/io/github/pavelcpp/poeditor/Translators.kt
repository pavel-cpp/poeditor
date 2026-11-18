package io.github.pavelcpp.poeditor

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.util.io.HttpRequests
import java.io.IOException
import java.net.URLEncoder

enum class Provider(val label: String) {
    DEEPL("DeepL"), MYMEMORY("MyMemory");

    override fun toString() = label
}

/** A failure with a message that is fine to show to the user as is. */
class TranslationException(message: String) : Exception(message)

interface Translator {
    val name: String

    /** Blocking network call, never call it on the UI thread. [lang] is the catalog language, e.g. "ru_RU"; the source is English. */
    fun translate(text: String, lang: String): String
}

class DeepL(private val key: String) : Translator {
    override val name = "DeepL"

    override fun translate(text: String, lang: String): String {
        // Free keys end with ":fx" and live on a different host
        val host = if (key.endsWith(":fx")) "api-free.deepl.com" else "api.deepl.com"
        val body = JsonObject().apply {
            add("text", JsonArray().apply { add(protect(text)) })
            addProperty("source_lang", "EN")
            addProperty("target_lang", deepLTarget(lang))
            addProperty("tag_handling", "xml")             // placeholders are wrapped in <x>, see protect()
            add("ignore_tags", JsonArray().apply { add("x") })
        }.toString()
        val json = call("DeepL", mapOf(
            403 to "Invalid DeepL API key. Check it in Settings | Tools | POEditor.",
            456 to "DeepL quota exceeded (the Free plan allows 500 000 characters per month).",
            429 to "DeepL says: too many requests. Try again in a moment.",
        )) {
            HttpRequests.post("https://$host/v2/translate", "application/json")
                .tuner { it.setRequestProperty("Authorization", "DeepL-Auth-Key $key") }
                .connectTimeout(10_000).readTimeout(20_000)
                .connect { r -> r.write(body); r.readString(null) }
        }
        return unprotect(parseDeepL(json))
    }
}

class MyMemory(private val email: String) : Translator {
    override val name = "MyMemory"

    override fun translate(text: String, lang: String): String {
        if (text.toByteArray().size > 500)
            throw TranslationException("MyMemory accepts at most 500 bytes per request and this string is longer. Use DeepL for long strings.")
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        // the email only raises the free daily limit from 5 000 to 50 000 characters; it is optional
        val url = "https://api.mymemory.translated.net/get?q=${enc(text)}&langpair=${enc("en|" + lang.replace('_', '-'))}" +
            if (email.isNotBlank()) "&de=${enc(email)}" else ""
        val json = call("MyMemory", emptyMap()) {
            HttpRequests.request(url).connectTimeout(10_000).readTimeout(20_000).readString(null)
        }
        return parseMyMemory(json)
    }
}

private fun <T> call(service: String, messages: Map<Int, String>, block: () -> T): T = try {
    block()
} catch (e: HttpRequests.HttpStatusException) { // must come before IOException, it is a subclass
    throw TranslationException(messages[e.statusCode] ?: "$service returned HTTP ${e.statusCode}.")
} catch (e: IOException) {
    throw TranslationException("Cannot reach $service: ${e.message}")
}

// ---------------------------------------------------------------------------------------------------------------------
// Pure helpers (unit-tested)

/** printf (`%d`, `%1$s`, `%-5.2f`), Python (`%(name)s`), Poedit/Qt (`%1%`, `%1`), `{0}`, `{name}`, `${var}`. */
private val PLACEHOLDER = Regex(
    """%\(\w+\)[-+#0]*\d*(?:\.\d+)?[a-zA-Z]|%(?:\d+\$)?[-+#0]*\d*(?:\.\d+)?[a-zA-Z%]|%\d+%?|\$\{[^}]*}|\{[\w.-]*}"""
)

/** Escapes the text for DeepL's XML mode and wraps placeholders in `<x>` so that they are left untouched. */
fun protect(text: String): String =
    PLACEHOLDER.replace(text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")) { "<x>${it.value}</x>" }

fun unprotect(translated: String): String = unescapeEntities(translated.replace(Regex("</?x>"), ""))

fun unescapeEntities(s: String): String =
    s.replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

/** Catalog language ("ru_RU", "pt_BR", "zh_CN") to DeepL's target code. */
fun deepLTarget(lang: String): String {
    val l = lang.replace('_', '-').uppercase()
    return when {
        l.startsWith("PT") -> if (l == "PT" || l == "PT-PT") "PT-PT" else "PT-BR"
        l.startsWith("EN") -> if (l == "EN-GB") "EN-GB" else "EN-US"
        l.startsWith("ZH") -> "ZH"
        else -> l.substringBefore('-')
    }
}

fun parseDeepL(json: String): String = try {
    JsonParser.parseString(json).asJsonObject["translations"].asJsonArray[0].asJsonObject["text"].asString
} catch (e: RuntimeException) {
    throw TranslationException("Unexpected response from DeepL.")
}

fun parseMyMemory(json: String): String {
    val o = try { JsonParser.parseString(json).asJsonObject } catch (e: RuntimeException) {
        throw TranslationException("Unexpected response from MyMemory.")
    }
    val text = o["responseData"]?.takeIf { it.isJsonObject }?.asJsonObject?.get("translatedText")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val status = o["responseStatus"]?.takeIf { it.isJsonPrimitive }?.asInt ?: 200
    if (status != 200) {
        val details = o["responseDetails"]?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        throw TranslationException("MyMemory: " + details.ifBlank { text }.ifBlank { "HTTP $status" })
    }
    return unescapeEntities(text)
}
