package ru.kukakur.foodbridge

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneOffset
import java.util.Base64

/** Unit tests for the internal flat food-field validator; public links use RequestParser. */
class DeepLinkParserTest {
    private val minimal = """{"id":"test-id","name":"Суп 🍲","kcal":125.5,"time":"2026-09-11T12:34:56+03:00"}"""
    private fun rejected(json: String) = assertTrue("Accepted invalid food fields: $json", runCatching { DeepLinkParser.parseJson(json) }.isFailure)
    private fun withField(key: String, value: String) = minimal.dropLast(1) + ",\"$key\":$value}"

    @Test fun unicodeRoundTripAndOptionalFieldsRemainAbsent() {
        val encoded = DeepLinkParser.encode(minimal)
        assertFalse(encoded.contains('='))
        assertEquals(minimal, DeepLinkParser.decode(encoded))
        val food = DeepLinkParser.parseJson(minimal)
        assertEquals("Суп 🍲", food.name)
        assertEquals("test-id", food.id)
        assertEquals(125.5, food.kcal, 0.0)
        assertEquals(ZoneOffset.ofHours(3), food.time.offset)
        assertEquals("2026-09-11T09:34:56Z", food.time.toInstant().toString())
        assertNull(food.p); assertNull(food.f); assertNull(food.c)
        assertNull(food.fiber); assertNull(food.sugar); assertNull(food.saturatedFat); assertNull(food.sodiumMg)
        assertEquals("other", food.meal)
        assertEquals(1L, food.revision)
    }

