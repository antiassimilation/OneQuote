package com.example.onequote.data.repo

import com.example.onequote.data.model.BuiltinSources
import com.example.onequote.data.model.FavoriteQuote
import com.example.onequote.data.model.QuoteContent
import com.example.onequote.data.model.QuoteSourceConfig
import kotlin.random.Random

/**
 * 来源选择辅助逻辑：集中承载可单测的纯函数，避免仓库层逻辑过重。
 */
internal object QuoteSourceSelection {

    /**
     * 收藏来源被抽中后，内部按等概率返回一条收藏内容，并保留原来源描述。
     */
    fun buildFavoriteQuoteContent(favorite: FavoriteQuote): QuoteContent {
        return QuoteContent(
            text = favorite.text,
            author = favorite.author?.takeIf(String::isNotBlank),
            sourceType = BuiltinSources.FAVORITES_NAME,
            sourceTypeCode = "favorite",
            sourceFrom = favorite.sourceApiName
        )
    }

    /**
     * 多来源刷新采用“按权重随机且不重复尝试”的顺序生成策略。
     */
    fun buildWeightedSourceAttemptOrder(
        sources: List<QuoteSourceConfig>,
        random: Random = Random.Default
    ): List<QuoteSourceConfig> {
        if (sources.size <= 1) return sources

        val remaining = sources.toMutableList()
        val ordered = ArrayList<QuoteSourceConfig>(sources.size)
        while (remaining.isNotEmpty()) {
            val totalWeight = remaining.sumOf { it.weight.coerceAtLeast(1) }
            var cursor = random.nextInt(totalWeight)
            var pickedIndex = 0
            for ((index, candidate) in remaining.withIndex()) {
                cursor -= candidate.weight.coerceAtLeast(1)
                if (cursor < 0) {
                    pickedIndex = index
                    break
                }
            }
            ordered += remaining.removeAt(pickedIndex)
        }
        return ordered
    }
}
