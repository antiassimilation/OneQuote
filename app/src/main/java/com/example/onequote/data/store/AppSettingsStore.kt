package com.example.onequote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.onequote.data.model.AppSettings
import com.example.onequote.data.model.BuiltinSources
import com.example.onequote.data.util.StyleParsers
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
        val parsed = if (raw.isNullOrBlank()) {
            AppSettings()
        } else {
            runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
                .getOrDefault(AppSettings())
        }
        normalizeSettings(parsed)
    }

    suspend fun getSettings(): AppSettings = settingsFlow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { preferences ->
            val current = preferences[settingsKey]
                ?.let { runCatching { json.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
                ?: AppSettings()
            val updated = normalizeSettings(transform(normalizeSettings(current)))
            preferences[settingsKey] = json.encodeToString(updated)
        }
    }

    /**
     * 统一做配置兜底：
     * - 确保内置 hitokoto 来源始终存在；
     * - 过滤非法分类代码，避免构建无效请求；
     * - 保持旧版本数据可平滑迁移。
     */
    private fun normalizeSettings(input: AppSettings): AppSettings {
        val normalizedSources = input.sources
            .map { source ->
                if (source.isBuiltin && source.id == BuiltinSources.HITOKOTO_ID) {
                    val filteredCodes = source.selectedTypeCodes
                        .map(String::trim)
                        .map(String::lowercase)
                        .filter { it in BuiltinSources.allHitokotoTypeCodes }
                        .distinct()
                    source.copy(selectedTypeCodes = filteredCodes)
                } else {
                    source
                }
            }
            .toMutableList()

        val hasBuiltin = normalizedSources.any { it.id == BuiltinSources.HITOKOTO_ID }
        if (!hasBuiltin) {
            normalizedSources += BuiltinSources.createDefaultHitokotoSource()
        }

        val normalizedStyle = input.style.copy(
            quoteFontSp = StyleParsers.clampQuoteFontSp(input.style.quoteFontSp),
            authorFontSp = StyleParsers.clampAuthorFontSp(input.style.authorFontSp)
        )

        return input.copy(
            sources = normalizedSources,
            style = normalizedStyle
        )
    }
}

