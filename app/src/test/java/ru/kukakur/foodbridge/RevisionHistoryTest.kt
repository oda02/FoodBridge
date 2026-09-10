package ru.kukakur.foodbridge

import org.junit.Assert.*
import org.junit.Test

class RevisionHistoryTest {
    private val first = DeepLinkParser.sha256("first-serving")
    private val second = DeepLinkParser.sha256("second-serving")

    @Test fun legacyRawHashRowsReadAsRevisionOneAndMigrateWhenRemembered() {
        val legacy = "$first\n$second"
        assertEquals(1L, RevisionHistory.latest(legacy, first))
        assertEquals(1L, RevisionHistory.latest(legacy, second))
        val migrated = RevisionHistory.remember(legacy, first, 2)
        assertEquals("$second\n$first:2", migrated)
        assertEquals(2L, RevisionHistory.latest(migrated, first))
        assertEquals(1L, RevisionHistory.latest(migrated, second))
        assertEquals("$first:1", RevisionHistory.remember(first, first, 1))
    }

    @Test fun olderOrRepeatedRevisionsCannotRollBackStoredMaximum() {
        val initial = "$first:7\n$second:2"
        val replay = RevisionHistory.remember(initial, first, 7)
        val older = RevisionHistory.remember(replay, first, 3)
        assertEquals(7L, RevisionHistory.latest(older, first))
        assertEquals(listOf("$second:2", "$first:7"), older.split('\n'))
        val newer = RevisionHistory.remember(older, first, 1000000)
        assertEquals(1000000L, RevisionHistory.latest(newer, first))
        assertEquals(2L, RevisionHistory.latest(newer, second))
        assertEquals(1, newer.split('\n').count { it.startsWith("$first:") })
    }

    @Test fun historyCapEvictsOldestAndRefreshesAnEditedServing() {
        val hashes = (0 until IdHistory.LIMIT).map { DeepLinkParser.sha256("serving-$it") }
        val full = hashes.joinToString("\n") { "$it:1" }
        val refreshed = RevisionHistory.remember(full, hashes.first(), 2)
        assertEquals(IdHistory.LIMIT, refreshed.split('\n').size)
        assertEquals("${hashes.first()}:2", refreshed.split('\n').last())
        val newHash = DeepLinkParser.sha256("new-serving")
        val advanced = RevisionHistory.remember(refreshed, newHash, 1)
        assertEquals(IdHistory.LIMIT, advanced.split('\n').size)
        assertNull(RevisionHistory.latest(advanced, hashes[1]))
        assertEquals(2L, RevisionHistory.latest(advanced, hashes.first()))
        assertEquals(1L, RevisionHistory.latest(advanced, newHash))
    }

    @Test fun absentAndBlankHistoryHaveNoKnownOriginalAndLookupMatchesWholeHash() {
        assertNull(RevisionHistory.latest(null, first))
        assertNull(RevisionHistory.latest("\n\n", first))
        assertNull(RevisionHistory.latest("${first}0:9", first))
        assertEquals("$first:1", RevisionHistory.remember(null, first, 1))
        assertEquals("$first:1", RevisionHistory.remember("\n\n", first, 1))
        assertEquals(2L, RevisionHistory.latest("\n$first:2\n", first))
    }
}
