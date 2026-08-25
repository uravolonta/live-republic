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

/** 테스트용 IVS Stub — 실제 AWS 호출 없이 Channel 생성·중단·Stream 조회를 흉내 낸다. */
class StubIvsService : IvsService {
    val created = AtomicInteger()
    val stops = AtomicInteger()

    @Volatile var streamAvailable = true

    @Volatile var failStopsRemaining = 0

    @Volatile var streamIdSuffix = "a"

    fun reset() {
        streamAvailable = true
        failStopsRemaining = 0
        streamIdSuffix = "a"
    }

    val keysCreated = AtomicInteger()
    val keysDeleted = AtomicInteger()

    override fun createChannel(name: String): IvsChannel {
        val n = created.incrementAndGet()
        return IvsChannel(
            channelArn = "arn:aws:ivs:stub:channel/$name-$n",
            ingestEndpoint = "rtmps://stub.ingest:443/app/",
            streamKey = "sk_stub_$n",
            streamKeyArn = "arn:aws:ivs:stub:stream-key/$name-$n",
            playbackUrl = "https://stub.playback/$name.m3u8",
        )
    }

    override fun createStreamKey(channelArn: String): IvsStreamKey {
        val n = keysCreated.incrementAndGet()
        return IvsStreamKey(arn = "arn:aws:ivs:stub:stream-key/re-$n", value = "sk_stub_re_$n")
    }

    override fun deleteStreamKey(streamKeyArn: String) {
        keysDeleted.incrementAndGet()
    }

    override fun stopStream(channelArn: String) {
        if (failStopsRemaining > 0) {
            failStopsRemaining--
            throw RuntimeException("stub stop failure")
        }
        stops.incrementAndGet()
    }

    override fun currentStreamSessionId(channelArn: String): String? =
        if (streamAvailable) "st-stub-${channelArn.takeLast(4)}-$streamIdSuffix" else null
}

@TestConfiguration(proxyBeanMethods = false)
class StubIvsConfiguration {
    @Bean
    fun ivsService(): StubIvsService = StubIvsService()
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

