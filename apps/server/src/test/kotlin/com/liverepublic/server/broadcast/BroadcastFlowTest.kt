package com.liverepublic.server.broadcast

import com.liverepublic.server.TestcontainersConfiguration
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.atomic.AtomicInteger

/** 테스트용 IVS Stub — 실제 AWS 호출 없이 Channel 생성·중단을 흉내 낸다. */
@TestConfiguration(proxyBeanMethods = false)
class StubIvsConfiguration {
    val created = AtomicInteger()

    @Bean
    fun ivsService(): IvsService = object : IvsService {
        override fun createChannel(name: String): IvsChannel {
            val n = created.incrementAndGet()
            return IvsChannel(
                channelArn = "arn:aws:ivs:stub:channel/$name-$n",
                ingestEndpoint = "rtmps://stub.ingest:443/app/",
                streamKey = "sk_stub_$n",
                playbackUrl = "https://stub.playback/$name.m3u8",
            )
        }

        override fun stopStream(channelArn: String) = Unit
    }
}

@Import(TestcontainersConfiguration::class, StubIvsConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastFlowTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val mapper = JsonMapper.builder().build()

    private fun ownerSession(email: String): Cookie {
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123","name":"Broadcast Owner"}"""),
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

    private fun createProduct(session: Cookie, name: String): Long {
        val result = mockMvc.perform(
            post("/api/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","price":10000,"optionGroups":[{"name":"색상","options":["검정","흰색"]}]}"""),
        ).andExpect(status().isCreated).andReturn()
        return mapper.readTree(result.response.contentAsString).get("id").asLong()
    }

    private fun createLiveWithProducts(session: Cookie, title: String, productIds: List<Long>): Long {
        val result = mockMvc.perform(
            post("/api/lives").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"$title","scheduledStartAt":"2027-06-01T20:00:00+09:00"}"""),
        ).andExpect(status().isCreated).andReturn()
        val liveId = mapper.readTree(result.response.contentAsString).get("id").asLong()
        if (productIds.isNotEmpty()) {
            mockMvc.perform(
                put("/api/lives/$liveId/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"productIds":[${productIds.joinToString(",")}]}"""),
            ).andExpect(status().isOk)
        }
        return liveId
    }

    @Test
    fun `방송 시작-상품 전환-종료 전체 흐름`() {
        val session = ownerSession("bc-flow@test.local")
        val p1 = createProduct(session, "방송 상품 1")
        val p2 = createProduct(session, "방송 상품 2")
        val liveId = createLiveWithProducts(session, "전체 흐름 방송", listOf(p1, p2))

        // 시작 → LIVE, 송출 정보와 첫 상품이 현재 상품으로 설정된다.
        val startBody = mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))
            .andExpect(jsonPath("$.ingestEndpoint").value(org.hamcrest.Matchers.startsWith("rtmps://")))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.startsWith("sk_stub")))
            .andExpect(jsonPath("$.playbackUrl").isNotEmpty)
            .andExpect(jsonPath("$.startedAt").isNotEmpty)
            .andReturn().response.contentAsString
        val detail = mapper.readTree(startBody)
        val firstLp = detail.get("products").get(0).get("liveProductId").asLong()
        val secondLp = detail.get("products").get(1).get("liveProductId").asLong()
        assert(detail.get("currentLiveProductId").asLong() == firstLp)
        // SKU별 재고(Available)가 함께 내려간다.
        assert(detail.get("products").get(0).get("skus").size() == 2)

        // 상품 전환 → 현재 상품 변경
        mockMvc.perform(
            put("/api/broadcast/lives/$liveId/current-product").cookie(session)
                .contentType(MediaType.APPLICATION_JSON).content("""{"liveProductId":$secondLp}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.currentLiveProductId").value(secondLp))

        // 종료 → ENDED, 송출 정보는 더 이상 내려가지 않는다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
            .andExpect(jsonPath("$.endedAt").isNotEmpty)
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.nullValue()))

        // 종료된 Live 재시작 거절
        mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(session))
            .andExpect(status().isConflict)
    }

    @Test
    fun `상품이 연결되지 않은 Live는 시작할 수 없다`() {
        val session = ownerSession("bc-notready@test.local")
        val liveId = createLiveWithProducts(session, "빈 방송", emptyList())
        mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(session))
            .andExpect(status().isConflict)
    }

    @Test
    fun `Shop당 방송 중 Live는 하나만 허용된다`() {
        val session = ownerSession("bc-single@test.local")
        val p = createProduct(session, "단일 방송 상품")
        val live1 = createLiveWithProducts(session, "첫 방송", listOf(p))
        val live2 = createLiveWithProducts(session, "둘째 방송", listOf(p))

        mockMvc.perform(post("/api/broadcast/lives/$live1/start").cookie(session))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/broadcast/lives/$live2/start").cookie(session))
            .andExpect(status().isConflict)

        // 종료하면 다시 시작할 수 있다.
        mockMvc.perform(post("/api/broadcast/lives/$live1/end").cookie(session))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/broadcast/lives/$live2/start").cookie(session))
            .andExpect(status().isOk)
    }

    @Test
    fun `Streamer 서브계정이 게릴라 Live를 만들어 시작한다`() {
        val session = ownerSession("bc-guerrilla@test.local")
        val p = createProduct(session, "게릴라 상품")

        mockMvc.perform(
            post("/api/streamers").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"bc-streamer","temporaryPassword":"temp-pass-123","name":"게릴라"}"""),
        ).andExpect(status().isCreated)
        val streamerSession = login("bc-streamer", "temp-pass-123")
        mockMvc.perform(
            post("/api/auth/password").cookie(streamerSession).contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"temp-pass-123","newPassword":"changed-pass-456"}"""),
        ).andExpect(status().isOk)

        // Streamer가 게릴라 Live 생성 → 즉시 시작
        val body = mockMvc.perform(
            post("/api/broadcast/lives").cookie(streamerSession).contentType(MediaType.APPLICATION_JSON)
                .content("""{"title":"게릴라 방송","productIds":[$p]}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val liveId = mapper.readTree(body).get("id").asLong()

        mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(streamerSession))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))

        // 시작 요청자가 기록되고 Owner 목록에서도 방송중으로 보인다.
        mockMvc.perform(get("/api/broadcast/lives").cookie(session))
            .andExpect(jsonPath("$[0].status").value("LIVE"))
    }

    @Test
    fun `다른 Shop 사용자는 방송을 시작할 수 없다`() {
        val sessionA = ownerSession("bc-a@test.local")
        val p = createProduct(sessionA, "A 상품")
        val liveId = createLiveWithProducts(sessionA, "A 방송", listOf(p))

        val sessionB = ownerSession("bc-b@test.local")
        mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(sessionB))
            .andExpect(status().isNotFound)
    }
}