    @Test fun malformedOrNoncanonicalBase64AndInvalidUtf8AreRejected() {
        listOf("A", "abc=", "ab+c", "ab/c", "%65e30", "a b", "Zh", "_w", "wyg", "7aCA")
            .forEach { value -> assertTrue("Accepted fragment $value", runCatching { DeepLinkParser.decode(value) }.isFailure) }
        assertEquals("f", DeepLinkParser.decode("Zg"))
        val tooLarge = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(DeepLinkParser.MAX_JSON + 1) { 32 })
        assertTrue(runCatching { DeepLinkParser.decode(tooLarge) }.isFailure)
        assertEquals(" ".repeat(DeepLinkParser.MAX_JSON), DeepLinkParser.decode(DeepLinkParser.encode(" ".repeat(DeepLinkParser.MAX_JSON))))
        rejected(" ".repeat(DeepLinkParser.MAX_JSON) + minimal)
    }

    @Test fun flatFieldsRejectDuplicatesUnknownFieldsAndNestedValues() {
        rejected(withField("name", "\"Second name\""))
        rejected(withField("na\\u006de", "\"Escaped duplicate\""))
        listOf("{\"x\":1}", "[]", "[1]", "null", "true").forEach { rejected(minimal.replace("125.5", it)) }
        rejected(withField("extra", "1")); rejected(withField("v", "1")); rejected(withField("op", "\"upsert\""))
        rejected("[$minimal]"); rejected(minimal + minimal); rejected(minimal.dropLast(1) + ",}")
        listOf("01", "NaN", "Infinity", "1e999", "\"125.5\"", "125.5/*comment*/").forEach { rejected(minimal.replace("125.5", it)) }
        rejected(minimal.replace("\"name\"", "name"))
    }

    @Test fun requiredIdAndFieldTypesAreStrict() {
        rejected(minimal.replace("\"id\":\"test-id\",", ""))
        rejected(minimal.replace("\"name\":\"Суп 🍲\",", ""))
        rejected(minimal.replace("\"kcal\":125.5,", ""))
        rejected(minimal.replace(",\"time\":\"2026-09-11T12:34:56+03:00\"", ""))
        rejected(minimal.replace("\"Суп 🍲\"", "1"))
        rejected(minimal.replace("\"test-id\"", "null")); rejected(withField("p", "null"))
    }

    @Test fun stringsValidateBoundsControlsAndSurrogates() {
        listOf("", "   ", "x".repeat(201), "bad\\nname", "bad\\u0000name", "bad\\u202ename", "bad\\u2066name", "\\ud800", "\\udc00")
            .forEach { rejected(minimal.replace("Суп 🍲", it)); rejected(minimal.replace("test-id", it)) }
        assertEquals("x".repeat(200), DeepLinkParser.parseJson(minimal.replace("Суп 🍲", "x".repeat(200))).name)
        assertEquals("😀", DeepLinkParser.parseJson(minimal.replace("Суп 🍲", "\\ud83d\\ude00")).name)
        listOf("breakfast", "lunch", "dinner", "snack", "other").forEach { assertEquals(it, DeepLinkParser.parseJson(withField("meal", "\"$it\"")).meal) }
        listOf("\"brunch\"", "\"BREAKFAST\"", "null").forEach { rejected(withField("meal", it)) }
    }

    @Test fun explicitOffsetAndValidCalendarDateAreRequired() {
        val original = "2026-09-11T12:34:56+03:00"
        listOf("2026-09-11T12:34:56Z", "2026-09-11T12:34:56.123456789-04:30", "1970-01-01T00:00:00Z", "2100-12-31T23:59:59+14:00", "2024-02-29T00:00:00Z")
            .forEach { assertEquals(it.take(4).toInt(), DeepLinkParser.parseJson(minimal.replace(original, it)).time.year) }
        listOf("2026-09-11T12:34:56", "2026-09-11", "2026-02-29T00:00:00Z", "2026-13-01T00:00:00Z", "2026-09-11T24:00:00Z",
            "2026-09-11T12:34:60Z", "2026-09-11T12:34:56+19:00", "2026-09-11T12:34:56+0300", "1969-12-31T23:59:59Z", "2101-01-01T00:00:00Z",
            "2026-09-11 12:34:56Z", "2026-09-11T12:34Z").forEach { rejected(minimal.replace(original, it)) }
    }

    @Test fun nutrientRangesAcceptZeroAndMaxButRejectOutOfRange() {
        listOf("0", "20000", "2e4").forEach { assertTrue(DeepLinkParser.parseJson(minimal.replace("125.5", it)).kcal >= 0) }
        listOf("-0.1", "20000.1").forEach { rejected(minimal.replace("125.5", it)) }
        listOf("p", "f", "c", "fiber", "sugar", "saturatedFat", "sodiumMg").forEach { key ->
            val max = if (key == "sodiumMg") 100000 else 5000
            listOf("0", "$max").forEach { DeepLinkParser.parseJson(withField(key, it)) }
            listOf("-0.01", "${max + 1}", "\"1\"", "false").forEach { rejected(withField(key, it)) }
        }
        assertEquals(0.0.toBits(), DeepLinkParser.parseJson(minimal.replace("125.5", "-0")).kcal.toBits())
    }

    @Test fun suspiciousValuesRequireAdditionalConfirmation() {
        val food = DeepLinkParser.parseJson(minimal)
        assertFalse(food.suspicious)
        assertFalse(food.copy(kcal = 4000.0, p = 500.0, sodiumMg = 10000.0).suspicious)
        assertTrue(food.copy(kcal = 4000.1).suspicious); assertTrue(food.copy(p = 500.1).suspicious)
        assertTrue(food.copy(sodiumMg = 10000.1).suspicious)
    }

    @Test fun longTokensFailValidationWithoutStackOverflowAndMaxEscapedStringWorks() {
        listOf(minimal.replace("Суп 🍲", "x".repeat(7000)), minimal.replace("125.5", "1".repeat(7000)),
            minimal.replace("Суп 🍲", "\\u0061".repeat(1000)), "{\"" + "a".repeat(7000) + "\":1}").forEach { json ->
            val failure = runCatching { DeepLinkParser.parseJson(json) }.exceptionOrNull()
            assertTrue("Expected validation exception, got $failure", failure is IllegalArgumentException)
        }
        assertEquals("a".repeat(200), DeepLinkParser.parseJson(minimal.replace("Суп 🍲", "\\u0061".repeat(200))).name)
    }

    @Test fun revisionsDefaultToOneAndRequireExactPositiveIntegerTokens() {
        assertEquals(1L, DeepLinkParser.parseJson(minimal).revision)
        listOf(1L, 2L, 3L, 1000000L).forEach { revision -> assertEquals(revision, DeepLinkParser.parseJson(withField("rev", "$revision")).revision) }
        listOf("0", "-1", "1000001", "1.0", "2e0", "\"2\"", "null", "true", "[]", "{}", "9223372036854775808")
            .forEach { rejected(withField("rev", it)) }
        rejected(withField("rev", "1").dropLast(1) + ",\"rev\":2}")
    }
}
