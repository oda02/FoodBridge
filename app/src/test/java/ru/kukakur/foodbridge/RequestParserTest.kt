package ru.kukakur.foodbridge

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RequestParserTest {
    private val upsert = """{"op":"upsert","id":"meal-1","name":"Суп 🍲","kcal":125.5,"time":"2020-01-15T12:34:56+03:00"}"""
    private val delete = """{"op":"delete","id":"meal-2","rev":2}"""
    private fun envelope(vararg items: String) = "{\"items\":[" + items.joinToString(",") + "]}"
    private fun url(json: String) = DeepLinkParser.ORIGIN + "#" + DeepLinkParser.encode(json)
    private fun rejected(json: String) = assertTrue("Accepted invalid request: $json", RequestParser.parse(url(json)).isFailure)
    private fun parse(vararg items: String) = RequestParser.parse(url(envelope(*items))).getOrThrow()

    @Test fun singleUpsertUsesTheSameEnvelopeAsABatch() {
        val request = parse(upsert)
        assertEquals(1, request.operations.size)
        val operation = request.operations.single() as FoodOperation.Upsert
        assertEquals("meal-1", operation.id)
        assertEquals(1L, operation.revision)
        assertEquals("Суп 🍲", operation.food.name)
        assertNull(operation.food.p)
        assertFalse(request.hasDeletes); assertFalse(request.suspicious)
    }

    @Test fun mixedOperationsPreserveOrderIdsAndRevisions() {
        val edit = upsert.replace("meal-1", "meal-3").dropLast(1) + ",\"rev\":3}"
        val request = parse(upsert, delete, edit)
        assertEquals(listOf("meal-1", "meal-2", "meal-3"), request.operations.map { it.id })
        assertEquals(listOf(1L, 2L, 3L), request.operations.map { it.revision })
        assertTrue(request.operations[0] is FoodOperation.Upsert)
        assertEquals(FoodOperation.Delete("meal-2", 2), request.operations[1])
        assertTrue(request.hasDeletes); assertFalse(request.suspicious)
        assertTrue(parse(upsert.replace("125.5", "4001"), delete).suspicious)
    }

    @Test fun singleAndMultipleDeletesAreSupported() {
        assertEquals(listOf(FoodOperation.Delete("meal-2", 2)), parse(delete).operations)
        val many = parse(delete, delete.replace("meal-2", "meal-3").replace(":2", ":1000000"))
        assertEquals(listOf(2L, 1000000L), many.operations.map { it.revision })
        assertTrue(many.hasDeletes); assertFalse(many.suspicious)
    }

    @Test fun batchesAcceptOneToTwentyOperationsOnly() {
        rejected(envelope())
        val twenty = (1..20).map { delete.replace("meal-2", "meal-$it") }
        assertEquals(20, parse(*twenty.toTypedArray()).operations.size)
        rejected(envelope(*(twenty + delete.replace("meal-2", "extra")).toTypedArray()))
    }

    @Test fun servingIdsMustBeUniqueAcrossAllOperationKinds() {
        rejected(envelope(upsert, upsert))
        rejected(envelope(delete, delete.replace(":2", ":3")))
        rejected(envelope(upsert, delete.replace("meal-2", "meal-1")))
        rejected(envelope(upsert, delete.replace("meal-2", "meal-\\u0031")))
    }

    @Test fun envelopeRejectsBarePayloadsAndVersionedOrUnknownTopFields() {
        rejected(upsert)
        rejected(upsert.replace("\"op\":\"upsert\",", ""))
        rejected(envelope(upsert).replace("{\"items\"", "{\"v\":1,\"items\""))
        rejected(envelope(upsert).replace("{\"items\"", "{\"v\":2,\"items\""))
        rejected(envelope(upsert).replace("{\"items\"", "{\"extra\":true,\"items\""))
        listOf("{}", "[]", "null", "{\"items\":null}", "{\"items\":{}}", "{\"items\":\"x\"}").forEach(::rejected)
        listOf("null", "true", "1", "\"delete\"", "[]").forEach { rejected(envelope(it)) }
    }

    @Test fun operationsRequireExplicitIdsKnownOpAndNoInnerVersion() {
        rejected(envelope(upsert.replace("\"id\":\"meal-1\",", "")))
        rejected(envelope(upsert.replace("\"op\":\"upsert\",", "")))
        rejected(envelope(upsert.replace("\"upsert\"", "\"add\"")))
        rejected(envelope(upsert.replace("\"upsert\"", "null")))
        rejected(envelope(upsert.dropLast(1) + ",\"v\":1}"))
        rejected(envelope(upsert.dropLast(1) + ",\"unknown\":1}"))
        rejected(envelope(delete.replace("\"id\":\"meal-2\",", "")))
        rejected(envelope(delete.replace("delete", "DELETE")))
    }

    @Test fun deleteIdentityValidationMatchesFoodFieldsAndRequiresLaterRevision() {
        listOf("0", "1", "-1", "1000001", "2.0", "2e0", "\"2\"", "null", "false").forEach { rejected(envelope(delete.replace(":2", ":$it"))) }
        rejected(envelope(delete.replace(",\"rev\":2", "")))
        rejected(envelope(delete.dropLast(1) + ",\"name\":\"unexpected\"}"))
        listOf("", " ", "x".repeat(201), "bad\\u202eid", "bad\\nid", "\\ud800").forEach { rejected(envelope(delete.replace("meal-2", it))) }
        assertEquals("x".repeat(200), parse(delete.replace("meal-2", "x".repeat(200))).operations.single().id)
        assertEquals("删除 🍲", parse(delete.replace("meal-2", "删除 🍲")).operations.single().id)
    }

    @Test fun upsertDelegationPreservesExactRevisionNumberSpelling() {
        listOf("1.0", "2e0", "\"2\"", "0", "null").forEach { rejected(envelope(upsert.dropLast(1) + ",\"rev\":$it}")) }
        assertEquals(2L, parse(upsert.dropLast(1) + ",\"rev\":2}").operations.single().revision)
        rejected(envelope(upsert.replace("125.5", "1e999")))
    }

    @Test fun duplicateDecodedKeysAreRejectedAtEnvelopeAndOperationLevels() {
        rejected("{\"items\":[$upsert],\"items\":[$delete]}")
        rejected("{\"items\":[$upsert],\"it\\u0065ms\":[$delete]}")
        rejected(envelope(upsert.dropLast(1) + ",\"name\":\"replacement\"}"))
        rejected(envelope(upsert.dropLast(1) + ",\"na\\u006de\":\"replacement\"}"))
        rejected(envelope(upsert.dropLast(1) + ",\"op\":\"delete\"}"))
        rejected(envelope(delete.dropLast(1) + ",\"rev\":3}"))
    }

    @Test fun malformedJsonAndExcessNestingAreRejectedWithoutStackOverflow() {
        listOf(envelope(upsert).dropLast(1) + ",}", "{\"items\":[$upsert,]}",
            envelope(upsert) + "{}", envelope(upsert.replace("125.5", "01")),
            envelope(upsert.replace("125.5", "1.")), envelope(upsert.replace("125.5", "1e+")),
            envelope(upsert.replace("Суп 🍲", "bad\\q")), envelope(upsert.replace("Суп 🍲", "bad\nname")),
            envelope(upsert.replace("125.5", "{\"nested\":1}")), envelope(upsert.replace("125.5", "[1]")),
            "[".repeat(3000) + "0" + "]".repeat(3000)).forEach { json ->
            val failure = runCatching { RequestParser.parseJson(json) }.exceptionOrNull()
            assertTrue("Validation must fail with an exception, not overflow: $failure", failure is Exception)
        }
        val quoted = parse(upsert.replace("Суп 🍲", "quoted \\\"name\\\" and {} []"))
        assertEquals("quoted \"name\" and {} []", (quoted.operations.single() as FoodOperation.Upsert).food.name)
    }

    @Test fun longStringsNumbersAndOversizedPayloadsAreBounded() {
        listOf(envelope(upsert.replace("Суп 🍲", "x".repeat(7000))), envelope(upsert.replace("125.5", "1".repeat(7000))),
            " ".repeat(8193), envelope(upsert.replace("Суп 🍲", "\\u0061".repeat(1000)))).forEach { json ->
            assertTrue(runCatching { RequestParser.parseJson(json) }.exceptionOrNull() is Exception)
        }
        val escaped = parse(upsert.replace("Суп 🍲", "\\u0061".repeat(200)))
        assertEquals("a".repeat(200), (escaped.operations.single() as FoodOperation.Upsert).food.name)
    }

    @Test fun publicLinksRequireExactHttpsRootOriginAndCanonicalFragmentEncoding() {
        val fragment = "#" + DeepLinkParser.encode(envelope(upsert))
        assertTrue(RequestParser.parse("https://food.kukakur.ru$fragment").isSuccess)
        listOf("http://food.kukakur.ru/", "https://evil.example/", "https://food.kukakur.ru.evil.example/", "https://user@food.kukakur.ru/",
            "https://food.kukakur.ru:443/", "https://food.kukakur.ru/path", "https://food.kukakur.ru/?data=1", "foodbridge://food.kukakur.ru/",
            "https://food.kukakur.ru/%2F").forEach { assertTrue(it, RequestParser.parse(it + fragment).isFailure) }
        listOf(DeepLinkParser.ORIGIN, DeepLinkParser.ORIGIN + "#", "not a URI", "x".repeat(12001), DeepLinkParser.ORIGIN + "#_w")
            .forEach { assertTrue(RequestParser.parse(it).isFailure) }
    }

    @Test fun portableUnifiedEnvelopeVectorsMatchParser() {
        val file = listOf(File("tests/vectors/deep-links.json"), File("../tests/vectors/deep-links.json")).firstOrNull { it.isFile }
        assertNotNull("Portable vectors are required", file)
        val vectors = Json.parseToJsonElement(file!!.readText(Charsets.UTF_8)).jsonArray
        assertTrue(vectors.size >= 50)
        vectors.forEach { entry ->
            val v = entry.jsonObject
            val parsed = RequestParser.parse(v.getValue("url").jsonPrimitive.content)
            assertEquals(v.getValue("label").jsonPrimitive.content, v.getValue("valid").jsonPrimitive.boolean, parsed.isSuccess)
            v["expectedOperations"]?.jsonArray?.let { expected ->
                val actual = parsed.getOrThrow().operations
                assertEquals(expected.size, actual.size)
                expected.zip(actual).forEach { (expectedEntry, operation) ->
                    val fields = expectedEntry.jsonObject
                    assertEquals(fields.getValue("id").jsonPrimitive.content, operation.id)
                    assertEquals(fields.getValue("rev").jsonPrimitive.long, operation.revision)
                    assertEquals(fields.getValue("op").jsonPrimitive.content, if (operation is FoodOperation.Delete) "delete" else "upsert")
                    fields["name"]?.let { assertEquals(it.jsonPrimitive.content, (operation as FoodOperation.Upsert).food.name) }
                }
            }
        }
    }
}
