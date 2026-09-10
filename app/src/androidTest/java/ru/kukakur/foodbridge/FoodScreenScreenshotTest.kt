package ru.kukakur.foodbridge

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.health.connect.client.HealthConnectClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.OffsetDateTime

/**
 * Isolated UI fixtures only: callbacks are counters and no repository, main activity,
 * permission request, deep link, or Health Connect insertion is invoked.
 * Run on an emulator: connectedDebugAndroidTest.
 * PNGs: targetContext.getExternalFilesDir(null)/ui-review/<theme>-<state>.png.
 */
@RunWith(AndroidJUnit4::class)
class FoodScreenScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val food = FoodPayload(
        id = "ui-fixture-20200115", name = "Овсянка с ягодами 🥣", kcal = 352.5,
        time = OffsetDateTime.parse("2020-01-15T09:00:00+03:00"),
        p = 12.4, f = 8.1, c = 54.0, fiber = 7.2, sugar = 9.6,
        saturatedFat = 1.8, sodiumMg = 125.0, meal = "breakfast",
    )
    private val preview = ScreenState(food = food, status = "preview",
        available = HealthConnectClient.SDK_AVAILABLE, permitted = true)

    private data class Fixture(val label: String, val state: ScreenState, val settings: Boolean = false)

    @Test fun renderSixStatesInLightAndDarkAndCaptureScreenshots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "ui-review")
        assertTrue("Cannot create screenshot directory: $output", output.isDirectory || output.mkdirs())
        val fixtures = listOf(
            Fixture("preview", preview),
            Fixture("missing-permission", preview.copy(permitted = false)),
            Fixture("success", preview.copy(status = "success")),
            Fixture("duplicate", preview.copy(status = "duplicate")),
            Fixture("invalid", preview.copy(food = null, status = "invalid")),
            Fixture("settings", preview.copy(autoAdd = false), settings = true),
        )
        var fixture by mutableStateOf(fixtures.first())
        var dark by mutableStateOf(false)
        var saveCalls = 0
        var permissionCalls = 0
        var closeCalls = 0
        var settingsCalls = 0
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                // A fresh scroll state for each fixture makes the first image deterministic.
                key(dark, fixture.label) {
                    FoodTheme {
                        FoodScreen(fixture.state, settings = fixture.settings,
                            onSave = { saveCalls++ }, onPermission = { permissionCalls++ },
                            onClose = { closeCalls++ }, onSettings = { settingsCalls++ })
                    }
                }
            }
        }
        var screenshots = 0
        for (night in listOf(false, true)) {
            for (next in fixtures) {
                compose.runOnIdle { dark = night; fixture = next }
                compose.waitForIdle()
                val theme = if (night) "dark" else "light"
                compose.onNodeWithText("FoodBridge").assertIsDisplayed()
                when (next.label) {
                    "preview", "missing-permission" -> compose.onNodeWithText(food.name).assertIsDisplayed()
                    "success" -> compose.onNodeWithText("✓ Добавлено").assertIsDisplayed()
                    "duplicate" -> compose.onNodeWithText("Эта ссылка уже обработана.").assertIsDisplayed()
                    "invalid" -> compose.onNodeWithText("Не удалось прочитать данные блюда.").assertIsDisplayed()
                    "settings" -> compose.onNodeWithText("Добавлять сразу").assertIsDisplayed()
                }
                capture(File(output, "$theme-${next.label}.png"))
                screenshots++
                when (next.label) {
                    "preview" -> {
                        compose.onNodeWithText("Добавить в Health Connect").performScrollTo().assertIsDisplayed().assertIsEnabled()
                        capture(File(output, "$theme-preview-actions.png"))
                        compose.onNodeWithText("Добавить в Health Connect").performClick()
                    }
                    "missing-permission" -> {
                        compose.onNodeWithText("Добавить в Health Connect").assertDoesNotExist()
                        compose.onNodeWithText("Разрешить запись питания").performScrollTo().assertIsDisplayed().assertIsEnabled()
                        capture(File(output, "$theme-missing-permission-actions.png"))
                        compose.onNodeWithText("Разрешить запись питания").performClick()
                    }
                    "settings" -> compose.onNodeWithText("Назад").performClick()
                    else -> {
                        compose.onNodeWithText("Добавить в Health Connect").assertDoesNotExist()
                        compose.onNodeWithText("Закрыть").performScrollTo().assertIsDisplayed().performClick()
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(2, saveCalls)
            assertEquals(2, permissionCalls)
            assertEquals(2, settingsCalls)
            assertEquals(6, closeCalls)
        }
        assertEquals(12, screenshots)
        assertTrue("All 12 state captures must exist", fixtures.all { fixture ->
            listOf("light", "dark").all { theme -> File(output, "$theme-${fixture.label}.png").length() > 0 }
        })
    }

    @Test fun renderEditPreviewAndUpdatedSuccessInLightAndDarkWithoutProviderAccess() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "ui-review")
        assertTrue(output.isDirectory || output.mkdirs())
        val edit = food.copy(revision = 2, kcal = 370.0, p = 14.0)
        var dark by mutableStateOf(false)
        var updated by mutableStateOf(false)
        var saveCalls = 0
        var closeCalls = 0
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                key(dark, updated) {
                    FoodTheme {
                        FoodScreen(preview.copy(food = edit, status = if (updated) "success" else "preview", updated = updated),
                            onSave = { saveCalls++ }, onClose = { closeCalls++ })
                    }
                }
            }
        }
        for (night in listOf(false, true)) {
            val theme = if (night) "dark" else "light"
            compose.runOnIdle { dark = night; updated = false }
            compose.waitForIdle()
            compose.onNodeWithText(food.name).assertIsDisplayed()
            compose.onNodeWithText("Добавить в Health Connect").assertDoesNotExist()
            compose.onNodeWithText("Исправление № 2. Эти данные заменят ранее сохранённое блюдо.").assertExists()
            capture(File(output, "$theme-update-preview.png"))
            compose.onNodeWithText("Обновить в Health Connect").performScrollTo().assertIsDisplayed().assertIsEnabled()
            capture(File(output, "$theme-update-preview-actions.png"))
            compose.onNodeWithText("Обновить в Health Connect").performClick()
            compose.runOnIdle { updated = true }
            compose.waitForIdle()
            compose.onNodeWithText("✓ Обновлено").assertIsDisplayed()
            compose.onNodeWithText("✓ Добавлено").assertDoesNotExist()
            compose.onNodeWithText("Обновить в Health Connect").assertDoesNotExist()
            capture(File(output, "$theme-update-success.png"))
            compose.onNodeWithText("Закрыть").performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(2, saveCalls)
            assertEquals(2, closeCalls)
        }
        listOf("light", "dark").forEach { theme ->
            listOf("update-preview", "update-preview-actions", "update-success").forEach { label ->
                assertTrue(File(output, "$theme-$label.png").length() > 0)
            }
        }
    }
    @Test fun renderBatchDeleteAndPartialStatesWithExplicitCallbacksInLightAndDark() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(requireNotNull(context.getExternalFilesDir(null)), "ui-review")
        assertTrue(output.isDirectory || output.mkdirs())
        val second = food.copy(id = "ui-second-meal", name = "Греческий йогурт с фруктами", kcal = 210.0)
        val upsert = FoodOperation.Upsert(second)
        val deletion = FoodOperation.Delete(food.id, 2)
        val batch = FoodRequest(listOf(FoodOperation.Upsert(food), upsert))
        val deleteOnly = FoodRequest(listOf(deletion))
        val mixed = FoodRequest(listOf(upsert, deletion))
        val existing = mapOf(food.id to StoredFood("fixture-record-id", 1, food.name))
        val requestState = preview.copy(food = null, existing = existing, autoAdd = true)
        val fixtures = listOf(
            Fixture("batch-preview", requestState.copy(request = batch)),
            Fixture("delete-confirmation", requestState.copy(request = deleteOnly)),
            Fixture("mixed-confirmation", requestState.copy(request = mixed)),
            Fixture("batch-partial", requestState.copy(request = mixed, status = "partial", outcomes = listOf(
                OperationOutcome(upsert, SaveResult.ADDED), OperationOutcome(deletion, error = "Не удалось выполнить. Повторите попытку.")))),
            Fixture("batch-success", requestState.copy(request = mixed, status = "success", outcomes = listOf(
                OperationOutcome(upsert, SaveResult.ADDED), OperationOutcome(deletion, SaveResult.DELETED)))),
            Fixture("delete-already-absent", requestState.copy(request = deleteOnly, status = "success",
                outcomes = listOf(OperationOutcome(deletion, SaveResult.ALREADY_ABSENT)))),
            Fixture("delete-missing-permission", requestState.copy(request = deleteOnly, permitted = false)),
        )
        var fixture by mutableStateOf(fixtures.first())
        var dark by mutableStateOf(false)
        var saveCalls = 0
        var closeCalls = 0
        var permissionCalls = 0
        compose.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                key(dark, fixture.label) {
                    FoodTheme {
                        FoodScreen(fixture.state, onSave = { saveCalls++ }, onClose = { closeCalls++ },
                            onPermission = { permissionCalls++ })
                    }
                }
            }
        }
        var expectedSaves = 0
        for (night in listOf(false, true)) {
            for (next in fixtures) {
                compose.runOnIdle { dark = night; fixture = next }
                compose.waitForIdle()
                // Rendering a preview, including auto-add-enabled deletion fixtures,
                // must not call the save callback without a deliberate button click.
                compose.runOnIdle { assertEquals(expectedSaves, saveCalls) }
                compose.onNodeWithText("FoodBridge").assertIsDisplayed()
                val theme = if (night) "dark" else "light"
                capture(File(output, "$theme-${next.label}.png"))
                val action = when (next.label) {
                    "batch-preview" -> "Сохранить всё в Health Connect"
                    "delete-confirmation", "mixed-confirmation" -> "Подтвердить изменения и удаление"
                    "batch-partial" -> "Повторить незавершённые"
                    else -> null
                }
                if (next.state.request?.hasDeletes == true && next.state.status != "success") {
                    compose.onNodeWithText("Удалённые блюда исчезнут из Health Connect. Проверьте список перед подтверждением.").assertExists()
                }
                when (next.label) {
                    "delete-confirmation" -> compose.onNodeWithText(food.name).assertExists()
                    "batch-partial" -> {
                        compose.onNodeWithText("Выполнено частично").assertExists()
                        compose.onNodeWithText("✓ Добавлено").assertExists()
                        compose.onNodeWithText("Не удалось выполнить. Повторите попытку.").assertExists()
                    }
                    "batch-success" -> {
                        compose.onNodeWithText("✓ Готово").assertExists()
                        compose.onNodeWithText("✓ Удалено").assertExists()
                    }
                    "delete-already-absent" -> compose.onNodeWithText("Уже отсутствует").assertExists()
                }
                if (action != null) {
                    compose.onNodeWithText(action).performScrollTo().assertIsDisplayed().assertIsEnabled()
                    capture(File(output, "$theme-${next.label}-actions.png"))
                    compose.onNodeWithText(action).performClick()
                    expectedSaves++
                    compose.runOnIdle { assertEquals(expectedSaves, saveCalls) }
                } else if (next.label == "delete-missing-permission") {
                    compose.onNodeWithText("Подтвердить изменения и удаление").assertDoesNotExist()
                    compose.onNodeWithText("Разрешить запись питания").performScrollTo().assertIsDisplayed().performClick()
                } else {
                    compose.onNodeWithText("Подтвердить изменения и удаление").assertDoesNotExist()
                    compose.onNodeWithText("Повторить незавершённые").assertDoesNotExist()
                    compose.onNodeWithText("Закрыть").performScrollTo().performClick()
                }
            }
        }
        compose.runOnIdle {
            assertEquals(8, saveCalls); assertEquals(4, closeCalls); assertEquals(2, permissionCalls)
        }
        assertTrue(fixtures.all { next -> listOf("light", "dark").all { theme ->
            File(output, "$theme-${next.label}.png").length() > 0
        } })
    }

    private fun capture(file: File) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        file.outputStream().use { stream -> assertTrue("PNG compression failed", bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
    }
}
