package com.example.onequote.data.repo

import com.example.onequote.data.model.BuiltinSources
import com.example.onequote.data.model.FavoriteQuote
import com.example.onequote.data.model.QuoteSourceConfig
import com.example.onequote.data.model.QuoteSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuoteSourceSelectionTest {

    @Test
    fun `buildFavoriteQuoteContent 保留收藏来源信息`() {
        val content = QuoteSourceSelection.buildFavoriteQuoteContent(
            FavoriteQuote(
                id = 1,
                sourceApiName = "一言 hitokoto",
                author = "作者A",
                text = "测试句子"
            )
        )

        assertEquals(BuiltinSources.FAVORITES_NAME, content.sourceType)
        assertEquals("favorite", content.sourceTypeCode)
        assertEquals("一言 hitokoto", content.sourceFrom)
        assertEquals("作者A", content.author)
        assertEquals("测试句子", content.text)
    }

    @Test
    fun `buildWeightedSourceAttemptOrder 返回不重复且完整的来源列表`() {
        val sourceA = createSource(id = "a", weight = 5)
        val sourceB = createSource(id = "b", weight = 1)
        val sourceC = createSource(id = "c", weight = 3)

        val ordered = QuoteSourceSelection.buildWeightedSourceAttemptOrder(
            listOf(sourceA, sourceB, sourceC),
            random = Random(1234)
        )

        assertEquals(3, ordered.size)
        assertEquals(setOf("a", "b", "c"), ordered.map { it.id }.toSet())
        assertTrue(ordered.distinctBy { it.id }.size == ordered.size)
    }

    private fun createSource(id: String, weight: Int): QuoteSourceConfig {
        return QuoteSourceConfig(
            id = id,
            typeName = id,
            url = "https://example.com/$id",
            appKey = "",
            sourceKind = QuoteSourceKind.REMOTE,
            weight = weight,
            enabled = true
        )
    }
}
