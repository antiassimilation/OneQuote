package com.example.onequote.data.model

import kotlinx.serialization.Serializable

@Serializable
data class QuoteContent(
    val text: String,
    val author: String? = null,
    val sourceType: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class QuoteSourceConfig(
    val id: String,
    val typeName: String,
    val url: String,
    val appKey: String,
    val enabled: Boolean = false,
    val tempDisabled: Boolean = false,
    val failStreak: Int = 0
)

@Serializable
data class WidgetStyleConfig(
    val backgroundRgba: String = "0.0.0.140",
    val textRgba: String = "255.255.255.255",
    val authorRgba: String = "220.220.220.255",
    val layoutMode: LayoutMode = LayoutMode.HORIZONTAL,
    val fontScalePercent: Int = 100,
    val cornerRadiusLevel: Int = 4,
    val shadowLevel: Int = 2
)

@Serializable
enum class WidgetClickAction {
    REFRESH,
    COPY,
    FAVORITE
}

@Serializable
data class FavoriteQuote(
    val id: Int,
    val sourceApiName: String,
    val author: String? = null,
    val text: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Serializable
enum class LayoutMode {
    HORIZONTAL,
    VERTICAL
}

@Serializable
data class AppSettings(
    val sources: List<QuoteSourceConfig> = emptyList(),
    val style: WidgetStyleConfig = WidgetStyleConfig(),
    val autoRefreshMinutes: Int = 30,
    val lastQuote: QuoteContent? = null,
    val lastManualRefreshAtMillis: Long = 0L,
    val savedPreviewVersion: Long = 0L,
    val singleClickAction: WidgetClickAction = WidgetClickAction.REFRESH,
    val doubleClickAction: WidgetClickAction = WidgetClickAction.COPY,
    val favorites: List<FavoriteQuote> = emptyList()
)

