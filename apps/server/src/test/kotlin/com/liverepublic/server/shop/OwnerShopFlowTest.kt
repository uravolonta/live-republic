package com.liverepublic.server.shop

import com.liverepublic.server.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import jakarta.servlet.http.Cookie
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class OwnerShopFlowTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun signup(email: String, name: String = "테스트 Owner") {
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123","name":"$name"}"""),
        ).andExpect(status().isCreated)
    }

    /** spring-session이 발급한 SESSION Cookie로 로그인 상태를 유지한다. */
    private fun login(email: String, password: String = "password-123"): Cookie {
        val result = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn()
        return requireNotNull(result.response.getCookie("SESSION")) { "로그인 응답에 SESSION Cookie가 없다" }
    }

    @Test
    fun `Owner가 가입하고 Shop과 계좌·배송정보를 설정해 재확인한다`() {
        signup("owner-flow@test.local")
        val session = login("owner-flow@test.local")

        mockMvc.perform(
            post("/api/shops").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"라이브 상점"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("라이브 상점"))

        mockMvc.perform(
            put("/api/shops/my").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"라이브 상점","bankName":"국민은행","bankAccountNumber":"123-456-789",
                       "bankAccountHolder":"홍길동","courierName":"CJ대한통운","baseShippingFee":3000}""",
                ),
        ).andExpect(status().isOk)

        mockMvc.perform(get("/api/shops/my").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bankName").value("국민은행"))
            .andExpect(jsonPath("$.bankAccountNumber").value("123-456-789"))
            .andExpect(jsonPath("$.bankAccountHolder").value("홍길동"))
            .andExpect(jsonPath("$.courierName").value("CJ대한통운"))
            .andExpect(jsonPath("$.baseShippingFee").value(3000))

        mockMvc.perform(get("/api/auth/me").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shopId").isNumber)
    }

    @Test
    fun `같은 이메일로 다시 가입할 수 없다`() {
        signup("dup@test.local")
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"dup@test.local","password":"password-123","name":"중복"}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `한글 등 72바이트를 넘는 비밀번호는 400으로 거절된다`() {
        // 한글 25자 = 75바이트: 문자 수 검증은 통과하지만 BCrypt 한계를 넘는다.
        val hangul25 = "가".repeat(25)
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"bytes@test.local","password":"$hangul25","name":"바이트"}"""),
        ).andExpect(status().isBadRequest)

        // 로그인에서도 500이 아니라 인증 실패로 처리된다.
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"bytes@test.local","password":"$hangul25"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `비밀번호가 틀리면 로그인이 거절된다`() {
        signup("wrongpw@test.local")
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"wrongpw@test.local","password":"wrong-password"}"""),
        ).andExpect(status().isUnauthorized)
    }

    @Test
    fun `로그인하지 않으면 Shop 정보에 접근할 수 없다`() {
        mockMvc.perform(get("/api/shops/my")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `다른 Owner의 Shop 정보는 보이지 않는다`() {
        signup("owner-a@test.local")
        val sessionA = login("owner-a@test.local")
        mockMvc.perform(
            post("/api/shops").cookie(sessionA).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"A의 상점"}"""),
        ).andExpect(status().isCreated)

        signup("owner-b@test.local")
        val sessionB = login("owner-b@test.local")

        // B는 아직 Shop이 없고 A의 Shop이 노출되지 않는다.
        mockMvc.perform(get("/api/shops/my").cookie(sessionB))
            .andExpect(status().isNotFound)

        // B가 Shop을 만들어도 자신의 Shop만 조회된다.
        mockMvc.perform(
            post("/api/shops").cookie(sessionB).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"B의 상점"}"""),
        ).andExpect(status().isCreated)
        mockMvc.perform(get("/api/shops/my").cookie(sessionB))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("B의 상점"))
    }

    @Test
    fun `Shop은 한 계정에 하나만 만들 수 있다`() {
        signup("one-shop@test.local")
        val session = login("one-shop@test.local")
        mockMvc.perform(
            post("/api/shops").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"첫 상점"}"""),
        ).andExpect(status().isCreated)
        mockMvc.perform(
            post("/api/shops").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"두 번째 상점"}"""),
        ).andExpect(status().isConflict)
    }
}
