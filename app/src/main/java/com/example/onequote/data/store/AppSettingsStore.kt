package com.example.onequote.data.store

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.onequote.data.model.AppSettings
import com.example.onequote.data.model.BuiltinSources
import com.example.onequote.data.model.QuoteSourceConfig
import com.example.onequote.data.model.QuoteSourceKind
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
            .map(::normalizeSource)
            .toMutableList()

        val hasBuiltin = normalizedSources.any { it.id == BuiltinSources.HITOKOTO_ID }
        if (!hasBuiltin) {
            normalizedSources += BuiltinSources.createDefaultHitokotoSource()
        }

        val hasFavorites = normalizedSources.any { it.id == BuiltinSources.FAVORITES_ID }
        if (!hasFavorites) {
            normalizedSources += BuiltinSources.createFavoritesSource()
        }

        val normalizedStyle = input.style.copy(
            quoteFontSp = StyleParsers.clampQuoteFontSp(input.style.quoteFontSp),
            authorFontSp = StyleParsers.clampAuthorFontSp(input.style.authorFontSp)
        )

        return input.copy(
            sources = normalizedSources,
            style = normalizedStyle,
            autoRefreshMinutes = input.autoRefreshMinutes.coerceIn(1, 60)
        )
    }

    /**
     * 统一兜底来源配置，确保旧版本数据升级后也能安全参与刷新与展示。
     */
    private fun normalizeSource(source: QuoteSourceConfig): QuoteSourceConfig {
        val safeWeight = source.weight.coerceAtLeast(1)
        return when {
            source.id == BuiltinSources.HITOKOTO_ID -> {
                val filteredCodes = source.selectedTypeCodes
                    .map(String::trim)
                    .map(String::lowercase)
                    .filter { it in BuiltinSources.allHitokotoTypeCodes }
                    .distinct()
                source.copy(
                    typeName = BuiltinSources.HITOKOTO_NAME,
                    url = BuiltinSources.HITOKOTO_URL,
                    sourceKind = QuoteSourceKind.REMOTE,
                    isBuiltin = true,
                    selectedTypeCodes = filteredCodes,
                    weight = safeWeight
                )
            }

            source.id == BuiltinSources.FAVORITES_ID || source.sourceKind == QuoteSourceKind.FAVORITES -> {
                source.copy(
                    id = BuiltinSources.FAVORITES_ID,
                    typeName = BuiltinSources.FAVORITES_NAME,
                    url = BuiltinSources.FAVORITES_URL,
                    appKey = "",
                    sourceKind = QuoteSourceKind.FAVORITES,
                    isBuiltin = true,
                    selectedTypeCodes = emptyList(),
                    weight = safeWeight,
                    failStreak = 0,
                    tempDisabled = false
                )
            }

            else -> source.copy(
                sourceKind = QuoteSourceKind.REMOTE,
                weight = safeWeight
            )
        }
    }
}
