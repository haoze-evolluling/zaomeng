package top.wkbin.zaomeng.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

data class AppPreferences(
    val defaultCharacters: String = "",
    val autoDistill: Boolean = true,
    val lastRunId: String = "",
    val lastSessionId: String = "",
)

class AppPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { values ->
            AppPreferences(
                defaultCharacters = values[DEFAULT_CHARACTERS].orEmpty(),
                autoDistill = values[AUTO_DISTILL] ?: true,
                lastRunId = values[LAST_RUN_ID].orEmpty(),
                lastSessionId = values[LAST_SESSION_ID].orEmpty(),
            )
        }

    suspend fun saveImportDefaults(characters: String, autoDistill: Boolean) {
        dataStore.edit { values ->
            values[DEFAULT_CHARACTERS] = characters
            values[AUTO_DISTILL] = autoDistill
        }
    }

    suspend fun rememberRun(runId: String) {
        val normalizedRunId = runId.trim()
        dataStore.edit { values ->
            if (normalizedRunId.isBlank()) {
                values.remove(LAST_RUN_ID)
            } else {
                values[LAST_RUN_ID] = normalizedRunId
            }
            values.remove(LAST_SESSION_ID)
        }
    }

    suspend fun rememberSession(runId: String, sessionId: String) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        if (normalizedRunId.isBlank() || normalizedSessionId.isBlank()) return
        dataStore.edit { values ->
            values[LAST_RUN_ID] = normalizedRunId
            values[LAST_SESSION_ID] = normalizedSessionId
        }
    }

    suspend fun clearLastSession() {
        dataStore.edit { it.remove(LAST_SESSION_ID) }
    }

    suspend fun clearLastLocation() {
        dataStore.edit { values ->
            values.remove(LAST_RUN_ID)
            values.remove(LAST_SESSION_ID)
        }
    }

    suspend fun forgetRun(runId: String) {
        val normalizedRunId = runId.trim()
        dataStore.edit { values ->
            if (values[LAST_RUN_ID] == normalizedRunId) {
                values.remove(LAST_RUN_ID)
                values.remove(LAST_SESSION_ID)
            }
        }
    }

    suspend fun forgetSession(runId: String, sessionId: String) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        dataStore.edit { values ->
            if (
                values[LAST_RUN_ID] == normalizedRunId &&
                values[LAST_SESSION_ID] == normalizedSessionId
            ) {
                values.remove(LAST_SESSION_ID)
            }
        }
    }

    private companion object {
        val DEFAULT_CHARACTERS = stringPreferencesKey("default_characters")
        val AUTO_DISTILL = booleanPreferencesKey("auto_distill")
        val LAST_RUN_ID = stringPreferencesKey("last_run_id")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
    }
}
