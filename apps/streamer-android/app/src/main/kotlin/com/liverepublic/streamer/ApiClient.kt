package com.liverepublic.streamer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class ApiResult(val status: Int, val body: String)

/**
 * Server API 클라이언트. Session Cookie를 메모리에 유지한다
 * (앱 재시작 시 재로그인 — 이 Slice의 알려진 제한).
 */
object ApiClient {

    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    private val client = OkHttpClient.Builder()
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore[url.host].orEmpty()
        })
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    var baseUrl: String = BuildConfig.SERVER_URL

    suspend fun get(path: String, headers: Map<String, String> = emptyMap()): ApiResult =
        execute(Request.Builder().url(baseUrl + path).get().withHeaders(headers))

    suspend fun post(
        path: String,
        json: JSONObject? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResult = execute(
        Request.Builder().url(baseUrl + path)
            .post((json?.toString() ?: "{}").toRequestBody(jsonType))
            .withHeaders(headers),
    )

    suspend fun put(
        path: String,
        json: JSONObject,
        headers: Map<String, String> = emptyMap(),
    ): ApiResult = execute(
        Request.Builder().url(baseUrl + path)
            .put(json.toString().toRequestBody(jsonType))
            .withHeaders(headers),
    )

    private fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder {
        headers.forEach { (name, value) -> header(name, value) }
        return this
    }

    private suspend fun execute(builder: Request.Builder): ApiResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(builder.build()).execute().use { response ->
                ApiResult(response.code, response.body?.string() ?: "")
            }
        } catch (e: Exception) {
            // 통신 단절은 status 0으로 구조화한다 (Owner Web과 같은 규칙).
            ApiResult(0, "")
        }
    }

    /** 오류 응답 body의 서버 안내문. */
    fun errorMessage(result: ApiResult, fallback: String): String = try {
        val message = JSONObject(result.body).optString("message", "")
        if (message.isNotEmpty()) message else fallback
    } catch (e: Exception) {
        fallback
    }

    fun json(result: ApiResult): JSONObject = JSONObject(result.body)

    fun jsonArray(result: ApiResult): JSONArray = JSONArray(result.body)
}
