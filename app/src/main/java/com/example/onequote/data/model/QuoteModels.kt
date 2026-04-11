package com.example.onequote.data.model

import kotlinx.serialization.Serializable

@Serializable
data class QuoteContent(
    val text: String,
    val author: String? = null,
    /** 来源平台名称（例如：一言 hitokoto / 用户自定义来源名）。 */
    val sourceType: String? = null,
    /** 来源类型代码（例如：hitokoto 的 a~l）。 */
    val sourceTypeCode: String? = null,
    /** 句子来源说明（例如：from 字段）。 */
    val sourceFrom: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Serializable
data class QuoteSourceConfig(
    val id: String,
    val typeName: String,
    val url: String,
    val appKey: String,
    /** 来源类型：远程 API 或本地收藏池。 */
    val sourceKind: QuoteSourceKind = QuoteSourceKind.REMOTE,
    /** 是否为内置来源。 */
    val isBuiltin: Boolean = false,
    /** 内置来源的类型筛选（例如 hitokoto 的 a~l），为空表示不使用该来源。 */
    val selectedTypeCodes: List<String> = emptyList(),
    /** 来源权重，值越大越容易在多来源随机时被抽中。 */
    val weight: Int = 1,
    val enabled: Boolean = false,
    val tempDisabled: Boolean = false,
    val failStreak: Int = 0
)

@Serializable
enum class QuoteSourceKind {
    REMOTE,
    FAVORITES
}

@Serializable
data class WidgetStyleConfig(
    val backgroundRgba: String = "0.0.0.140",
    val textRgba: String = "255.255.255.255",
    val authorRgba: String = "220.220.220.255",
    val layoutMode: LayoutMode = LayoutMode.HORIZONTAL,
    val textAlignMode: TextAlignMode = TextAlignMode.LEFT,
    /** 正文字号（sp），范围 12~25。 */
    val quoteFontSp: Int = 16,
    /** 作者字号（sp），范围 12~20。 */
    val authorFontSp: Int = 12,
    val cornerRadiusLevel: Int = 4,
    /** 阴影强度预设，正文与作者共用。 */
    val shadowPreset: ShadowPreset = ShadowPreset.NORMAL
)

@Serializable
enum class ShadowPreset {
    NONE,
    NORMAL,
    BOLD,
    BOLD_LIGHT
}

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
enum class TextAlignMode {
    LEFT,
    CENTER,
    RIGHT
}

object BuiltinSources {
    const val HITOKOTO_ID = "builtin_hitokoto"
    const val HITOKOTO_NAME = "一言 hitokoto"
    const val HITOKOTO_URL = "https://v1.hitokoto.cn/"
    const val FAVORITES_ID = "builtin_favorites"
    const val FAVORITES_NAME = "收藏"
    const val FAVORITES_URL = "local://favorites"

    val hitokotoTypeOptions: List<Pair<String, String>> = listOf(
        "a" to "动画",
        "b" to "漫画",
        "c" to "游戏",
        "d" to "文学",
        "e" to "原创",
        "f" to "来自网络",
        "g" to "其他",
        "h" to "影视",
        "i" to "诗词",
        "j" to "网易云",
        "k" to "哲学",
        "l" to "抖机灵"
    )

    val allHitokotoTypeCodes: List<String> = hitokotoTypeOptions.map { it.first }

    fun createDefaultHitokotoSource(): QuoteSourceConfig {
        return QuoteSourceConfig(
            id = HITOKOTO_ID,
            typeName = HITOKOTO_NAME,
            url = HITOKOTO_URL,
            appKey = "",
            sourceKind = QuoteSourceKind.REMOTE,
            isBuiltin = true,
            selectedTypeCodes = allHitokotoTypeCodes,
            weight = 1,
            enabled = true,
            tempDisabled = false,
            failStreak = 0
        )
    }

    fun createFavoritesSource(): QuoteSourceConfig {
        return QuoteSourceConfig(
            id = FAVORITES_ID,
            typeName = FAVORITES_NAME,
            url = FAVORITES_URL,
            appKey = "",
            sourceKind = QuoteSourceKind.FAVORITES,
            isBuiltin = true,
            selectedTypeCodes = emptyList(),
            weight = 1,
            enabled = false,
            tempDisabled = false,
            failStreak = 0
        )
    }
}

@Serializable
data class AppSettings(
    val sources: List<QuoteSourceConfig> = listOf(
        BuiltinSources.createDefaultHitokotoSource(),
        BuiltinSources.createFavoritesSource()
    ),
    val style: WidgetStyleConfig = WidgetStyleConfig(),
    val autoRefreshMinutes: Int = 30,
    val lastQuote: QuoteContent? = null,
    val lastManualRefreshAtMillis: Long = 0L,
    val savedPreviewVersion: Long = 0L,
    val onboardingCompleted: Boolean = false,
    val singleClickAction: WidgetClickAction = WidgetClickAction.REFRESH,
    val doubleClickAction: WidgetClickAction = WidgetClickAction.COPY,
    val favorites: List<FavoriteQuote> = emptyList()
)
