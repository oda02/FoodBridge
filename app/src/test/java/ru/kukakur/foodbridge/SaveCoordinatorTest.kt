package ru.kukakur.foodbridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.OffsetDateTime

class SaveCoordinatorTest {
    private val food = FoodPayload("test-id", "Lunch", 100.0, OffsetDateTime.parse("2026-09-11T12:00:00+03:00"))
    private class MemoryHistory : HistoryStore {
        val revisions = linkedMapOf<String, Long>()
        var failRemember = false
        override suspend fun latestRevision(id: String): Long? = revisions[id]
        override suspend fun remember(id: String, revision: Long) {
            if (failRemember) throw IOException("Local history unavailable")
            revisions[id] = maxOf(revisions[id] ?: 0L, revision)
        }
    }
    // This fake models an origin-scoped gateway, not the global provider database.
    private class MemoryGateway : RecordGateway {
        val own = linkedMapOf<String, StoredFood>()
        val foreign = linkedMapOf<String, StoredFood>()
        val lookups = mutableListOf<String>()
        val upserts = mutableListOf<FoodPayload>()
        val deletes = mutableListOf<String>()
        var lookupFailure: Exception? = null
        var upsertFailure: Exception? = null
        var deleteFailure: Exception? = null
        var deleteResponseLost = false
        var beforeUpsert: suspend (FoodPayload) -> Unit = {}
        var beforeDelete: suspend (String) -> Unit = {}
        fun seed(food: FoodPayload) { own[food.id] = StoredFood("own:${food.id}", food.revision, food.name) }
        override suspend fun lookup(id: String): StoredFood? {
            lookups += id
            lookupFailure?.let { throw it }
            return own[id]
        }
        override suspend fun upsert(food: FoodPayload) {
            beforeUpsert(food)
            upsertFailure?.let { throw it }
            upserts += food
            val previous = own[food.id]
            if (previous == null || previous.revision < food.revision) seed(food)
        }
        override suspend fun delete(recordId: String) {
            beforeDelete(recordId)
            deleteFailure?.let { throw it }
            deletes += recordId
            own.entries.removeAll { it.value.recordId == recordId }
            if (deleteResponseLost) throw IOException("Provider reply lost after deletion")
        }
    }

