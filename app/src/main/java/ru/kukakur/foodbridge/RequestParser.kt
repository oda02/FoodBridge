package ru.kukakur.foodbridge

import kotlinx.serialization.json.*

/** Parses the unified items envelope without losing exact revision number spellings. */
object RequestParser {
    const val MAX_OPERATIONS = 20

    fun parse(url: String): Result<FoodRequest> = runCatching { parseJson(DeepLinkParser.decodeLink(url)) }

    fun parseJson(text: String): FoodRequest {
        require(text.toByteArray(Charsets.UTF_8).size <= DeepLinkParser.MAX_JSON) { "Payload too large" }
        val root = StrictJson(text).read().jsonObject
        require(root.keys == setOf("items")) { "Only the items envelope is accepted" }
        val items = root["items"] as? JsonArray ?: error("Items must be an array")
        require(items.size in 1..MAX_OPERATIONS) { "Batch must contain 1..20 operations" }
        val operations = items.map { entry ->
            val item = entry as? JsonObject ?: error("Operation must be an object")
            val op = item["op"] as? JsonPrimitive
            require(op != null && op.isString) { "Operation must be a string" }
            require("id" in item && "v" !in item) { "Batch operations require explicit id and no inner version" }
            when (op.content) {
                "upsert" -> {
                    // JsonLiteral retains the original numeric spelling (e.g. 2e0), so
                    // the food validator still rejects fractional/exponential revisions.
                    val payload = JsonObject(linkedMapOf<String, JsonElement>().apply {
                        putAll(item.filterKeys { it != "op" })
                    })
                    FoodOperation.Upsert(DeepLinkParser.parseJson(payload.toString()))
                }
                "delete" -> {
                    require(item.keys == setOf("op", "id", "rev")) { "Delete accepts only id and revision" }
                    // Reuse the exact existing id/revision validation, including Unicode,
                    // length, integer spelling, and range checks, with fixed benign fields.
                    val identity = DeepLinkParser.parseJson(buildJsonObject {
                        put("id", item.getValue("id")); put("rev", item.getValue("rev"))
                        put("name", "Delete"); put("kcal", 0); put("time", "2020-01-01T00:00:00Z")
                    }.toString())
                    require(identity.revision >= 2) { "Delete requires a later revision" }
                    FoodOperation.Delete(identity.id, identity.revision)
                }
                else -> error("Unknown operation")
            }
        }
        require(operations.map { it.id }.toSet().size == operations.size) { "Duplicate serving id in batch" }
        return FoodRequest(operations)
    }

    /**
     * A small strict scanner rejects duplicate decoded keys before JsonObject can
     * overwrite them. Container recursion is capped at 3: envelope -> items -> item.
     * String and number scanning are iterative and bounded, with no recursive regex.
     */
    private class StrictJson(private val text: String) {
        private var i = 0
        fun read(): JsonElement {
            val value = value(0)
            whitespace()
            require(i == text.length) { "Trailing JSON data" }
            return value
        }
        private fun whitespace() { while (i < text.length && text[i] in " \r\n\t") i++ }
        private fun take(c: Char) {
            whitespace()
            require(i < text.length && text[i] == c) { "Unexpected JSON token" }
            i++
        }
        private fun value(depth: Int): JsonElement {
            whitespace()
            require(i < text.length) { "Missing JSON value" }
            return when (text[i]) {
                '{' -> { require(depth < 3) { "JSON nesting too deep" }; objectValue(depth + 1) }
                '[' -> { require(depth < 3) { "JSON nesting too deep" }; arrayValue(depth + 1) }
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> numberValue()
                else -> error("Invalid JSON value")
            }
        }
        private fun objectValue(depth: Int): JsonObject {
            take('{')
            val out = linkedMapOf<String, JsonElement>()
            whitespace()
            if (i < text.length && text[i] == '}') { i++; return JsonObject(out) }
            while (true) {
                whitespace()
                require(i < text.length && text[i] == '"') { "Object key must be a string" }
                val key = stringValue().content
                require(key !in out) { "Duplicate JSON key" }
                take(':')
                out[key] = value(depth)
                whitespace()
                require(i < text.length) { "Unterminated object" }
                if (text[i] == '}') { i++; return JsonObject(out) }
                take(',')
            }
        }
        private fun arrayValue(depth: Int): JsonArray {
            take('[')
            val out = mutableListOf<JsonElement>()
            whitespace()
            if (i < text.length && text[i] == ']') { i++; return JsonArray(out) }
            while (true) {
                require(out.size < MAX_OPERATIONS) { "Too many array items" }
                out += value(depth)
                whitespace()
                require(i < text.length) { "Unterminated array" }
                if (text[i] == ']') { i++; return JsonArray(out) }
                take(',')
            }
        }
        private fun stringValue(): JsonPrimitive {
            val start = i
            take('"')
            while (i < text.length) {
                val c = text[i++]
                require(i - start <= 1202) { "String token too long" }
                if (c == '"') return Json.parseToJsonElement(text.substring(start, i)).jsonPrimitive
                require(c.code >= 0x20) { "Unescaped control character" }
                if (c == '\\') {
                    require(i < text.length) { "Unterminated escape" }
                    when (text[i++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            require(i + 4 <= text.length) { "Incomplete Unicode escape" }
                            repeat(4) { require(text[i++] in "0123456789abcdefABCDEF") { "Invalid Unicode escape" } }
                        }
                        else -> error("Invalid JSON escape")
                    }
                }
            }
            error("Unterminated string")
        }
        private fun literal(word: String): JsonElement {
            require(text.startsWith(word, i)) { "Invalid JSON literal" }
            i += word.length
            return Json.parseToJsonElement(word)
        }
        private fun numberValue(): JsonPrimitive {
            val start = i
            if (text[i] == '-') i++
            require(i < text.length) { "Incomplete number" }
            if (text[i] == '0') i++ else {
                require(text[i] in '1'..'9') { "Invalid number" }
                while (i < text.length && text[i] in '0'..'9') i++
            }
            if (i < text.length && text[i] == '.') {
                i++
                val digits = i
                while (i < text.length && text[i] in '0'..'9') i++
                require(i > digits) { "Missing fraction" }
            }
            if (i < text.length && text[i] in "eE") {
                i++
                if (i < text.length && text[i] in "+-") i++
                val digits = i
                while (i < text.length && text[i] in '0'..'9') i++
                require(i > digits) { "Missing exponent" }
            }
            require(i - start <= 64) { "Number token too long" }
            return Json.parseToJsonElement(text.substring(start, i)).jsonPrimitive
        }
    }
}
