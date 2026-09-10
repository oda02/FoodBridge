package ru.kukakur.foodbridge

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.foodSettings by preferencesDataStore(name = "foodbridge")
interface HistoryStore {
    suspend fun latestRevision(id: String): Long?
    suspend fun remember(id: String, revision: Long)
}
class Settings(context: Context) : HistoryStore {
    private val store = context.applicationContext.foodSettings
    private val autoKey = booleanPreferencesKey("auto_add")
    private val idsKey = stringPreferencesKey("recent_ids_v1")
    val autoAdd = store.data.map { it[autoKey] ?: false }
    suspend fun setAutoAdd(enabled: Boolean) { store.edit { it[autoKey] = enabled } }
    private fun key(id: String) = DeepLinkParser.sha256(id)
    override suspend fun latestRevision(id: String): Long? = RevisionHistory.latest(store.data.first()[idsKey], key(id))
    override suspend fun remember(id: String, revision: Long) {
        store.edit {
            it[idsKey] = RevisionHistory.remember(it[idsKey], key(id), revision)
        }
    }
}