    @Test fun newSaveLooksUpBeforeInsertionAndRemembersOnlyAfterProviderSuccess() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway()
        gateway.beforeUpsert = {
            assertEquals(listOf(food.id), gateway.lookups)
            assertNull(history.latestRevision(food.id))
        }
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertEquals(SaveResult.ADDED, coordinator.save(food))
        assertEquals(1L, history.latestRevision(food.id)); assertEquals(listOf(food), gateway.upserts)
        assertEquals(SaveResult.DUPLICATE, coordinator.save(food)); assertEquals(1, gateway.upserts.size)
    }

    @Test fun unknownIdWithHigherRevisionCreatesWithoutRequiringLocalOriginal() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway()
        assertEquals(SaveResult.ADDED, SaveCoordinator(history, { true }, gateway).save(food.copy(revision = 9)))
        assertEquals(9L, history.latestRevision(food.id)); assertEquals(9L, gateway.own[food.id]?.revision)
    }

    @Test fun providerLookupRepairsMissingLocalHistoryAndSkipsStaleSave() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food.copy(revision = 7)) }
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertEquals(SaveResult.DUPLICATE, coordinator.save(food.copy(revision = 4)))
        assertEquals(7L, history.latestRevision(food.id)); assertTrue(gateway.upserts.isEmpty())
        assertEquals(SaveResult.DUPLICATE, coordinator.save(food.copy(revision = 7)))
    }

    @Test fun incomingHigherRevisionUpdatesProviderEvenWithoutLocalHistory() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food) }
        val corrected = food.copy(revision = 4, kcal = 155.0)
        assertEquals(SaveResult.UPDATED, SaveCoordinator(history, { true }, gateway).save(corrected))
        assertEquals(4L, history.latestRevision(food.id))
        assertEquals("own:${food.id}", gateway.own[food.id]?.recordId); assertEquals(listOf(corrected), gateway.upserts)
    }

    @Test fun localRevisionAndProviderRevisionBothPreventDowngrades() = runTest {
        for ((local, provider) in listOf(9L to 3L, 3L to 9L)) {
            val history = MemoryHistory().apply { revisions[food.id] = local }
            val gateway = MemoryGateway().apply { seed(food.copy(revision = provider)) }
            assertEquals(SaveResult.DUPLICATE, SaveCoordinator(history, { true }, gateway).save(food.copy(revision = 8)))
            assertEquals(9L, history.latestRevision(food.id)); assertTrue(gateway.upserts.isEmpty())
        }
    }

    @Test fun missingPermissionPreventsProviderLookupAndAllMutation() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway()
        val coordinator = SaveCoordinator(history, { false }, gateway)
        assertTrue(runCatching { coordinator.save(food) }.exceptionOrNull() is MissingPermission)
        assertTrue(runCatching { coordinator.delete(food.id, 2, true) }.exceptionOrNull() is MissingPermission)
        assertTrue(gateway.lookups.isEmpty()); assertTrue(gateway.upserts.isEmpty())
        assertTrue(gateway.deletes.isEmpty()); assertTrue(history.revisions.isEmpty())
    }

    @Test fun providerLookupFailureCannotBecomeAnInsertionOrDeletion() = runTest {
        val history = MemoryHistory()
        val gateway = MemoryGateway().apply { seed(food); lookupFailure = IOException("Lookup failed") }
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertTrue(runCatching { coordinator.save(food.copy(revision = 2)) }.exceptionOrNull() is IOException)
        assertTrue(runCatching { coordinator.delete(food.id, 2, true) }.exceptionOrNull() is IOException)
        assertTrue(gateway.upserts.isEmpty()); assertTrue(gateway.deletes.isEmpty())
        assertTrue(history.revisions.isEmpty()); assertEquals(1L, gateway.own[food.id]?.revision)
    }

    @Test fun failedUpsertLeavesHistoryUnchangedAndRetryCanSucceed() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { upsertFailure = IOException("Provider unavailable") }
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertTrue(runCatching { coordinator.save(food) }.exceptionOrNull() is IOException)
        assertTrue(history.revisions.isEmpty()); assertTrue(gateway.own.isEmpty())
        gateway.upsertFailure = null
        assertEquals(SaveResult.ADDED, coordinator.save(food)); assertEquals(1L, history.latestRevision(food.id))
    }

    @Test fun permissionRevokedDuringUpsertDoesNotRememberSuccess() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { upsertFailure = SecurityException("Permission revoked") }
        assertTrue(runCatching { SaveCoordinator(history, { true }, gateway).save(food) }.exceptionOrNull() is SecurityException)
        assertTrue(history.revisions.isEmpty()); assertTrue(gateway.own.isEmpty())
    }

    @Test fun failedLocalWriteAfterInsertionIsRecoveredFromProviderOnRetry() = runTest {
        val history = MemoryHistory().apply { failRemember = true }; val gateway = MemoryGateway()
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertTrue(runCatching { coordinator.save(food) }.exceptionOrNull() is IOException)
        assertEquals(1L, gateway.own[food.id]?.revision); assertTrue(history.revisions.isEmpty())
        history.failRemember = false
        assertEquals(SaveResult.DUPLICATE, coordinator.save(food))
        assertEquals(1, gateway.upserts.size); assertEquals(1L, history.latestRevision(food.id))
    }

    @Test fun deleteRequiresExplicitConfirmationAndRevisionAtLeastTwo() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food) }
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertTrue(runCatching { coordinator.delete(food.id, 2, false) }.isFailure)
        assertTrue(runCatching { coordinator.delete(food.id, 1, true) }.isFailure)
        assertTrue(gateway.deletes.isEmpty()); assertTrue(history.revisions.isEmpty())
        assertTrue(gateway.own.containsKey(food.id))
    }

    @Test fun deleteUsesOnlyTheOwnRecordIdReturnedByLookupAndRemembersTombstone() = runTest {
        val history = MemoryHistory()
        val gateway = MemoryGateway().apply {
            own[food.id] = StoredFood("provider-generated-record-id", 1, food.name)
            foreign[food.id] = StoredFood("foreign-record-id", 100, "Other app's meal")
        }
        gateway.beforeDelete = { assertNull(history.latestRevision(food.id)) }
        assertEquals(SaveResult.DELETED, SaveCoordinator(history, { true }, gateway).delete(food.id, 2, true))
        assertEquals(listOf("provider-generated-record-id"), gateway.deletes)
        assertTrue(gateway.own.isEmpty()); assertEquals("foreign-record-id", gateway.foreign[food.id]?.recordId)
        assertEquals(2L, history.latestRevision(food.id))
    }

    @Test fun missingDeleteTargetRemembersTombstoneWithoutCallingDelete() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway()
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertEquals(SaveResult.ALREADY_ABSENT, coordinator.delete(food.id, 5, true))
        assertEquals(5L, history.latestRevision(food.id)); assertTrue(gateway.deletes.isEmpty())
        assertEquals(SaveResult.DUPLICATE, coordinator.save(food.copy(revision = 4)))
        assertEquals(SaveResult.DUPLICATE, coordinator.delete(food.id, 5, true)); assertTrue(gateway.upserts.isEmpty())
    }

    @Test fun staleDeleteCannotRemoveANewerProviderRecord() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food.copy(revision = 7)) }
        assertEquals(SaveResult.DUPLICATE, SaveCoordinator(history, { true }, gateway).delete(food.id, 6, true))
        assertEquals(7L, history.latestRevision(food.id)); assertTrue(gateway.deletes.isEmpty())
        assertEquals(7L, gateway.own[food.id]?.revision)
    }

    @Test fun newerUpsertCanExplicitlyRecreateAfterADeletionTombstone() = runTest {
        val history = MemoryHistory().apply { revisions[food.id] = 3 }; val gateway = MemoryGateway()
        assertEquals(SaveResult.ADDED, SaveCoordinator(history, { true }, gateway).save(food.copy(revision = 4)))
        assertEquals(4L, history.latestRevision(food.id)); assertEquals(4L, gateway.own[food.id]?.revision)
    }

    @Test fun deleteFailureWithRecordStillPresentDoesNotRememberSuccess() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food); deleteFailure = IOException("Delete failed") }
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertTrue(runCatching { coordinator.delete(food.id, 2, true) }.exceptionOrNull() is IOException)
        assertTrue(history.revisions.isEmpty()); assertTrue(gateway.own.containsKey(food.id))
        assertTrue("Failed delete must be resolved by rereading", gateway.lookups.size >= 2)
        gateway.deleteFailure = null
        assertEquals(SaveResult.DELETED, coordinator.delete(food.id, 2, true))
    }

    @Test fun deleteThatSucceedsBeforeResponseLossIsResolvedByRereadingAbsence() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food); deleteResponseLost = true }
        assertEquals(SaveResult.DELETED, SaveCoordinator(history, { true }, gateway).delete(food.id, 2, true))
        assertTrue(gateway.own.isEmpty()); assertEquals(2L, history.latestRevision(food.id))
        assertTrue(gateway.lookups.size >= 2); assertEquals(1, gateway.deletes.size)
    }

    @Test fun lookupFailureAfterAmbiguousDeleteCannotClaimSuccess() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food); deleteResponseLost = true }
        gateway.beforeDelete = { gateway.lookupFailure = IOException("Follow-up lookup unavailable") }
        assertTrue(runCatching { SaveCoordinator(history, { true }, gateway).delete(food.id, 2, true) }.isFailure)
        assertTrue(history.revisions.isEmpty())
    }

    @Test fun failedHistoryWriteAfterDeletionRetriesAsAlreadyAbsentWithoutSecondDelete() = runTest {
        val history = MemoryHistory().apply { failRemember = true }; val gateway = MemoryGateway().apply { seed(food) }
        val coordinator = SaveCoordinator(history, { true }, gateway)
        assertTrue(runCatching { coordinator.delete(food.id, 2, true) }.exceptionOrNull() is IOException)
        assertTrue(gateway.own.isEmpty()); assertTrue(history.revisions.isEmpty())
        history.failRemember = false
        assertEquals(SaveResult.ALREADY_ABSENT, coordinator.delete(food.id, 2, true))
        assertEquals(1, gateway.deletes.size); assertEquals(2L, history.latestRevision(food.id))
    }

    @Test fun distinctCoordinatorInstancesSerializeLookupAndInsertion() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        gateway.beforeUpsert = { entered.complete(Unit); release.await() }
        val first = async { SaveCoordinator(history, { true }, gateway).save(food) }
        entered.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) { SaveCoordinator(history, { true }, gateway).save(food) }
        val wasBlocked = !second.isCompleted; val lookupCountWhileBlocked = gateway.lookups.size
        release.complete(Unit)
        assertEquals(SaveResult.ADDED, first.await()); assertEquals(SaveResult.DUPLICATE, second.await())
        assertTrue(wasBlocked); assertEquals(1, lookupCountWhileBlocked); assertEquals(1, gateway.upserts.size)
    }

    @Test fun concurrentNewerSavePreventsOlderDeletionAcrossCoordinatorInstances() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        gateway.beforeUpsert = { entered.complete(Unit); release.await() }
        val newer = async { SaveCoordinator(history, { true }, gateway).save(food.copy(revision = 4)) }
        entered.await()
        val older = async(start = CoroutineStart.UNDISPATCHED) { SaveCoordinator(history, { true }, gateway).delete(food.id, 3, true) }
        val wasBlocked = !older.isCompleted
        release.complete(Unit)
        assertEquals(SaveResult.UPDATED, newer.await()); assertEquals(SaveResult.DUPLICATE, older.await())
        assertTrue(wasBlocked); assertTrue(gateway.deletes.isEmpty()); assertEquals(4L, history.latestRevision(food.id))
    }

    @Test fun concurrentDeletionPreventsStaleRecreationAcrossCoordinatorInstances() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        gateway.beforeDelete = { entered.complete(Unit); release.await() }
        val deletion = async { SaveCoordinator(history, { true }, gateway).delete(food.id, 4, true) }
        entered.await()
        val stale = async(start = CoroutineStart.UNDISPATCHED) { SaveCoordinator(history, { true }, gateway).save(food.copy(revision = 3)) }
        val wasBlocked = !stale.isCompleted
        release.complete(Unit)
        assertEquals(SaveResult.DELETED, deletion.await()); assertEquals(SaveResult.DUPLICATE, stale.await())
        assertTrue(wasBlocked); assertTrue(gateway.upserts.isEmpty()); assertTrue(gateway.own.isEmpty())
    }

    @Test fun retryingAPartiallyAppliedBatchDoesNotReapplySuccessfulItems() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway()
        val coordinator = SaveCoordinator(history, { true }, gateway); val second = food.copy(id = "second")
        assertEquals(SaveResult.ADDED, coordinator.save(food))
        gateway.upsertFailure = IOException("Temporary failure on second operation")
        assertTrue(runCatching { coordinator.save(second) }.isFailure)
        gateway.upsertFailure = null
        assertEquals(SaveResult.DUPLICATE, coordinator.save(food)); assertEquals(SaveResult.ADDED, coordinator.save(second))
        assertEquals(listOf(food.id, second.id), gateway.upserts.map { it.id })
        assertEquals(setOf(food.id, second.id), gateway.own.keys)
    }

    @Test fun cancellingInFlightDeletionKeepsLockUntilTombstoneIsRemembered() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway().apply { seed(food) }
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        gateway.beforeDelete = { entered.complete(Unit); release.await() }
        val deletion = async { SaveCoordinator(history, { true }, gateway).delete(food.id, 4, true) }
        entered.await()
        deletion.cancel()
        val staleSave = async(start = CoroutineStart.UNDISPATCHED) {
            SaveCoordinator(history, { true }, gateway).save(food.copy(revision = 3))
        }
        val saveWasBlocked = !staleSave.isCompleted
        release.complete(Unit)
        deletion.join()
        assertEquals(SaveResult.DUPLICATE, staleSave.await())
        assertTrue(saveWasBlocked); assertTrue(deletion.isCancelled)
        assertEquals(4L, history.latestRevision(food.id)); assertTrue(gateway.own.isEmpty())
        assertEquals(1, gateway.deletes.size); assertTrue(gateway.upserts.isEmpty())
    }

    @Test fun independentIdsBothSaveAndAreResolvedByTheirOwnIdentity() = runTest {
        val history = MemoryHistory(); val gateway = MemoryGateway(); val coordinator = SaveCoordinator(history, { true }, gateway)
        val results = listOf(async { coordinator.save(food) }, async { coordinator.save(food.copy(id = "second")) }).awaitAll()
        assertTrue(results.all { it == SaveResult.ADDED }); assertEquals(setOf("test-id", "second"), gateway.own.keys)
    }

    @Test fun revisionHistoryMigratesLegacyRowsAndNeverDowngrades() {
        val hash = DeepLinkParser.sha256("meal")
        assertEquals(1L, RevisionHistory.latest(hash, hash))
        val updated = RevisionHistory.remember(hash, hash, 4)
        assertEquals(4L, RevisionHistory.latest(updated, hash))
        assertEquals(4L, RevisionHistory.latest(RevisionHistory.remember(updated, hash, 2), hash))
        val rows = (0 until IdHistory.LIMIT).joinToString("\n") { DeepLinkParser.sha256("id-$it") + ":1" }
        val advanced = RevisionHistory.remember(rows, hash, 2)
        assertEquals(IdHistory.LIMIT, advanced.lines().size)
        assertNull(RevisionHistory.latest(advanced, DeepLinkParser.sha256("id-0")))
        assertEquals(2L, RevisionHistory.latest(advanced, hash))
    }
}
