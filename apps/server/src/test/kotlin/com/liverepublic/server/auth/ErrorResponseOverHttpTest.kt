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
        // 72바이트 초과 비밀번호 → 400
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
}
