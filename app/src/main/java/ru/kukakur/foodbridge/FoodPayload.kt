package ru.kukakur.foodbridge

import java.time.OffsetDateTime

data class FoodPayload(
    val id: String,
    val name: String,
    val kcal: Double,
    val time: OffsetDateTime,
    val p: Double? = null, val f: Double? = null, val c: Double? = null,
    val fiber: Double? = null, val sugar: Double? = null,
    val saturatedFat: Double? = null, val sodiumMg: Double? = null,
    val meal: String = "other",
    val revision: Long = 1,
) {
    // Unusual but valid values always require an explicit tap, even with auto-add enabled.
    val suspicious: Boolean get() = kcal > 4000 || listOfNotNull(p, f, c, fiber, sugar, saturatedFat).any { it > 500 } || (sodiumMg ?: 0.0) > 10000
}

object IdHistory {
    const val LIMIT = 1000
    fun add(history: List<String>, id: String): List<String> = (history.filterNot { it == id } + id).takeLast(LIMIT)
}

object RevisionHistory {
    private fun rows(text: String?) = text?.split('\n')?.filter(String::isNotEmpty) ?: emptyList()
    fun latest(text: String?, hash: String): Long? = rows(text).firstOrNull { it.substringBefore(':') == hash }?.substringAfter(':', "1")?.toLongOrNull()
    fun remember(text: String?, hash: String, revision: Long): String =
        (rows(text).filterNot { it.substringBefore(':') == hash } + "$hash:${maxOf(latest(text, hash) ?: 0L, revision)}").takeLast(IdHistory.LIMIT).joinToString("\n")
}
