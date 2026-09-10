package ru.kukakur.foodbridge

import android.app.Application
import android.content.Intent
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

data class ScreenState(
    val food: FoodPayload? = null, val request: FoodRequest? = null,
    val status: String = "home", val message: String? = null,
    val available: Int = HealthConnectClient.SDK_UNAVAILABLE, val permitted: Boolean = false,
    val autoAdd: Boolean = false, val closeAfterSave: Boolean = false, val requestId: Long = 0,
    val updated: Boolean = false, val existing: Map<String, StoredFood?> = emptyMap(),
    val outcomes: List<OperationOutcome> = emptyList(),
)

class FoodViewModel(application: Application) : AndroidViewModel(application) {
    val settings = Settings(application)
    private val repository = HealthConnectRepository(application, settings)
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var autoConsumed = false
    init { viewModelScope.launch { settings.autoAdd.collect { mutable.value = mutable.value.copy(autoAdd = it) } } }

    fun open(action: String?, url: String?) {
        val current = ++generation
        autoConsumed = false
        val request = if (action == Intent.ACTION_VIEW && url != null) RequestParser.parse(url).getOrNull() else null
        val home = url == null && action == Intent.ACTION_MAIN || action == Intent.ACTION_VIEW && url == DeepLinkParser.ORIGIN
        mutable.value = ScreenState(request = request, food = (request?.operations?.singleOrNull() as? FoodOperation.Upsert)?.food,
            status = if (home) "home" else if (request != null) "preview" else "invalid", requestId = current, autoAdd = mutable.value.autoAdd)
        viewModelScope.launch { refreshFor(current, allowAuto = true) }
    }
    fun refresh() { val current = generation; viewModelScope.launch { refreshFor(current, allowAuto = false) } }
    private suspend fun refreshFor(current: Long, allowAuto: Boolean) {
        try {
            val available = repository.status()
            val permitted = repository.permitted()
            val auto = settings.autoAdd.first()
            if (current != generation) return
            mutable.value = mutable.value.copy(available = available, permitted = permitted, autoAdd = auto)
            val request = mutable.value.request
            val existing = mutableMapOf<String, StoredFood?>()
            if (permitted && request != null && mutable.value.status == "preview") {
                for (op in request.operations) existing[op.id] = repository.inspect(op.id)
            }
            if (current != generation) return
            mutable.value = mutable.value.copy(existing = existing)
            if (allowAuto && !autoConsumed) {
                autoConsumed = true
                if (auto && permitted && mutable.value.status == "preview" && request != null && !request.hasDeletes && !request.suspicious) save(current, auto = true)
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (current == generation) mutable.value = mutable.value.copy(message = "Не удалось проверить Health Connect. Повторите попытку.") }
    }
    fun setAuto(enabled: Boolean) { viewModelScope.launch {
        try { settings.setAutoAdd(enabled) } catch (e: CancellationException) { throw e } catch (_: Exception) { mutable.value = mutable.value.copy(message = "Не удалось сохранить настройку.") }
    } }
    fun save(requestId: Long, auto: Boolean = false) {
        val initial = mutable.value
        val request = initial.request ?: return
        // Consent belongs to the exact preview, including its delete list.
        if (requestId != generation || initial.status !in listOf("preview", "partial")) return
        if (request.operations.any { it is FoodOperation.Upsert && it.food.time.toInstant().isAfter(Instant.now()) }) {
            mutable.value = initial.copy(message = "Время одного из блюд ещё не наступило. Исправьте дату в ссылке.")
            return
        }
        if (auto && (!initial.permitted || request.hasDeletes || request.suspicious || !initial.autoAdd)) return
        val current = generation
        val completed = initial.outcomes.filter { it.result != null }
        val remaining = FoodRequest(request.operations.filter { op -> completed.none { it.operation.id == op.id } })
        mutable.value = initial.copy(status = "saving", message = null)
        viewModelScope.launch {
            val results = repository.execute(remaining, confirmedDeletes = !auto) { progress ->
                if (current == generation) mutable.value = mutable.value.copy(outcomes = completed + progress)
            }
            if (current == generation) {
                val all = completed + results
                val failed = all.any { it.error != null }
                val duplicate = all.all { it.result == SaveResult.DUPLICATE }
                mutable.value = mutable.value.copy(outcomes = all, permitted = mutable.value.permitted && all.none { it.permissionRequired },
                    status = if (failed) "partial" else if (duplicate && request.operations.size == 1) "duplicate" else "success",
                    closeAfterSave = auto && !failed, updated = all.singleOrNull()?.result == SaveResult.UPDATED)
            }
        }
    }
}
