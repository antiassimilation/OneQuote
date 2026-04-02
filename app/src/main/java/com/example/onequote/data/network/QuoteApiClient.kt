package com.example.onequote.data.network

import com.example.onequote.data.model.QuoteContent
import com.example.onequote.data.model.QuoteSourceConfig
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
        val httpUrl = source.url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("format", "json")
            ?.addQueryParameter("appkey", source.appKey)
            ?.build()
            ?: return Result.failure(IllegalArgumentException("invalid_url"))

        val request = Request.Builder().url(httpUrl).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IOException("http_${response.code}"))
                }
                val body = response.body?.string().orEmpty()
                val parsed = json.decodeFromString(ApiResponse.serializer(), body)
                if (parsed.code != 200 || parsed.data?.text.isNullOrBlank()) {
                    Result.failure(IllegalStateException("api_code_${parsed.code}"))
                } else {
                    Result.success(
                        QuoteContent(
                            text = parsed.data?.text.orEmpty(),
                            author = parsed.data?.author,
                            sourceType = source.typeName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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

