package com.example.onequote.data.repo

import com.example.onequote.data.model.AppSettings
import com.example.onequote.data.model.BuiltinSources
import com.example.onequote.data.model.FavoriteQuote
import com.example.onequote.data.model.QuoteContent
import com.example.onequote.data.model.QuoteSourceConfig
import com.example.onequote.data.network.QuoteApiClient
import com.example.onequote.data.store.AppSettingsStore
import com.example.onequote.data.util.AppDebugLogger
import kotlinx.coroutines.flow.Flow
import kotlin.random.Random

class QuoteRepository(
    private val store: AppSettingsStore,
    private val apiClient: QuoteApiClient
) {
    private val logTag = "QuoteRepository"
    private val refreshGateLock = Any()
    private var refreshInFlight = false
    private var lastRefreshStartAtMillis = 0L

    companion object {
        /**
         * 全局刷新最小间隔（毫秒），统一约束小组件点击、应用内手动刷新与 Worker 自动刷新。
         */
        private const val GLOBAL_REFRESH_MIN_INTERVAL_MS = 2500L
    }

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

    suspend fun updateBuiltinHitokotoTypeSelection(selectedCodes: List<String>) {
        val normalized = selectedCodes
            .map(String::trim)
            .map(String::lowercase)
            .filter { it in BuiltinSources.allHitokotoTypeCodes }
            .distinct()

        store.update { current ->
            current.copy(
                sources = current.sources.map { source ->
                    if (source.id == BuiltinSources.HITOKOTO_ID) {
                        // 全不选视为不使用该来源：enabled=false；否则默认可用。
                        source.copy(
                            selectedTypeCodes = normalized,
                            enabled = normalized.isNotEmpty(),
                            tempDisabled = false,
                            failStreak = 0
                        )
                    } else {
                        source
                    }
                }
            )
        }
    }

    suspend fun removeSource(sourceId: String) {
        store.update { current ->
            current.copy(sources = current.sources.filterNot { it.id == sourceId })
        }
    }

    suspend fun testAndEnableSource(sourceId: String): Result<Unit> {
        AppDebugLogger.log(logTag, "test_and_enable_start sourceId=$sourceId")
        val settings = store.getSettings()
        val source = settings.sources.firstOrNull { it.id == sourceId }
            ?: return Result.failure(IllegalArgumentException("source_not_found"))

        if (source.isBuiltin && source.id == BuiltinSources.HITOKOTO_ID && source.selectedTypeCodes.isEmpty()) {
            AppDebugLogger.log(logTag, "test_and_enable_blocked sourceId=$sourceId reason=empty_type_selection")
            return Result.failure(IllegalStateException("builtin_type_empty"))
        }

        val testResult = apiClient.fetch(source)
        return if (testResult.isSuccess) {
            AppDebugLogger.log(logTag, "test_and_enable_success sourceId=$sourceId")
            store.update { current ->
                current.copy(
                    sources = current.sources.map {
                        if (it.id == sourceId) it.copy(enabled = true, tempDisabled = false, failStreak = 0) else it
                    }
                )
            }
            Result.success(Unit)
        } else {
            AppDebugLogger.log(logTag, "test_and_enable_failed sourceId=$sourceId error=${testResult.exceptionOrNull()?.message}")
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
        AppDebugLogger.log(logTag, "mark_manual_refresh_at=$nowMillis")
        store.update { it.copy(lastManualRefreshAtMillis = nowMillis) }
    }

    suspend fun addFavoriteFromLastQuote(): Result<Unit> {
        val settings = getSettings()
        val quote = settings.lastQuote ?: return Result.failure(IllegalStateException("no_quote"))
        AppDebugLogger.log(logTag, "favorite_from_last_quote source=${quote.sourceType} author=${quote.author?.isNotBlank() == true} len=${quote.text.length}")
        val displaySource = quote.sourceFrom
            ?.takeIf { it.isNotBlank() }
            ?: quote.sourceType
            ?: "未知来源"
        return addFavorite(
            sourceApiName = displaySource,
            author = quote.author,
            text = quote.text
        )
    }

    suspend fun addFavorite(sourceApiName: String, author: String?, text: String): Result<Unit> {
        val safeText = text.trim()
        if (safeText.isBlank()) return Result.failure(IllegalArgumentException("favorite_text_blank"))
        AppDebugLogger.log(logTag, "favorite_add_attempt source=$sourceApiName textLen=${safeText.length} author=${author?.isNotBlank() == true}")

        store.update { current ->
            val duplicated = current.favorites.any {
                it.text == safeText && (it.author.orEmpty() == author.orEmpty()) && it.sourceApiName == sourceApiName
            }
            if (duplicated) {
                AppDebugLogger.log(logTag, "favorite_add_skipped duplicated=true")
                return@update current
            }

            val nextId = (current.favorites.maxOfOrNull { it.id } ?: 0) + 1
            val next = FavoriteQuote(
                id = nextId,
                sourceApiName = sourceApiName,
                author = author?.takeIf(String::isNotBlank),
                text = safeText
            )
            current.copy(favorites = current.favorites + next)
        }
        AppDebugLogger.log(logTag, "favorite_add_success")
        return Result.success(Unit)
    }

    suspend fun removeFavorite(id: Int) {
        store.update { current ->
            current.copy(favorites = current.favorites.filterNot { it.id == id })
        }
    }

    suspend fun replaceFavorites(newFavorites: List<FavoriteQuote>) {
        store.update { current ->
            current.copy(favorites = newFavorites)
        }
    }

    suspend fun exportFavoritesAsCsv(): String {
        val favorites = getSettings().favorites.sortedBy { it.id }
        AppDebugLogger.log(logTag, "export_csv_start favorites=${favorites.size}")
        return buildString {
            appendLine("编号,来源api,作者,收藏的一言内容")
            favorites.forEach { favorite ->
                appendCsvLine(
                    favorite.id.toString(),
                    favorite.sourceApiName,
                    favorite.author.orEmpty(),
                    favorite.text
                )
            }
        }
    }

    suspend fun importFavoritesFromCsv(rawCsv: String): CsvImportSummary {
        AppDebugLogger.log(logTag, "import_csv_start size=${rawCsv.length}")
        val rows = parseCsvRows(rawCsv).getOrElse {
            AppDebugLogger.log(logTag, "import_csv_failed parse_error=${it.message}")
            return CsvImportSummary(0, 0, 0, unsupportedFile = true)
        }
        if (rows.isEmpty()) return CsvImportSummary(0, 0, 0, unsupportedFile = true)

        val header = rows.firstOrNull()?.map { it.trim() }.orEmpty()
        val expectedHeader = listOf("编号", "来源api", "作者", "收藏的一言内容")
        if (header != expectedHeader) {
            AppDebugLogger.log(logTag, "import_csv_failed invalid_header=$header")
            return CsvImportSummary(0, 0, 0, unsupportedFile = true)
        }

        val dataRows = rows.drop(1)
        if (dataRows.isEmpty()) return CsvImportSummary(0, 0, 0)

        val current = getSettings()
        val merged = current.favorites.toMutableList()
        val staged = mutableListOf<FavoriteQuote>()
        var importedCount = 0
        var duplicatedCount = 0
        var invalidCount = 0

        dataRows.forEach { cols ->
            if (cols.size < 4) {
                invalidCount += 1
                return@forEach
            }

            val rowId = cols[0].trim().toIntOrNull()
            if (rowId == null || rowId <= 0) {
                invalidCount += 1
                return@forEach
            }

            val sourceName = cols[1].trim().ifBlank { "未知来源" }
            val author = cols[2].trim().ifBlank { "" }
            val text = cols[3].trim()
            if (text.isBlank()) {
                invalidCount += 1
                return@forEach
            }

            val duplicated = merged.any {
                it.sourceApiName == sourceName && it.author.orEmpty() == author && it.text == text
            }
            if (duplicated) {
                duplicatedCount += 1
                return@forEach
            }

            val nextId = (merged.maxOfOrNull { it.id } ?: 0) + staged.size + 1
            staged += FavoriteQuote(
                id = nextId,
                sourceApiName = sourceName,
                author = author.takeIf(String::isNotBlank),
                text = text
            )
            importedCount += 1
        }

        if (invalidCount > 0) {
            AppDebugLogger.log(logTag, "import_csv_failed invalid_rows=$invalidCount")
            return CsvImportSummary(0, 0, invalidCount, unsupportedFile = true)
        }

        replaceFavorites(merged + staged)
        AppDebugLogger.log(logTag, "import_csv_success imported=$importedCount duplicated=$duplicatedCount")
        return CsvImportSummary(importedCount, duplicatedCount, invalidCount)
    }

    suspend fun refreshFromEnabledSources(): Result<QuoteContent> {
        val now = System.currentTimeMillis()
        val gate = enterRefreshGate(now)
        if (gate != null) {
            AppDebugLogger.log(logTag, "refresh_blocked reason=$gate")
            return Result.failure(IllegalStateException(gate))
        }

        AppDebugLogger.log(logTag, "refresh_start")
        try {
            val current = store.getSettings()
            val enabledSources = current.sources.filter { source ->
                source.enabled && !(source.isBuiltin && source.id == BuiltinSources.HITOKOTO_ID && source.selectedTypeCodes.isEmpty())
            }
            if (enabledSources.isEmpty()) {
                AppDebugLogger.log(logTag, "refresh_failed reason=no_enabled_source")
                return Result.failure(IllegalStateException("no_enabled_source"))
            }

            val shuffled = enabledSources.shuffled()
            var lastError: Throwable = IllegalStateException("refresh_failed")
            var attemptedCount = 0
            for (source in shuffled) {
                // 实时启用态再校验，避免刷新循环期间来源被关闭后仍继续请求。
                if (!isSourceEnabledForRefresh(source.id)) {
                    AppDebugLogger.log(logTag, "refresh_source_skipped_disabled sourceId=${source.id} name=${source.typeName}")
                    continue
                }

                attemptedCount += 1
                val result = apiClient.fetch(source)
                if (result.isSuccess) {
                    val quote = result.getOrThrow()
                    AppDebugLogger.log(logTag, "refresh_success source=${source.typeName} len=${quote.text.length}")
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
                AppDebugLogger.log(logTag, "refresh_source_failed source=${source.typeName} error=${lastError.message}")
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

            if (attemptedCount == 0) {
                AppDebugLogger.log(logTag, "refresh_failed reason=no_enabled_source_after_recheck")
                return Result.failure(IllegalStateException("no_enabled_source"))
            }

            AppDebugLogger.log(logTag, "refresh_failed final=${lastError.message}")
            return Result.failure(lastError)
        } finally {
            leaveRefreshGate()
        }
    }

    /**
     * 刷新循环中的实时启用态检查：
     * 兼容并发场景下的手动禁用/内置分类清空，仅做本地配置读取。
     */
    private suspend fun isSourceEnabledForRefresh(sourceId: String): Boolean {
        val latest = store.getSettings()
        val source = latest.sources.firstOrNull { it.id == sourceId } ?: return false
        if (!source.enabled) return false
        if (source.isBuiltin && source.id == BuiltinSources.HITOKOTO_ID && source.selectedTypeCodes.isEmpty()) {
            return false
        }
        return true
    }

    /**
     * 进入刷新闸门：
     * - 已有刷新在执行则拒绝（避免并发请求）；
     * - 与上次刷新启动间隔过短则拒绝（避免高频请求）。
     */
    private fun enterRefreshGate(nowMillis: Long): String? {
        synchronized(refreshGateLock) {
            if (refreshInFlight) return "refresh_in_flight"
            val delta = nowMillis - lastRefreshStartAtMillis
            if (lastRefreshStartAtMillis > 0L && delta in 0 until GLOBAL_REFRESH_MIN_INTERVAL_MS) {
                return "refresh_throttled"
            }
            refreshInFlight = true
            lastRefreshStartAtMillis = nowMillis
            return null
        }
    }

    /** 释放刷新闸门。 */
    private fun leaveRefreshGate() {
        synchronized(refreshGateLock) {
            refreshInFlight = false
        }
    }

    private fun StringBuilder.appendCsvLine(vararg columns: String) {
        append(columns.joinToString(",") { escapeCsv(it) })
        append('\n')
    }

    private fun escapeCsv(input: String): String {
        val escaped = input.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    /** 严格 CSV 解析：支持双引号、换行与逗号转义，格式异常时直接失败。 */
    private fun parseCsvRows(rawCsv: String): Result<List<List<String>>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < rawCsv.length) {
            val c = rawCsv[i]
            when {
                c == '"' && i + 1 < rawCsv.length && rawCsv[i + 1] == '"' -> {
                    field.append('"')
                    i += 1
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    row += field.toString()
                    field.clear()
                }
                (c == '\n' || c == '\r') && !inQuotes -> {
                    if (c == '\r' && i + 1 < rawCsv.length && rawCsv[i + 1] == '\n') i += 1
                    row += field.toString()
                    field.clear()
                    if (row.any { it.isNotBlank() }) {
                        rows += row.toList()
                    }
                    row.clear()
                }
                else -> field.append(c)
            }
            i += 1
        }

        if (inQuotes) {
            return Result.failure(IllegalArgumentException("csv_unclosed_quote"))
        }

        row += field.toString()
        if (row.any { it.isNotBlank() }) {
            rows += row.toList()
        }
        return Result.success(rows)
    }
}

data class CsvImportSummary(
    val importedCount: Int,
    val duplicatedCount: Int,
    val invalidCount: Int,
    val unsupportedFile: Boolean = false
)
