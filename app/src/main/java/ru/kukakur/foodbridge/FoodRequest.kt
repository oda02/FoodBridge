package ru.kukakur.foodbridge

sealed interface FoodOperation {
    val id: String
    val revision: Long

    data class Upsert(val food: FoodPayload) : FoodOperation {
        override val id: String get() = food.id
        override val revision: Long get() = food.revision
    }

    data class Delete(override val id: String, override val revision: Long) : FoodOperation
}

data class FoodRequest(val operations: List<FoodOperation>) {
    val hasDeletes: Boolean get() = operations.any { it is FoodOperation.Delete }
    val suspicious: Boolean get() = operations.any { it is FoodOperation.Upsert && it.food.suspicious }
}
