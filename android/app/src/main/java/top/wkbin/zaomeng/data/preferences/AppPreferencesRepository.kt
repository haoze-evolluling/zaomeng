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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

enum class ChatFontSize(
    val storageValue: String,
    val scale: Float,
) {
    SMALL("small", 0.9f),
    STANDARD("standard", 1f),
    LARGE("large", 1.15f),
    ;

    companion object {
        fun fromStorageValue(value: String?): ChatFontSize =
            values().firstOrNull { it.storageValue == value } ?: STANDARD
    }
}

enum class ThemeMode(val storageValue: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        fun fromStorageValue(value: String?): ThemeMode =
            values().firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

data class ChatDisplayPreferences(
    val fontSize: ChatFontSize = ChatFontSize.STANDARD,
    val compactMode: Boolean = false,
)

data class AppPreferences(
    val defaultCharacters: String = "",
    val autoDistill: Boolean = true,
    val restoreLastLocation: Boolean = true,
    val lastRunId: String = "",
    val lastSessionId: String = "",
    val chatDisplay: ChatDisplayPreferences = ChatDisplayPreferences(),
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
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
                restoreLastLocation = values[RESTORE_LAST_LOCATION] ?: true,
                lastRunId = values[LAST_RUN_ID].orEmpty(),
                lastSessionId = values[LAST_SESSION_ID].orEmpty(),
                chatDisplay = ChatDisplayPreferences(
                    fontSize = ChatFontSize.fromStorageValue(values[CHAT_FONT_SIZE]),
                    compactMode = values[CHAT_COMPACT_MODE] ?: false,
                ),
                themeMode = ThemeMode.fromStorageValue(values[THEME_MODE]),
            )
        }

    val chatDisplayPreferences: Flow<ChatDisplayPreferences> = preferences
        .map { preferences -> preferences.chatDisplay }
        .distinctUntilChanged()

    val themeMode: Flow<ThemeMode> = preferences
        .map { preferences -> preferences.themeMode }
        .distinctUntilChanged()

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

    suspend fun setChatFontSize(fontSize: ChatFontSize) {
        dataStore.edit { values -> values[CHAT_FONT_SIZE] = fontSize.storageValue }
    }

    suspend fun setCompactChatMode(enabled: Boolean) {
        dataStore.edit { values -> values[CHAT_COMPACT_MODE] = enabled }
    }

    suspend fun saveChatDisplayPreferences(preferences: ChatDisplayPreferences) {
        dataStore.edit { values ->
            values[CHAT_FONT_SIZE] = preferences.fontSize.storageValue
            values[CHAT_COMPACT_MODE] = preferences.compactMode
        }
    }

    suspend fun setRestoreLastLocation(enabled: Boolean) {
        dataStore.edit { values -> values[RESTORE_LAST_LOCATION] = enabled }
    }

    suspend fun setThemeMode(themeMode: ThemeMode) {
        dataStore.edit { values -> values[THEME_MODE] = themeMode.storageValue }
    }

    private companion object {
        val DEFAULT_CHARACTERS = stringPreferencesKey("default_characters")
        val AUTO_DISTILL = booleanPreferencesKey("auto_distill")
        val RESTORE_LAST_LOCATION = booleanPreferencesKey("restore_last_location")
        val LAST_RUN_ID = stringPreferencesKey("last_run_id")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        val CHAT_FONT_SIZE = stringPreferencesKey("chat_font_size")
        val CHAT_COMPACT_MODE = booleanPreferencesKey("chat_compact_mode")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }
}