    /** 시작 요청을 보내고 송출 임대 토큰을 돌려받는다. */
    private fun startLive(session: Cookie, liveId: Long): String {
        val body = mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(session))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return mapper.readTree(body).get("broadcastToken").asText()
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
    fun `방송 시작-확정-상품 전환-종료 전체 흐름`() {
        val session = ownerSession("bc-flow@test.local")
        val p1 = createProduct(session, "방송 상품 1")
        val p2 = createProduct(session, "방송 상품 2")
        val liveId = createLiveWithProducts(session, "전체 흐름 방송", listOf(p1, p2))

        // 시작 요청 → STARTING: 송출 자격과 임대 토큰이 발급되지만 아직 방송 중이 아니다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("STARTING"))
            .andExpect(jsonPath("$.ingestEndpoint").value(org.hamcrest.Matchers.startsWith("rtmps://")))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.startsWith("sk_stub")))
            .andExpect(jsonPath("$.broadcastToken").isNotEmpty)
            .andExpect(jsonPath("$.canControl").value(true))
            .andExpect(jsonPath("$.startedAt").value(org.hamcrest.Matchers.nullValue()))

        // 재시작(임대 갱신) — 토큰이 회전되어 새 토큰을 받는다.
        val token = startLive(session, liveId)

        // SDK 연결 확인 → LIVE 확정 + Stream Session 기록. 방송 단말(토큰)만 가능하다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(session))
            .andExpect(status().isForbidden) // 토큰 없이는 확정 불가
        val startBody = mockMvc.perform(
            post("/api/broadcast/lives/$liveId/confirm").cookie(session).header("X-Broadcast-Token", token),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))
            .andExpect(jsonPath("$.startedAt").isNotEmpty)
            .andReturn().response.contentAsString
        val detail = mapper.readTree(startBody)
        val firstLp = detail.get("products").get(0).get("liveProductId").asLong()
        val secondLp = detail.get("products").get(1).get("liveProductId").asLong()
        assert(detail.get("currentLiveProductId").asLong() == firstLp)
        // SKU별 재고(Available)가 함께 내려간다.
        assert(detail.get("products").get(0).get("skus").size() == 2)

        // 상품 전환 → 현재 상품 변경 (방송 단말 토큰 필수)
        mockMvc.perform(
            put("/api/broadcast/lives/$liveId/current-product").cookie(session)
                .header("X-Broadcast-Token", token)
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

    @org.junit.jupiter.api.BeforeEach
    fun resetStub() {
        stubIvs.reset()
    }

    @Autowired
    lateinit var streamSessionRepository: com.liverepublic.server.live.LiveStreamSessionRepository

    @Test
    fun `STARTING Live는 목록에 보이고 재진입해 재개하거나 취소할 수 있다`() {
        val session = ownerSession("bc-starting-list@test.local")
        val p = createProduct(session, "재진입 상품")
        val liveId = createLiveWithProducts(session, "재진입 방송", listOf(p))

        val token = startLive(session, liveId)

        // 앱이 종료돼도 목록에서 STARTING Live가 보인다 (슬롯 점유 중이므로 필수).
        mockMvc.perform(get("/api/broadcast/lives").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value(liveId))
            .andExpect(jsonPath("$[0].status").value("STARTING"))

        // 재진입: 임대 토큰을 제시한 단말만 자격을 다시 받는다. 토큰 없는 조회(같은 계정의
        // 다른 단말 포함)에는 자격이 내려가지 않는다.
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.canBroadcast").value(true)) // 시작 계정은 start 재호출로 임대 갱신 가능
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.startsWith("sk_stub")))
            .andExpect(jsonPath("$.canControl").value(true))

        // 시작 취소 → SCHEDULED 복귀, 서버가 IVS 중단도 시도한다.
        val stopsBefore = stubIvs.stops.get()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SCHEDULED"))
        assert(stubIvs.stops.get() == stopsBefore + 1) { "STARTING 취소에도 IVS 중단을 시도해야 한다" }
    }

    @Test
    fun `IVS 감지 지연 시 확정은 409이고 감지 후 재시도가 성공한다`() {
        val session = ownerSession("bc-confirm-retry@test.local")
        val p = createProduct(session, "지연 상품")
        val liveId = createLiveWithProducts(session, "지연 방송", listOf(p))
        val token = startLive(session, liveId)

        stubIvs.streamAvailable = false
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(status().isConflict)
        // 아직 STARTING이어야 재시도할 수 있다.
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session))
            .andExpect(jsonPath("$.status").value("STARTING"))

        stubIvs.streamAvailable = true
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))
    }

    @Test
    fun `IVS 중단 실패 시 종료는 502로 실패하고 재시도로 종료된다`() {
        val session = ownerSession("bc-stop-fail@test.local")
        val p = createProduct(session, "중단 실패 상품")
        val liveId = createLiveWithProducts(session, "중단 실패 방송", listOf(p))
        val token = startLive(session, liveId)
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(status().isOk)

        // 2회 모두 실패 → 종료 확정 없이 502, 상태는 LIVE 유지 (실송출 방치 방지).
        stubIvs.failStopsRemaining = 2
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session))
            .andExpect(status().isBadGateway)
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session))
            .andExpect(jsonPath("$.status").value("LIVE"))

        // 사용자 재시도 → 성공 → ENDED.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
    }

    @Test
    fun `재연결로 새 Stream Session이 생기면 이력에 추가된다`() {
        val session = ownerSession("bc-session-history@test.local")
        val p = createProduct(session, "이력 상품")
        val liveId = createLiveWithProducts(session, "이력 방송", listOf(p))
        val token = startLive(session, liveId)
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(status().isOk)

        // 재연결 후 새 Stream Session — LIVE 상태에서의 재확정이 새 이력을 추가한다.
        stubIvs.streamIdSuffix = "b"
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))

        val sessions = streamSessionRepository.findAllByLiveIdOrderById(liveId)
        assert(sessions.size == 2) { "Stream Session 이력은 2행이어야 한다: ${sessions.map { it.ivsStreamId }}" }
        assert(sessions[0].endedAt != null) { "이전 Session은 닫혀야 한다" }
        assert(sessions[1].endedAt == null && sessions[1].ivsStreamId.endsWith("-b"))

        // 종료 시 열린 Session이 닫힌다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session)).andExpect(status().isOk)
        assert(streamSessionRepository.findAllByLiveIdOrderById(liveId).all { it.endedAt != null })
    }

    @Test
    fun `상품이 연결되지 않은 Live는 시작할 수 없다`() {
        val session = ownerSession("bc-notready@test.local")
        val liveId = createLiveWithProducts(session, "빈 방송", emptyList())
        mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(session))
            .andExpect(status().isConflict)
    }

    @Test
    fun `Shop당 방송 중-시작 중 Live는 하나만 허용된다`() {
        val session = ownerSession("bc-single@test.local")
        val p = createProduct(session, "단일 방송 상품")
        val live1 = createLiveWithProducts(session, "첫 방송", listOf(p))
        val live2 = createLiveWithProducts(session, "둘째 방송", listOf(p))

        // STARTING만으로도 다른 Live의 시작이 차단된다 (시작 슬롯 선점).
        mockMvc.perform(post("/api/broadcast/lives/$live1/start").cookie(session))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/broadcast/lives/$live2/start").cookie(session))
            .andExpect(status().isConflict)

        // 연결 실패 시 종료(시작 취소) → SCHEDULED 복귀 → 다른 Live 시작 가능.
        mockMvc.perform(post("/api/broadcast/lives/$live1/end").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SCHEDULED"))
        val token2 = startLive(session, live2)

        // LIVE 확정 후 종료 → ENDED.
        mockMvc.perform(post("/api/broadcast/lives/$live2/confirm").cookie(session).header("X-Broadcast-Token", token2))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/broadcast/lives/$live2/end").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
    }

    @Autowired
    lateinit var stubIvs: StubIvsService

    @Autowired
    lateinit var broadcastService: BroadcastService

    @Test
    fun `동시 시작 경쟁에서도 IVS Channel은 하나만 생성된다`() {
        val session = ownerSession("bc-race@test.local")
        val p = createProduct(session, "경쟁 상품")
        val live1 = createLiveWithProducts(session, "경쟁 방송 1", listOf(p))
        val live2 = createLiveWithProducts(session, "경쟁 방송 2", listOf(p))
        val userId = mapper.readTree(
            mockMvc.perform(get("/api/auth/me").cookie(session)).andReturn().response.contentAsString,
        ).get("id").asLong()

        val before = stubIvs.created.get()
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val successes = AtomicInteger()
        listOf(live1, live2).forEach { id ->
            executor.submit {
                start.await()
                try {
                    broadcastService.start(userId, id)
                    successes.incrementAndGet()
                } catch (e: Exception) { /* 409 허용 */ }
            }
        }
        start.countDown()
        executor.shutdown()
        check(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))

        // Shop 잠금이 AWS 호출 전에 슬롯을 확정하므로 Channel은 정확히 1개만 생성된다.
        assert(successes.get() == 1) { "시작 성공은 1건이어야 한다: ${successes.get()}" }
        assert(stubIvs.created.get() - before == 1) { "Channel 생성은 1회여야 한다: ${stubIvs.created.get() - before}" }
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

        val gToken = startLive(streamerSession, liveId)
        mockMvc.perform(
            post("/api/broadcast/lives/$liveId/confirm").cookie(streamerSession).header("X-Broadcast-Token", gToken),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))

        // 시작 요청자가 기록되고 Owner 목록에서도 방송중으로 보인다.
        mockMvc.perform(get("/api/broadcast/lives").cookie(session))
            .andExpect(jsonPath("$[0].status").value("LIVE"))
    }

    @Autowired
    lateinit var liveService: com.liverepublic.server.live.LiveService

    @Test
    fun `Owner 취소와 방송 시작이 경합해도 한쪽만 성공한다`() {
        val session = ownerSession("bc-cancel-race@test.local")
        val p = createProduct(session, "취소 경합 상품")
        val userId = mapper.readTree(
            mockMvc.perform(get("/api/auth/me").cookie(session)).andReturn().response.contentAsString,
        ).get("id").asLong()

        repeat(3) { i ->
            val liveId = createLiveWithProducts(session, "취소 경합 $i", listOf(p))
            val start = java.util.concurrent.CountDownLatch(1)
            val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
            val outcomes = java.util.concurrent.ConcurrentLinkedQueue<String>()
            executor.submit {
                start.await()
                try {
                    liveService.cancel(userId, liveId)
                    outcomes += "cancelled"
                } catch (e: Exception) { /* 409 허용 */ }
            }
            executor.submit {
                start.await()
                try {
                    broadcastService.start(userId, liveId)
                    outcomes += "started"
                } catch (e: Exception) { /* 409 허용 */ }
            }
            start.countDown()
            executor.shutdown()
            check(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))

            // 잠금으로 직렬화되므로 정확히 한쪽만 성공하고, 최종 상태가 그 결과와 일치한다.
            assert(outcomes.size == 1) { "한쪽만 성공해야 한다: $outcomes" }
            val status = mapper.readTree(
                mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session))
                    .andReturn().response.contentAsString,
            ).get("status").asText()
            val expected = if (outcomes.first() == "cancelled") "CANCELLED" else "STARTING"
            assert(status == expected) { "최종 상태 불일치: $status vs $outcomes" }
            // 다음 반복을 위해 STARTING이면 시작을 취소한다.
            if (status == "STARTING") {
                mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session))
                    .andExpect(status().isOk)
            }
        }
    }

    @Test
    fun `종료는 멱등이며 Stream Key가 폐기되고 재시작 시 새 Key가 발급된다`() {
        val session = ownerSession("bc-key@test.local")
        val p = createProduct(session, "키 폐기 상품")
        val liveId = createLiveWithProducts(session, "키 폐기 방송", listOf(p))

        val startBody = mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(session))
            .andReturn().response.contentAsString
        val firstKey = mapper.readTree(startBody).get("streamKey").asText()
        val token = mapper.readTree(startBody).get("broadcastToken").asText()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(status().isOk)

        val deletedBefore = stubIvs.keysDeleted.get()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
        assert(stubIvs.keysDeleted.get() == deletedBefore + 1) { "종료 시 Stream Key를 폐기해야 한다" }

        // 멱등: 응답 유실 후 재요청도 성공으로 응답한다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))

        // 같은 Channel을 재사용하는 다른 Live 흐름: 시작 취소(SCHEDULED 복귀) 후 재시작 시 새 Key.
        val live2 = createLiveWithProducts(session, "재발급 방송", listOf(p))
        startLive(session, live2)
        mockMvc.perform(post("/api/broadcast/lives/$live2/end").cookie(session))
            .andExpect(jsonPath("$.status").value("SCHEDULED")) // 시작 취소 — Key는 폐기됨
        val rekeyed = mapper.readTree(
            mockMvc.perform(post("/api/broadcast/lives/$live2/start").cookie(session))
                .andReturn().response.contentAsString,
        ).get("streamKey").asText()
        assert(rekeyed.startsWith("sk_stub_re_")) { "재시작 시 새 Key가 발급돼야 한다: $rekeyed" }
        assert(rekeyed != firstKey)
        mockMvc.perform(post("/api/broadcast/lives/$live2/end").cookie(session)).andExpect(status().isOk)
    }

    @Test
    fun `송출 자격은 임대 토큰을 제시한 방송 단말에만 응답된다`() {
        val session = ownerSession("bc-lease@test.local")
        val p = createProduct(session, "임대 상품")
        val liveId = createLiveWithProducts(session, "임대 방송", listOf(p))

        // 같은 Shop의 Streamer 서브계정 준비
        mockMvc.perform(
            post("/api/streamers").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"lease-streamer","temporaryPassword":"temp-pass-123","name":"임대"}"""),
        ).andExpect(status().isCreated)
        val streamerSession = login("lease-streamer", "temp-pass-123")
        mockMvc.perform(
            post("/api/auth/password").cookie(streamerSession).contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"temp-pass-123","newPassword":"changed-pass-456"}"""),
        ).andExpect(status().isOk)

        // Owner 단말 A가 시작 — 임대 토큰을 받는다.
        val token = startLive(session, liveId)

        // 같은 계정의 다른 단말(토큰 없음)에도 자격이 내려가지 않는다 — 계정이 아닌 단말 기준.
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.canControl").value(false))
            .andExpect(jsonPath("$.canBroadcast").value(true))
        // 임대 단말에는 내려간다.
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.startsWith("sk_stub")))
            .andExpect(jsonPath("$.canControl").value(true))
        // 다른 계정(Streamer)에는 상태만 보인다.
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(streamerSession))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.canBroadcast").value(false))

        // 임대 갱신(start 재호출)은 시작 계정만 — 토큰이 회전되어 이전 단말은 무효화된다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/start").cookie(streamerSession))
            .andExpect(status().isConflict)
        val rotated = startLive(session, liveId)
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session).header("X-Broadcast-Token", token))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.nullValue())) // 이전 토큰 무효
        mockMvc.perform(get("/api/broadcast/lives/$liveId").cookie(session).header("X-Broadcast-Token", rotated))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.startsWith("sk_stub")))

        // 확정·전환은 임대 단말만, 종료는 임대 단말 또는 Owner(강제)만.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(session).header("X-Broadcast-Token", rotated))
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(streamerSession))
            .andExpect(status().isForbidden)
        // Owner 계정은 토큰 없이 강제 종료할 수 있다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
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
