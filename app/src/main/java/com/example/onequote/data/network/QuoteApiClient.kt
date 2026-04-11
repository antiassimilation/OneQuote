package com.example.onequote.data.network

import com.example.onequote.data.model.QuoteContent
import com.example.onequote.data.model.BuiltinSources
import com.example.onequote.data.model.QuoteSourceConfig
import com.example.onequote.data.model.QuoteSourceKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class QuoteApiClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun fetch(source: QuoteSourceConfig): Result<QuoteContent> {
        if (source.sourceKind != QuoteSourceKind.REMOTE) {
            return Result.failure(IllegalArgumentException("unsupported_source_kind"))
        }

        val httpUrlBuilder = source.url.toHttpUrlOrNull()
            ?.newBuilder()
            ?: return Result.failure(IllegalArgumentException("invalid_url"))

        if (source.isBuiltin && source.id == BuiltinSources.HITOKOTO_ID) {
            source.selectedTypeCodes
                .map(String::trim)
                .filter(String::isNotBlank)
                .forEach { code ->
                    httpUrlBuilder.addQueryParameter("c", code)
                }
            // 明确约束返回格式与编码，减少解析歧义。
            httpUrlBuilder.addQueryParameter("encode", "json")
            httpUrlBuilder.addQueryParameter("charset", "utf-8")
        } else {
            httpUrlBuilder.addQueryParameter("format", "json")
            if (source.appKey.isNotBlank()) {
                httpUrlBuilder.addQueryParameter("appkey", source.appKey)
            }
        }

        val httpUrl = httpUrlBuilder.build()

        val request = Request.Builder().url(httpUrl).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("http_${response.code}"))
                }
                val body = response.body?.string().orEmpty()
                parseHitokoto(body, source)
                    ?: parseLegacy(body, source)
                    ?: Result.failure(IllegalStateException("api_payload_invalid"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseHitokoto(body: String, source: QuoteSourceConfig): Result<QuoteContent>? {
        val parsed = runCatching { json.decodeFromString(HitokotoResponse.serializer(), body) }.getOrNull()
            ?: return null
        val text = parsed.hitokoto?.trim().orEmpty()
        if (text.isBlank()) return null

        val normalizedCode = normalizeHitokotoTypeCode(parsed.type)
        return Result.success(
            QuoteContent(
                text = text,
                author = resolveHitokotoAuthor(parsed.fromWho, parsed.from, parsed.creator),
                sourceType = source.typeName,
                sourceTypeCode = normalizedCode,
                sourceFrom = parsed.from?.trim().takeUnless { it.isNullOrBlank() }
            )
        )
    }

    private fun parseLegacy(body: String, source: QuoteSourceConfig): Result<QuoteContent>? {
        val parsed = runCatching { json.decodeFromString(ApiResponse.serializer(), body) }.getOrNull()
            ?: return null
        if (parsed.code != 200 || parsed.data?.text.isNullOrBlank()) {
            return Result.failure(IllegalStateException("api_code_${parsed.code}"))
        }
        return Result.success(
            QuoteContent(
                text = parsed.data.text.orEmpty(),
                author = parsed.data.author,
                sourceType = source.typeName
            )
        )
    }

    private fun normalizeHitokotoTypeCode(raw: String?): String {
        val code = raw?.trim()?.lowercase().orEmpty()
        return if (code in BuiltinSources.allHitokotoTypeCodes) code else "a"
    }

    /**
     * hitokoto 的可展示作者信息会分布在多个字段中，这里做顺序兜底，避免作者高概率为空。
     */
    internal fun resolveHitokotoAuthor(fromWho: String?, from: String?, creator: String?): String? {
        return sequenceOf(fromWho, from, creator)
            .map { it?.trim().orEmpty() }
            .firstOrNull { it.isNotBlank() }
    }
}

@Serializable
private data class ApiResponse(
    val code: Int = -1,
    val msg: String? = null,
    val data: ApiData? = null
)

@Serializable
private data class ApiData(
    val text: String? = null,
    val author: String? = null
)

@Serializable
private data class HitokotoResponse(
    val hitokoto: String? = null,
    val type: String? = null,
    val from: String? = null,
    @kotlinx.serialization.SerialName("from_who")
    val fromWho: String? = null,
    val creator: String? = null
)
