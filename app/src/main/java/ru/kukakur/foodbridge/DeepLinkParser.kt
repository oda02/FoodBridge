package ru.kukakur.foodbridge

import kotlinx.serialization.json.*
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

object DeepLinkParser {
    const val ORIGIN = "https://food.kukakur.ru/"
    const val MAX_URL = 12000
    const val MAX_JSON = 8192
    private val fields = setOf("id", "rev", "name", "kcal", "time", "p", "f", "c", "fiber", "sugar", "saturatedFat", "sodiumMg", "meal")



    fun decodeLink(url: String): String {
        require(url.length <= MAX_URL) { "URL too long" }
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "food.kukakur.ru" && uri.rawUserInfo == null && uri.port == -1)
        require(uri.rawPath in listOf("", "/") && uri.rawQuery == null) { "Only root fragment links are accepted" }
        return decode(requireNotNull(uri.rawFragment))
    }

    fun encode(json: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    fun decode(value: String): String {
        require(value.isNotEmpty() && value.length <= 10923 && value.matches(Regex("[A-Za-z0-9_-]+")))
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size <= MAX_JSON && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }

    // Individual food fields are deliberately flat. This scanner rejects duplicates, nested objects and arrays before JSON decoding.
    private fun flatObject(text: String): JsonObject {
        var i = 0
        fun ws() { while (i < text.length && text[i] in " \r\n\t") i++ }
        fun take(c: Char) { ws(); require(i < text.length && text[i++] == c) }
        fun token(): String {
            ws(); require(i < text.length)
            val start = i
            if (text[i] == '"') {
                i++
                while (i < text.length) {
                    val c = text[i++]
                    if (c == '"') return text.substring(start, i).also { require(it.length <= 1202) }
                    if (c == '\\') { require(i < text.length); i++ }
                    require(i - start <= 1202)
                }
                error("Unterminated string")
            }
            while (i < text.length && text[i] !in ",}: \r\n\t") i++
            return text.substring(start, i).also { require(it.length in 1..64 && it.matches(Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null"))) }
        }
        val out = linkedMapOf<String, JsonElement>()
        take('{'); ws()
        if (i < text.length && text[i] != '}') while (true) {
            val rawKey = token(); require(rawKey.startsWith('"'))
            val key = Json.parseToJsonElement(rawKey).jsonPrimitive.content
            require(key in fields && key !in out) { "Unknown or duplicate field" }
            take(':'); out[key] = Json.parseToJsonElement(token())
            ws(); if (i >= text.length || text[i] != ',') break
            i++
        }
        take('}'); ws(); require(i == text.length)
        return JsonObject(out)
    }

    fun parseJson(text: String): FoodPayload {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_JSON)
        val o = flatObject(text)
        fun string(key: String, max: Int, required: Boolean = false): String? {
            val x = o[key] ?: run { require(!required); return null }
            val p = x.jsonPrimitive
            require(p.isString)
            return p.content.also {
                require(it.isNotBlank() && it.length <= max && it.none { c -> c.isISOControl() || c in '\u202a'..'\u202e' || c in '\u2066'..'\u2069' })
                // Reject unpaired UTF-16 surrogates; valid emoji pairs are fine.
                require(Charsets.UTF_8.newEncoder().canEncode(it))
            }
        }
        fun number(key: String, max: Double, required: Boolean = false): Double? {
            val x = o[key] ?: run { require(!required); return null }
            val p = x.jsonPrimitive; require(!p.isString && p.content.length <= 64)
            val n = requireNotNull(p.doubleOrNull)
            require(n.isFinite() && n >= 0 && n <= max)
            return if (n == 0.0) 0.0 else n
        }

        val revision = o["rev"]?.jsonPrimitive?.let {
            require(!it.isString && it.content.matches(Regex("[1-9][0-9]{0,6}")))
            it.content.toLong().also { value -> require(value <= 1000000) }
        } ?: 1L
        val name = string("name", 200, true)!!
        val kcal = number("kcal", 20000.0, true)!!
        val timeString = string("time", 40, true)!!
        require(timeString.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})")))
        val time = OffsetDateTime.parse(timeString, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        require(time.year in 1970..2100)
        val meal = string("meal", 16) ?: "other"
        require(meal in setOf("breakfast", "lunch", "dinner", "snack", "other"))
        val p = number("p", 5000.0); val f = number("f", 5000.0); val c = number("c", 5000.0)
        val fiber = number("fiber", 5000.0); val sugar = number("sugar", 5000.0)
        val saturatedFat = number("saturatedFat", 5000.0); val sodium = number("sodiumMg", 100000.0)
        val id = string("id", 200, true)!!
        return FoodPayload(id, name, kcal, time, p, f, c, fiber, sugar, saturatedFat, sodium, meal, revision)
    }
    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
