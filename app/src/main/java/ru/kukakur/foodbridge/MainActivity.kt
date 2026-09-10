package ru.kukakur.foodbridge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val model: FoodViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        if (savedInstanceState == null || model.state.value.status == "home") model.open(intent.action, intent.dataString)
        setContent {
            FoodTheme {
                val state by model.state.collectAsStateWithLifecycle()
                val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { model.refresh() }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(state.requestId) { showSettings = false }
                LaunchedEffect(state.requestId, state.status, state.closeAfterSave) {
                    if (state.status in listOf("success", "duplicate") && state.closeAfterSave) { delay(1200); finish() }
                }
                key(state.requestId) { FoodScreen(state, showSettings,
                    onSettings = { showSettings = !showSettings }, onAuto = model::setAuto,
                    onSave = { model.save(state.requestId) }, onPermission = { permissions.launch(HealthConnectRepository.permissions) },
                    onHealth = ::openHealth, onClose = { finish() }) }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); model.open(intent.action, intent.dataString) }
    override fun onResume() { super.onResume(); model.refresh() }
    private fun openHealth() {
        val action = Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        try { startActivity(action) } catch (_: android.content.ActivityNotFoundException) {
            try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: android.content.ActivityNotFoundException) { }
        }
    }
}

@Composable fun FoodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
}
private fun number(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else String.format(Locale.US, "%.1f", value)
private val mealNames = mapOf("breakfast" to "Завтрак", "lunch" to "Обед", "dinner" to "Ужин", "snack" to "Перекус", "other" to "Приём пищи")

@Composable fun FoodScreen(
    state: ScreenState, settings: Boolean = false, onSettings: () -> Unit = {}, onAuto: (Boolean) -> Unit = {},
    onSave: () -> Unit = {}, onPermission: () -> Unit = {}, onHealth: () -> Unit = {}, onClose: () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("FoodBridge", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onSettings, enabled = state.status != "saving") { Text(if (settings) "Назад" else "Настройки") }
            }
            if (settings) {
                Text("Настройки", style = MaterialTheme.typography.headlineMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Добавлять сразу", style = MaterialTheme.typography.titleMedium)
                        Text("Сохранять блюда и исправления при открытии ссылки и возвращаться назад.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = state.autoAdd, onCheckedChange = onAuto)
                }
                Text("Включайте только если доверяете ссылкам. Другая программа тоже может открыть FoodBridge. Удаление и необычные значения всегда требуют подтверждения.")
                TextButton(onClick = onHealth) { Text("Открыть Health Connect") }
                PrivacyText()
            } else {
                if (state.request != null && (state.request.operations.size > 1 || state.request.hasDeletes)) {
                    RequestSummary(state, onSave)
                } else when (state.status) {
                    "invalid" -> { Text("Не удалось прочитать данные блюда.", style = MaterialTheme.typography.headlineSmall); Text("Откройте новую ссылку FoodBridge с корректными данными.") }
                    "duplicate" -> { Text("Эта ссылка уже обработана.", style = MaterialTheme.typography.headlineSmall); state.food?.let { Text(it.name); if (it.revision > 1) Text("Эта или более новая версия уже сохранена. Изменения не повторялись.") } }
                    "success" -> { Text(if (state.updated) "✓ Обновлено" else "✓ Добавлено", style = MaterialTheme.typography.headlineLarge); state.food?.let { FoodSummary(it) } }
                    "home" -> { Text("Питание по ссылке", style = MaterialTheme.typography.headlineLarge); Text("Откройте ссылку на блюдо из ChatGPT, проверьте данные и добавьте их в Health Connect.") }
                    else -> state.food?.let { food ->
                        Text(food.name, style = MaterialTheme.typography.headlineMedium)
                        if (food.revision > 1) Text("Исправление № ${food.revision}. Эти данные заменят ранее сохранённое блюдо.")
                        FoodSummary(food)
                        Text("${mealNames[food.meal]} · ${food.time.format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru")))}\nUTC${food.time.offset}")
                        if (food.suspicious) Text("Необычно большие значения. Проверьте данные перед добавлением.", color = MaterialTheme.colorScheme.error)
                        if (state.status == "saving") { CircularProgressIndicator(); Text("Сохраняем…") }
                        else if (food.time.toInstant().isAfter(java.time.Instant.now())) Text("Время блюда ещё не наступило. Исправьте дату в ссылке.", color = MaterialTheme.colorScheme.error)
                        else if (state.permitted) Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) { Text(if (food.revision > 1) "Обновить в Health Connect" else "Добавить в Health Connect") }
                    }
                }
                if (state.status in listOf("home", "preview", "partial")) {
                    if (state.available != HealthConnectClient.SDK_AVAILABLE) {
                        Text(if (state.available == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) "Health Connect требует установки или обновления." else "Health Connect недоступен на этом устройстве.")
                        OutlinedButton(onClick = onHealth) { Text("Открыть настройки") }
                    } else if (!state.permitted) {
                        Text("Разрешите FoodBridge записывать питание в Health Connect. Отдельное разрешение на чтение не нужно.")
                        Button(onClick = onPermission, modifier = Modifier.fillMaxWidth()) { Text("Разрешить запись питания") }
                        TextButton(onClick = onHealth) { Text("Настройки Health Connect") }
                    }
                }
                if (state.status == "partial" && state.food != null) {
                    state.outcomes.forEach { it.error?.let { error -> Text(error, color = MaterialTheme.colorScheme.error) } }
                }
                state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (state.status != "saving") OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Закрыть") }
            }
        }
    }
}


