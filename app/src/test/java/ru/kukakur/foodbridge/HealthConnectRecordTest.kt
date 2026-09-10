package ru.kukakur.foodbridge

import androidx.health.connect.client.records.MealType
import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class HealthConnectRecordTest {
    private val food = FoodPayload("stable-meal-id", "Овсянка 🥣", 432.5,
        OffsetDateTime.parse("2026-09-11T12:34:56.123-04:30"),
        p = 25.5, f = 10.25, c = 60.5, fiber = 5.5, sugar = 4.25, saturatedFat = 2.5, sodiumMg = 725.0, meal = "lunch")

    @Test fun mapsAllNutrientsWithCorrectUnitsAndPreservesOffsetAndInstant() {
        val record = HealthConnectRepository.record(food)
        assertEquals(food.name, record.name)
        assertEquals(432.5, record.energy!!.inKilocalories, 0.000001)
        assertEquals(25.5, record.protein!!.inGrams, 0.000001)
        assertEquals(10.25, record.totalFat!!.inGrams, 0.000001)
        assertEquals(60.5, record.totalCarbohydrate!!.inGrams, 0.000001)
        assertEquals(5.5, record.dietaryFiber!!.inGrams, 0.000001)
        assertEquals(4.25, record.sugar!!.inGrams, 0.000001)
        assertEquals(2.5, record.saturatedFat!!.inGrams, 0.000001)
        assertEquals(725.0, record.sodium!!.inMilligrams, 0.000001)
        assertEquals(0.725, record.sodium!!.inGrams, 0.000001)
        assertEquals(food.time.toInstant(), record.startTime)
        assertEquals(food.time.toInstant().plusSeconds(60), record.endTime)
        assertEquals(food.time.offset, record.startZoneOffset)
        assertEquals(food.time.offset, record.endZoneOffset)
    }

    @Test fun missingOptionalNutrientsStayNullAndExplicitZeroIsKept() {
        val bare = FoodPayload("minimal", "Apple", 0.0, food.time)
        val record = HealthConnectRepository.record(bare)
        assertNull(record.protein); assertNull(record.totalFat); assertNull(record.totalCarbohydrate)
        assertNull(record.dietaryFiber); assertNull(record.sugar); assertNull(record.saturatedFat); assertNull(record.sodium)
        assertEquals(0.0, record.energy!!.inKilocalories, 0.0)
        assertEquals(0.0, HealthConnectRepository.record(bare.copy(p = 0.0)).protein!!.inGrams, 0.0)
    }

    @Test fun mapsAllSupportedMealTypes() {
        mapOf("breakfast" to MealType.MEAL_TYPE_BREAKFAST, "lunch" to MealType.MEAL_TYPE_LUNCH,
            "dinner" to MealType.MEAL_TYPE_DINNER, "snack" to MealType.MEAL_TYPE_SNACK, "other" to MealType.MEAL_TYPE_UNKNOWN)
            .forEach { (meal, expected) -> assertEquals(expected, HealthConnectRepository.record(food.copy(meal = meal)).mealType) }
    }

    @Test fun editKeepsProviderIdentityAndMapsRevisionToClientRecordVersion() {
        val original = HealthConnectRepository.record(food)
        val edit = HealthConnectRepository.record(food.copy(name = "Updated meal", kcal = 500.0, revision = 7))
        assertEquals(original.metadata.clientRecordId, edit.metadata.clientRecordId)
        assertEquals(1L, original.metadata.clientRecordVersion)
        assertEquals(7L, edit.metadata.clientRecordVersion)
        assertEquals("Updated meal", edit.name)
        assertEquals(500.0, edit.energy!!.inKilocalories, 0.000001)
    }
    @Test fun clientIdentityIsStableOpaqueAndVersionedForRetries() {
        val metadata = HealthConnectRepository.record(food).metadata
        assertEquals("foodbridge:" + DeepLinkParser.sha256(food.id), metadata.clientRecordId)
        assertEquals(1L, metadata.clientRecordVersion)
        assertEquals(metadata.clientRecordId, HealthConnectRepository.record(food.copy(kcal = 100.0)).metadata.clientRecordId)
        assertNotEquals(metadata.clientRecordId, HealthConnectRepository.record(food.copy(id = "different")).metadata.clientRecordId)
    }
}
