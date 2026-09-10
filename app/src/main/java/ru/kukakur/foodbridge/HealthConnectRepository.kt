package ru.kukakur.foodbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import android.health.connect.HealthConnectManager
import android.health.connect.HealthConnectException
import android.health.connect.ReadRecordsRequestUsingIds
import android.health.connect.ReadRecordsResponse
import android.os.OutcomeReceiver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.health.connect.datatypes.NutritionRecord as NativeNutrition

class HealthConnectRepository(private val context: Context, private val history: HistoryStore) {
    companion object {
        const val PROVIDER = "com.google.android.apps.healthdata"
        val permissions = setOf(HealthPermission.getWritePermission(NutritionRecord::class))
        fun record(food: FoodPayload): NutritionRecord = NutritionRecord(
            startTime = food.time.toInstant(), endTime = food.time.toInstant().plusSeconds(60),
            startZoneOffset = food.time.offset, endZoneOffset = food.time.offset,
            metadata = Metadata.manualEntry(clientRecordId = "foodbridge:" + DeepLinkParser.sha256(food.id), clientRecordVersion = food.revision),
            name = food.name, energy = Energy.kilocalories(food.kcal),
            protein = food.p?.let(Mass::grams), totalFat = food.f?.let(Mass::grams),
            totalCarbohydrate = food.c?.let(Mass::grams), dietaryFiber = food.fiber?.let(Mass::grams),
            sugar = food.sugar?.let(Mass::grams), saturatedFat = food.saturatedFat?.let(Mass::grams),
            sodium = food.sodiumMg?.let(Mass::milligrams),
            mealType = when (food.meal) {
                "breakfast" -> MealType.MEAL_TYPE_BREAKFAST
                "lunch" -> MealType.MEAL_TYPE_LUNCH
                "dinner" -> MealType.MEAL_TYPE_DINNER
                "snack" -> MealType.MEAL_TYPE_SNACK
                else -> MealType.MEAL_TYPE_UNKNOWN
            },
        )
    }
    fun status() = HealthConnectClient.getSdkStatus(context, PROVIDER)
    private fun client() = HealthConnectClient.getOrCreate(context, PROVIDER)
    suspend fun permitted() = status() == HealthConnectClient.SDK_AVAILABLE && client().permissionController.getGrantedPermissions().containsAll(permissions)
    suspend fun latestRevision(id: String) = history.latestRevision(id)
    private suspend fun ownRecord(id: String): StoredFood? = suspendCancellableCoroutine { continuation ->
        val stableId = "foodbridge:" + DeepLinkParser.sha256(id)
        val request = ReadRecordsRequestUsingIds.Builder(NativeNutrition::class.java).addClientRecordId(stableId).build()
        try {
            context.getSystemService(HealthConnectManager::class.java).readRecords(request, context.mainExecutor,
                object : OutcomeReceiver<ReadRecordsResponse<NativeNutrition>, HealthConnectException> {
                    override fun onResult(result: ReadRecordsResponse<NativeNutrition>) {
                        if (!continuation.isActive) return
                        val mapped = runCatching {
                            check(result.records.size <= 1)
                            result.records.singleOrNull()?.let {
                                check(it.metadata.clientRecordId == stableId && it.metadata.dataOrigin.packageName == context.packageName)
                                StoredFood(it.metadata.id, it.metadata.clientRecordVersion, it.mealName)
                            }
                        }
                        mapped.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
                    }
                    override fun onError(error: HealthConnectException) { if (continuation.isActive) continuation.resumeWithException(if (error.errorCode == HealthConnectException.ERROR_SECURITY) MissingPermission() else error) }
                })
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }
    suspend fun inspect(id: String): StoredFood? { if (!permitted()) throw MissingPermission(); return ownRecord(id) }
    private val gateway = object : RecordGateway {
        override suspend fun lookup(id: String) = ownRecord(id)
        override suspend fun upsert(food: FoodPayload) { client().insertRecords(listOf(record(food))) }
        override suspend fun delete(recordId: String) { client().deleteRecords(NutritionRecord::class, recordIdsList = listOf(recordId), clientRecordIdsList = emptyList()) }
    }
    private val coordinator = SaveCoordinator(history, ::permitted, gateway)
    suspend fun save(food: FoodPayload) = coordinator.save(food)
    suspend fun execute(request: FoodRequest, confirmedDeletes: Boolean, onProgress: (List<OperationOutcome>) -> Unit): List<OperationOutcome> {
        val outcomes = mutableListOf<OperationOutcome>()
        for (operation in request.operations) {
            val outcome = try {
                val result = when (operation) {
                    is FoodOperation.Upsert -> coordinator.save(operation.food)
                    is FoodOperation.Delete -> coordinator.delete(operation.id, operation.revision, confirmedDeletes)
                }
                OperationOutcome(operation, result)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { OperationOutcome(operation, permissionRequired = e is MissingPermission || e is SecurityException, error = when(e) {
                is MissingPermission, is SecurityException -> "Нужно разрешение на запись питания."
                is DeleteConfirmationRequired -> "Удаление требует подтверждения."
                else -> "Не удалось выполнить. Повторите попытку."
            }) }
            outcomes += outcome
            onProgress(outcomes.toList())
        }
        return outcomes
    }
}