@Composable private fun RequestSummary(state: ScreenState, onSave: () -> Unit) {
    val request = state.request ?: return
    Text(when (state.status) {
        "success" -> "✓ Готово"
        "partial" -> "Выполнено частично"
        "saving" -> "Сохраняем…"
        else -> if (request.operations.size == 1) "Удалить блюдо" else "Блюда: ${request.operations.size}"
    }, style = MaterialTheme.typography.headlineMedium)
    request.operations.forEach { operation ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (operation) {
                    is FoodOperation.Upsert -> {
                        Text(operation.food.name, style = MaterialTheme.typography.titleLarge)
                        Text("${number(operation.food.kcal)} kcal · ${if (operation.revision > 1) "Исправление № ${operation.revision}" else "Добавление"}")
                        listOf("Белки" to operation.food.p, "Жиры" to operation.food.f, "Углеводы" to operation.food.c, "Клетчатка" to operation.food.fiber, "Сахар" to operation.food.sugar, "Насыщенные жиры" to operation.food.saturatedFat).forEach { (label, value) ->
                            value?.let { NutrientRow(label, "${number(it)} g") }
                        }
                        operation.food.sodiumMg?.let { NutrientRow("Натрий", "${number(it)} mg") }
                        Text(operation.food.time.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm XXX", Locale.forLanguageTag("ru"))))
                    }
                    is FoodOperation.Delete -> {
                        Text(state.existing[operation.id]?.name ?: operation.id, style = MaterialTheme.typography.titleLarge)
                        Text("Удаление · исправление № ${operation.revision}", color = MaterialTheme.colorScheme.error)
                        Text("ID: ${operation.id}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.outcomes.find { it.operation.id == operation.id }?.let { outcome ->
                    Text(outcome.error ?: when (outcome.result) {
                        SaveResult.ADDED -> "✓ Добавлено"
                        SaveResult.UPDATED -> "✓ Обновлено"
                        SaveResult.DELETED -> "✓ Удалено"
                        SaveResult.ALREADY_ABSENT -> "Уже отсутствует"
                        SaveResult.DUPLICATE -> "Эта или более новая правка уже применена"
                        null -> "Ожидание"
                    }, color = if (outcome.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
    if (state.status == "saving") { CircularProgressIndicator(); Text("Готово: ${state.outcomes.size} из ${request.operations.size}") }
    if (state.status in listOf("preview", "partial")) {
        if (request.hasDeletes) Text("Удалённые блюда исчезнут из Health Connect. Проверьте список перед подтверждением.")
        if (request.suspicious) Text("Необычно большие значения. Проверьте данные.", color = MaterialTheme.colorScheme.error)
        if (request.operations.size > 1) Text("Действия выполняются по очереди. При ошибке можно повторить только незавершённые.", style = MaterialTheme.typography.bodySmall)
        if (state.permitted) Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.status == "partial") "Повторить незавершённые" else if (request.hasDeletes) "Подтвердить изменения и удаление" else "Сохранить всё в Health Connect")
        }
    }
}

@Composable private fun FoodSummary(food: FoodPayload) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${number(food.kcal)} kcal", style = MaterialTheme.typography.displaySmall)
            listOf("Белки" to food.p, "Жиры" to food.f, "Углеводы" to food.c, "Клетчатка" to food.fiber, "Сахар" to food.sugar, "Насыщенные жиры" to food.saturatedFat).forEach { (label, value) ->
                if (value != null) NutrientRow(label, "${number(value)} g")
            }
            food.sodiumMg?.let { NutrientRow("Натрий", "${number(it)} mg") }
        }
    }
}
@Composable private fun NutrientRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(label, Modifier.weight(1f)); Text(value) }
}
@Composable fun PrivacyText() {
    Text("FoodBridge не использует аккаунты. Данные блюда из ссылки обрабатываются на устройстве и записываются в Android Health Connect. Разработчик их не получает. Для исправлений и удаления приложение проверяет только свои записи в Health Connect. Локально хранятся настройка и номера последних 1000 операций без названий блюд. Доступ других приложений к питанию вы управляете в Health Connect.")
}
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        setContent { FoodTheme { Surface(Modifier.fillMaxSize()) { Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Конфиденциальность", style = MaterialTheme.typography.headlineMedium); PrivacyText(); Button(onClick = { finish() }) { Text("Закрыть") }
        } } } }
    }
}
