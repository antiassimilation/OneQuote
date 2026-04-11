package com.example.onequote.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuoteApiClientTest {

    private val client = QuoteApiClient()

    @Test
    fun `resolveHitokotoAuthor 优先使用 from_who`() {
        val author = client.resolveHitokotoAuthor(
            fromWho = "作者A",
            from = "作品B",
            creator = "上传者C"
        )

        assertEquals("作者A", author)
    }

    @Test
    fun `resolveHitokotoAuthor 在 from_who 为空时回退到 from`() {
        val author = client.resolveHitokotoAuthor(
            fromWho = " ",
            from = "作品B",
            creator = "上传者C"
        )

        assertEquals("作品B", author)
    }

    @Test
    fun `resolveHitokotoAuthor 在全部为空时返回 null`() {
        val author = client.resolveHitokotoAuthor(
            fromWho = " ",
            from = null,
            creator = ""
        )

        assertNull(author)
    }
}
