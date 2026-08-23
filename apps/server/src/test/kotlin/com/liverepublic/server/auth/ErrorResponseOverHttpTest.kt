package com.liverepublic.server.auth

import com.liverepublic.server.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient

/**
 * MockMvc는 Servlet의 ERROR dispatch를 재현하지 않아, /error가 Security에 막혀
 * 모든 오류가 401로 가려지는 문제를 잡지 못한다. 실제 HTTP로 오류 상태를 검증한다.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ErrorResponseOverHttpTest {

    @LocalServerPort
    var port: Int = 0

    private fun client(): RestClient = RestClient.builder()
        .baseUrl("http://localhost:$port")
        .defaultStatusHandler({ true }) { _, _ -> } // 오류 상태에서도 예외를 던지지 않는다.
        .build()

    private fun postJson(path: String, body: String): HttpStatus =
        HttpStatus.valueOf(
            client().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity().statusCode.value(),
        )

    @Test
    fun `오류 응답이 401로 가려지지 않고 본래 상태로 전달된다`() {
        // ASCII가 아닌 비밀번호 → 400 (정책: 영문 대소문자·숫자·특수문자만 허용)
        val hangul25 = "가".repeat(25)
        assertEquals(
            HttpStatus.BAD_REQUEST,
            postJson(
                "/api/auth/signup",
                """{"email":"http-bytes@test.local","password":"$hangul25","name":"바이트"}""",
            ),
        )

        // 중복 가입 → 409
        val body = """{"email":"http-dup@test.local","password":"password-123","name":"중복"}"""
        assertEquals(HttpStatus.CREATED, postJson("/api/auth/signup", body))
        assertEquals(HttpStatus.CONFLICT, postJson("/api/auth/signup", body))

        // 미인증 보호 자원 → 401 유지
        val unauthorized = client().get().uri("/api/shops/my").retrieve().toBodilessEntity().statusCode.value()
        assertEquals(401, unauthorized)
    }

    @Test
    fun `400 응답 본문에 서버가 작성한 안내문이 포함된다`() {
        // Owner 준비
        val email = "http-message@test.local"
        postJson("/api/auth/signup", """{"email":"$email","password":"password-123","name":"안내"}""")
        val login = client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .body("""{"email":"$email","password":"password-123"}""")
            .retrieve().toBodilessEntity()
        val sessionCookie = requireNotNull(login.headers["Set-Cookie"]?.firstOrNull()) { "SESSION Cookie 없음" }
            .substringBefore(";")
        client().post().uri("/api/shops").header("Cookie", sessionCookie)
            .contentType(MediaType.APPLICATION_JSON).body("""{"name":"안내 상점"}""")
            .retrieve().toBodilessEntity()

        // '/' 포함 Option → 400 본문의 message가 화면에 그대로 표시할 안내문이다.
        val res = client().post().uri("/api/products").header("Cookie", sessionCookie)
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"name":"안내 상품","price":1000,"optionGroups":[{"name":"색상","options":["A / B"]}]}""")
            .retrieve().toEntity(String::class.java)
        assertEquals(400, res.statusCode.value())
        val body = requireNotNull(res.body)
        assert(body.contains("Option 이름에는")) { "본문에 안내문이 없다: $body" }
    }
}
