package com.example.onequote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.onequote.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "onequote_settings")

class AppSettingsStore(private val context: Context) {
    private val settingsKey = stringPreferencesKey("app_settings_json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { preferences ->
        val raw = preferences[settingsKey]
        if (raw.isNullOrBlank()) {
            AppSettings()
        } else {
            runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
                .getOrDefault(AppSettings())
        }
    }

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { preferences ->
            val current = preferences[settingsKey]
                ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
                ?: AppSettings()
            val updated = transform(current)
            preferences[settingsKey] = json.encodeToString(updated)
        }
    }
}

