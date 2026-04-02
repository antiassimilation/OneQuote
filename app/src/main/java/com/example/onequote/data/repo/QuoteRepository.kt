package com.example.onequote.data.repo

import com.example.onequote.data.model.AppSettings
import com.example.onequote.data.model.QuoteContent
import com.example.onequote.data.model.QuoteSourceConfig
import com.example.onequote.data.network.QuoteApiClient
import com.example.onequote.data.store.AppSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

class QuoteRepository(
    private val store: AppSettingsStore,
    private val apiClient: QuoteApiClient
) {
    fun observeSettings(): Flow<AppSettings> = store.settingsFlow

    suspend fun getSettings(): AppSettings = store.getSettings()

    suspend fun saveSettings(settings: AppSettings) {
        store.update { settings.copy(savedPreviewVersion = System.currentTimeMillis()) }
    }

    suspend fun addSource(typeName: String, url: String, appKey: String) {
        val source = QuoteSourceConfig(
            id = "${System.currentTimeMillis()}-${Random.nextInt(1000, 9999)}",
            typeName = typeName.trim(),
            url = url.trim(),
            appKey = appKey.trim(),
            enabled = false,
            tempDisabled = false,
            failStreak = 0
        )
        store.update { it.copy(sources = it.sources + source) }
    }

    suspend fun removeSource(sourceId: String) {
        store.update { current ->
            current.copy(sources = current.sources.filterNot { it.id == sourceId })
        }
    }

    suspend fun testAndEnableSource(sourceId: String): Result<Unit> {
        val settings = store.getSettings()
        val source = settings.sources.firstOrNull { it.id == sourceId }
            ?: return Result.failure(IllegalArgumentException("source_not_found"))

        val testResult = apiClient.fetch(source)
        return if (testResult.isSuccess) {
            store.update { current ->
                current.copy(
                    sources = current.sources.map {
                        if (it.id == sourceId) it.copy(enabled = true, tempDisabled = false, failStreak = 0) else it
                    }
                )
            }
            Result.success(Unit)
        } else {
            store.update { current ->
                current.copy(
                    sources = current.sources.map {
                        if (it.id == sourceId) it.copy(enabled = false, tempDisabled = true, failStreak = 0) else it
                    }
                )
            }
            Result.failure(testResult.exceptionOrNull() ?: IllegalStateException("source_unavailable"))
        }
    }

    suspend fun disableSource(sourceId: String) {
        store.update { current ->
            current.copy(
                sources = current.sources.map {
                    if (it.id == sourceId) it.copy(enabled = false, tempDisabled = false, failStreak = 0) else it
                }
            )
        }
    }

    suspend fun markManualRefreshAt(nowMillis: Long) {
        store.update { it.copy(lastManualRefreshAtMillis = nowMillis) }
    }

    suspend fun refreshFromEnabledSources(): Result<QuoteContent> {
        val current = store.getSettings()
        val enabledSources = current.sources.filter { it.enabled }
        if (enabledSources.isEmpty()) {
            return Result.failure(IllegalStateException("no_enabled_source"))
        }

        val shuffled = enabledSources.shuffled()
        var lastError: Throwable = IllegalStateException("refresh_failed")
        for (source in shuffled) {
            val result = apiClient.fetch(source)
            if (result.isSuccess) {
                val quote = result.getOrThrow()
                store.update { settings ->
                    settings.copy(
                        lastQuote = quote,
                        sources = settings.sources.map {
                            if (it.id == source.id) it.copy(failStreak = 0, tempDisabled = false) else it
                        }
                    )
                }
                return Result.success(quote)
            }

            lastError = result.exceptionOrNull() ?: IllegalStateException("unknown_error")
            store.update { settings ->
                settings.copy(
                    sources = settings.sources.map {
                        if (it.id == source.id) {
                            val newStreak = it.failStreak + 1
                            if (newStreak >= 2) {
                                it.copy(enabled = false, tempDisabled = true, failStreak = 0)
                            } else {
                                it.copy(failStreak = newStreak)
                            }
                        } else {
                            it
                        }
                    }
                )
            }
        }

        return Result.failure(lastError)
    }
}

