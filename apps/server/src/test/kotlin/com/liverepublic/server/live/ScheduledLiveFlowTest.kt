package com.liverepublic.server.live

import com.liverepublic.server.TestcontainersConfiguration
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ScheduledLiveFlowTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val mapper = JsonMapper.builder().build()

    private fun ownerSession(email: String): Cookie {
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123","name":"Live Owner"}"""),
        ).andExpect(status().isCreated)
        val session = login(email, "password-123")
        mockMvc.perform(
            post("/api/shops").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$email 의 상점"}"""),
        ).andExpect(status().isCreated)
        return session
    }

    private fun login(email: String, password: String): Cookie {
        val result = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn()
        return requireNotNull(result.response.getCookie("SESSION"))
    }

    private fun createLive(session: Cookie, title: String, thumbnailUrl: String? = null): Long {
        val thumbnail = thumbnailUrl?.let { ""","thumbnailUrl":"$it"""" } ?: ""
        val result = mockMvc.perform(
            post("/api/lives").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"$title","scheduledStartAt":"2027-01-15T20:00:00+09:00"$thumbnail}"""),
        ).andExpect(status().isCreated).andReturn()
        return mapper.readTree(result.response.contentAsString).get("id").asLong()
    }

    private fun createProduct(session: Cookie, name: String): Long {
        val result = mockMvc.perform(
            post("/api/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","price":10000,"optionGroups":[]}"""),
        ).andExpect(status().isCreated).andReturn()
        return mapper.readTree(result.response.contentAsString).get("id").asLong()
    }

    @Test
    fun `예정 Live를 여러 개 만들고 썸네일 URL이 보존된다`() {
        val session = ownerSession("live-owner@test.local")
        val live1 = createLive(session, "첫 방송", "https://cdn.example.com/live1.jpg")
        createLive(session, "둘째 방송")

        mockMvc.perform(get("/api/lives").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))

        // 상품 없는 예정 Live도 저장되고 준비 미완료 사유가 표시된다. 썸네일 URL 왕복 확인.
        mockMvc.perform(get("/api/lives/$live1").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ready").value(false))
            .andExpect(jsonPath("$.notReadyReasons.length()").value(1))
            .andExpect(jsonPath("$.thumbnailUrl").value("https://cdn.example.com/live1.jpg"))

        // http(s)가 아닌 썸네일 URL은 거절된다.
        mockMvc.perform(
            post("/api/lives").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"불량","scheduledStartAt":"2027-01-15T20:00:00+09:00","thumbnailUrl":"javascript:alert(1)"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `Streamer 서브계정 생성과 최초 비밀번호 변경 흐름`() {
        val session = ownerSession("live-sub@test.local")

        mockMvc.perform(
            post("/api/streamers").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"streamer-one","temporaryPassword":"temp-pass-123","name":"방송 담당"}"""),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.loginId").value("streamer-one"))
            .andExpect(jsonPath("$.mustChangePassword").value(true))

        // 임시 비밀번호로 로그인 — 비밀번호 변경 전에는 보호 API가 403이다.
        val streamerSession = login("streamer-one", "temp-pass-123")
        mockMvc.perform(get("/api/auth/me").cookie(streamerSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mustChangePassword").value(true))
            .andExpect(jsonPath("$.isStreamer").value(true))
        mockMvc.perform(get("/api/lives").cookie(streamerSession))
            .andExpect(status().isForbidden)

        // 임시 비밀번호를 그대로 새 비밀번호로 쓸 수 없다.
        mockMvc.perform(
            post("/api/auth/password").cookie(streamerSession).contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"temp-pass-123","newPassword":"temp-pass-123"}"""),
        ).andExpect(status().isBadRequest)

        // 다른 비밀번호로 변경하면 제한이 풀리고 새 비밀번호로 로그인된다.
        mockMvc.perform(
            post("/api/auth/password").cookie(streamerSession).contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"temp-pass-123","newPassword":"my-new-pass-456"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.mustChangePassword").value(false))
        mockMvc.perform(get("/api/auth/me").cookie(streamerSession))
            .andExpect(jsonPath("$.mustChangePassword").value(false))
        login("streamer-one", "my-new-pass-456")
    }

    @Test
    fun `상품 두 개를 순서와 함께 연결하고 순서를 변경한다`() {
        val session = ownerSession("live-products@test.local")
        val liveId = createLive(session, "상품 방송")
        val productA = createProduct(session, "상품 A")
        val productB = createProduct(session, "상품 B")

        // 상품 연결만으로 방송 준비 완료가 된다 (담당자는 준비 조건이 아니다 — 2026-08-24 결정).
        mockMvc.perform(
            put("/api/lives/$liveId/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"productIds":[$productA,$productB]}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.products[0].name").value("상품 A"))
            .andExpect(jsonPath("$.products[1].name").value("상품 B"))
            .andExpect(jsonPath("$.ready").value(true))

        // 순서 변경 후 조회에서도 유지된다.
        mockMvc.perform(
            put("/api/lives/$liveId/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"productIds":[$productB,$productA]}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(get("/api/lives/$liveId").cookie(session))
            .andExpect(jsonPath("$.products[0].name").value("상품 B"))
            .andExpect(jsonPath("$.products[1].name").value("상품 A"))
    }

    @Test
    fun `중복 상품 연결과 다른 Shop 자원 연결을 거절한다`() {
        val sessionA = ownerSession("live-a@test.local")
        val liveId = createLive(sessionA, "A의 방송")
        val productA = createProduct(sessionA, "A의 상품")

        // 중복 상품
        mockMvc.perform(
            put("/api/lives/$liveId/products").cookie(sessionA).contentType(MediaType.APPLICATION_JSON)
                .content("""{"productIds":[$productA,$productA]}"""),
        ).andExpect(status().isBadRequest)

        val sessionB = ownerSession("live-b@test.local")
        val productB = createProduct(sessionB, "B의 상품")

        // 다른 Shop의 상품 연결 거절
        mockMvc.perform(
            put("/api/lives/$liveId/products").cookie(sessionA).contentType(MediaType.APPLICATION_JSON)
                .content("""{"productIds":[$productB]}"""),
        ).andExpect(status().isBadRequest)

        // 다른 Shop의 Live 접근 거절
        mockMvc.perform(get("/api/lives/$liveId").cookie(sessionB))
            .andExpect(status().isNotFound)
        mockMvc.perform(
            put("/api/lives/$liveId").cookie(sessionB).contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"탈취","scheduledStartAt":"2027-01-15T20:00:00+09:00"}"""),
        ).andExpect(status().isNotFound)
    }

    @Test
    fun `취소된 Live는 보존되고 수정할 수 없다`() {
        val session = ownerSession("live-cancel@test.local")
        val liveId = createLive(session, "취소될 방송")

        mockMvc.perform(post("/api/lives/$liveId/cancel").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        // 취소 후에도 목록·상세에서 보존된다.
        mockMvc.perform(get("/api/lives/$liveId").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        // 취소된 Live는 수정·재취소할 수 없다.
        mockMvc.perform(
            put("/api/lives/$liveId").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"수정 시도","scheduledStartAt":"2027-01-15T20:00:00+09:00"}"""),
        ).andExpect(status().isConflict)
        mockMvc.perform(post("/api/lives/$liveId/cancel").cookie(session))
            .andExpect(status().isConflict)
    }

    @Test
    fun `예정 시각이 offset을 포함한 ISO-8601로 전달된다`() {
        val session = ownerSession("live-time@test.local")
        val liveId = createLive(session, "시간 방송")
        val body = mockMvc.perform(get("/api/lives/$liveId").cookie(session))
            .andReturn().response.contentAsString
        val at = mapper.readTree(body).get("scheduledStartAt").asText()
        // KST 20:00 = UTC 11:00 — offset 포함 형식이며 UTC 기준으로 동일 시각이다.
        assert(java.time.OffsetDateTime.parse(at).isEqual(java.time.OffsetDateTime.parse("2027-01-15T11:00:00Z"))) { at }
    }
}
