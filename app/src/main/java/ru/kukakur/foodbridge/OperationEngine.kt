package ru.kukakur.foodbridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class StoredFood(val recordId: String, val revision: Long, val name: String? = null)
interface RecordGateway {
    suspend fun lookup(id: String): StoredFood?
    suspend fun upsert(food: FoodPayload)
    suspend fun delete(recordId: String)
}
enum class SaveResult { ADDED, UPDATED, DELETED, ALREADY_ABSENT, DUPLICATE }
class MissingPermission : Exception()
class DeleteConfirmationRequired : Exception()

// One process-wide lock includes provider lookup, mutation and local revision write.
class SaveCoordinator(private val history: HistoryStore, private val permitted: suspend () -> Boolean, private val gateway: RecordGateway) {
    companion object { private val mutex = Mutex() }
    suspend fun save(food: FoodPayload): SaveResult = mutex.withLock { withContext(NonCancellable) {
        if (!permitted()) throw MissingPermission()
        val current = gateway.lookup(food.id)
        val latest = maxOf(history.latestRevision(food.id) ?: 0L, current?.revision ?: 0L)
        if (latest >= food.revision) {
            history.remember(food.id, latest)
            return@withContext SaveResult.DUPLICATE
        }
        gateway.upsert(food)
        history.remember(food.id, food.revision)
        if (current == null) SaveResult.ADDED else SaveResult.UPDATED
    } }
    suspend fun delete(id: String, revision: Long, confirmed: Boolean): SaveResult = mutex.withLock { withContext(NonCancellable) {
        if (!confirmed) throw DeleteConfirmationRequired()
        require(revision in 2..1000000)
        if (!permitted()) throw MissingPermission()
        val current = gateway.lookup(id)
        val latest = maxOf(history.latestRevision(id) ?: 0L, current?.revision ?: 0L)
        if (latest >= revision) {
            history.remember(id, latest)
            return@withContext SaveResult.DUPLICATE
        }
        if (current == null) {
            history.remember(id, revision)
            return@withContext SaveResult.ALREADY_ABSENT
        }
        try { gateway.delete(current.recordId) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // A deletion may have succeeded before a provider IPC failure. Absence
            // from a successful OWN-record lookup is the only safe retry evidence.
            if (gateway.lookup(id) != null) throw e
        }
        history.remember(id, revision)
        SaveResult.DELETED
    } }
}

data class OperationOutcome(val operation: FoodOperation, val result: SaveResult? = null, val error: String? = null, val permissionRequired: Boolean = false)
