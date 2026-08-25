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

/** headers의 Key는 소문자다 (OkHttp toMultimap 규칙). */
data class ApiResult(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

/**
 * Server API 클라이언트. Session Cookie를 메모리에 유지한다
 * (앱 재시작 시 재로그인 — 이 Slice의 알려진 제한).
 */
object ApiClient {

    // OkHttp 워커 스레드들이 동시에 읽고 쓴다 (confirm 재시도와 상품 전환이 동시 진행될 수 있다).
    private val cookieStore = java.util.concurrent.ConcurrentHashMap<String, List<Cookie>>()

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
                ApiResult(
                    response.code,
                    response.body?.string() ?: "",
                    response.headers.toMultimap().mapValues { it.value.last() },
                )
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

    // 손상된 body(프록시가 자른 응답 등)는 통신 예외와 같은 규칙으로 null로 구조화한다 —
    // 호출부가 오류 흐름으로 처리하고, 방송 중 앱이 죽지 않게 한다.
    fun json(result: ApiResult): JSONObject? = try {
        JSONObject(result.body)
    } catch (e: Exception) {
        null
    }

    fun jsonArray(result: ApiResult): JSONArray? = try {
        JSONArray(result.body)
    } catch (e: Exception) {
        null
    }
}
