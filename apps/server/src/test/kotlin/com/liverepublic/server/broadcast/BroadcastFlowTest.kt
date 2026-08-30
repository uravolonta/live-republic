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

    /** Channel별 실제 존재하는 Key ARN — 부분 실패의 고아 Key를 재현하기 위한 상태. */
    val channelKeys = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

    override fun createChannel(name: String): IvsChannel {
        val n = created.incrementAndGet()
        val channelArn = "arn:aws:ivs:stub:channel/$name-$n"
        val keyArn = "arn:aws:ivs:stub:stream-key/$name-$n"
        channelKeys[channelArn] = mutableListOf(keyArn)
        return IvsChannel(
            channelArn = channelArn,
            ingestEndpoint = "rtmps://stub.ingest:443/app/",
            streamKey = "sk_stub_$n",
            streamKeyArn = keyArn,
            playbackUrl = "https://stub.playback/$name.m3u8",
        )
    }

    override fun createStreamKey(channelArn: String): IvsStreamKey {
        val n = keysCreated.incrementAndGet()
        val arn = "arn:aws:ivs:stub:stream-key/re-$n"
        channelKeys.getOrPut(channelArn) { mutableListOf() }.add(arn)
        return IvsStreamKey(arn = arn, value = "sk_stub_re_$n")
    }

    override fun deleteStreamKey(streamKeyArn: String) {
        keysDeleted.incrementAndGet()
        channelKeys.values.forEach { it.remove(streamKeyArn) }
    }

    override fun listStreamKeyArns(channelArn: String): List<String> =
        channelKeys[channelArn].orEmpty().toList()

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

    @Autowired
    lateinit var stubIvs: StubIvsService

    @Autowired
    lateinit var broadcastService: BroadcastService

    @Autowired
    lateinit var streamSessionRepository: com.liverepublic.server.live.LiveStreamSessionRepository

    private val mapper = JsonMapper.builder().build()

    @org.junit.jupiter.api.BeforeEach
    fun resetStub() {
        stubIvs.reset()
    }

    private fun signupOwner(email: String): Cookie {
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123","name":"Broadcast Owner"}"""),
        ).andExpect(status().isCreated)
        val session = webLogin(email, "password-123")
        mockMvc.perform(
            post("/api/shops").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$email 의 상점"}"""),
        ).andExpect(status().isCreated)
        return session
    }

    /** Owner Web 로그인 — 앱 세션 규칙의 영향을 받지 않는다. */
    private fun webLogin(email: String, password: String): Cookie {
        val result = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"$password"}"""),
        ).andExpect(status().isOk).andReturn()
        return requireNotNull(result.response.getCookie("SESSION"))
    }

    /** 방송 앱 로그인 — 테넌트당 1개 세션만 허용된다. */
    private fun appLogin(email: String, password: String): Cookie {
        val result = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client", "streamer-app")
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

    /** SESSION 쿠키 값은 세션 ID의 Base64 인코딩이다 (spring-session 기본 직렬화). */
    private fun sessionIdOf(cookie: Cookie): String =
        String(java.util.Base64.getDecoder().decode(cookie.value))

    private fun startBroadcast(session: Cookie): tools.jackson.databind.JsonNode {
        val body = mockMvc.perform(post("/api/broadcast/start").cookie(session))
            .andExpect(status().isOk).andReturn().response.contentAsString
        return mapper.readTree(body)
    }

    @Test
    fun `방송 시작-확정-상품 전환-종료 전체 흐름`() {
        val web = signupOwner("bc-flow@test.local")
        createProduct(web, "방송 상품 1")
        createProduct(web, "방송 상품 2")
        val app = appLogin("bc-flow@test.local", "password-123")

        // 진행 중 방송 없음 → live = null
        mockMvc.perform(get("/api/broadcast/current").cookie(app))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.live").value(org.hamcrest.Matchers.nullValue()))

        // 즉시 시작 — 예약 선택 없이 새 Live가 만들어지고 자격이 내려간다.
        // 구성이 없으므로 판매 중 상품 전체가 연결된다.
        val started = startBroadcast(app)
        val liveId = started.get("id").asLong()
        assert(started.get("status").asText() == "STARTING")
        assert(started.get("ingestEndpoint").asText().startsWith("rtmps://"))
        assert(started.get("streamKey").asText().startsWith("sk_stub"))
        assert(started.get("products").size() == 2)
        val firstLp = started.get("products").get(0).get("liveProductId").asLong()
        val secondLp = started.get("products").get(1).get("liveProductId").asLong()
        assert(started.get("currentLiveProductId").asLong() == firstLp)

        // current에도 같은 방송이 보인다.
        mockMvc.perform(get("/api/broadcast/current").cookie(app))
            .andExpect(jsonPath("$.live.id").value(liveId))

        // SDK 연결 확인 → LIVE 확정 + Stream Session 이력 기록.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))
            .andExpect(jsonPath("$.startedAt").isNotEmpty)

        // 상품 전환
        mockMvc.perform(
            put("/api/broadcast/lives/$liveId/current-product").cookie(app)
                .contentType(MediaType.APPLICATION_JSON).content("""{"liveProductId":$secondLp}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.currentLiveProductId").value(secondLp))

        // 종료 → ENDED, 자격은 더 이상 내려가지 않는다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
            .andExpect(jsonPath("$.streamKey").value(org.hamcrest.Matchers.nullValue()))

        // 종료 후 current는 다시 비고, 새 시작은 새 Live를 만든다.
        mockMvc.perform(get("/api/broadcast/current").cookie(app))
            .andExpect(jsonPath("$.live").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `앱 세션은 테넌트당 1개 - 다른 계정은 Owner 로그아웃 후에만 접속된다`() {
        val web = signupOwner("bc-session@test.local")
        createProduct(web, "세션 상품")
        mockMvc.perform(
            post("/api/streamers").cookie(web).contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"session-streamer","temporaryPassword":"temp-pass-123","name":"세션 스트리머"}"""),
        ).andExpect(status().isCreated)

        // Streamer가 앱 로그인 → 슬롯 점유.
        val streamerApp = appLogin("session-streamer", "temp-pass-123")

        // 같은 테넌트의 다른 계정(Owner)은 앱 로그인이 거절된다.
        mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client", "streamer-app")
                .content("""{"email":"bc-session@test.local","password":"password-123"}"""),
        ).andExpect(status().isConflict)

        // Web 로그인은 앱 세션 규칙의 영향을 받지 않는다.
        webLogin("bc-session@test.local", "password-123")

        // Owner 대시보드에서 현재 앱 세션이 보인다.
        mockMvc.perform(get("/api/broadcast/app-session").cookie(web))
            .andExpect(jsonPath("$.session.accountName").value("세션 스트리머"))

        // Owner가 강제 로그아웃 → 기존 앱 세션은 401, 다른 계정 앱 로그인 가능.
        mockMvc.perform(post("/api/broadcast/app-session/logout").cookie(web))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.session").value(org.hamcrest.Matchers.nullValue()))
        mockMvc.perform(get("/api/broadcast/current").cookie(streamerApp))
            .andExpect(status().isUnauthorized)
        appLogin("bc-session@test.local", "password-123")
    }

    @Test
    fun `같은 계정의 앱 재로그인은 이전 세션을 자동 대체한다`() {
        val web = signupOwner("bc-relogin@test.local")
        createProduct(web, "재로그인 상품")
        val first = appLogin("bc-relogin@test.local", "password-123")
        val userId = mapper.readTree(
            mockMvc.perform(get("/api/auth/me").cookie(first)).andReturn().response.contentAsString,
        ).get("id").asLong()
        val second = appLogin("bc-relogin@test.local", "password-123")

        // 이전 앱 세션은 무효화되고(크래시 복구 경로), 새 세션만 유효하다.
        mockMvc.perform(get("/api/broadcast/current").cookie(first))
            .andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/broadcast/current").cookie(second))
            .andExpect(status().isOk)

        // 검사-사용(TOCTOU) 방어: 대체된 세션 ID로 서비스 조작이 직접 실행돼도
        // 트랜잭션 안의 잠금 검증이 403으로 거절한다 (Controller 통과 후 대체된 경우와 동일 경로).
        val staleAttempt = runCatching { broadcastService.start(userId, sessionIdOf(first)) }
        val ex = staleAttempt.exceptionOrNull() as? org.springframework.web.server.ResponseStatusException
        assert(ex?.statusCode?.value() == 403) { "대체된 세션의 시작은 403이어야 한다: $staleAttempt" }
    }

    @Test
    fun `최초 앱 로그인 2건이 동시에 들어와도 서버 오류 없이 한 세션으로 수렴한다`() {
        signupOwner("bc-firstlogin@test.local")
        // 이 테넌트는 app_session 행이 없다 — INSERT 경쟁 경로.
        val statuses = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        val startLatch = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        repeat(2) {
            executor.submit {
                startLatch.await()
                val result = mockMvc.perform(
                    post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Client", "streamer-app")
                        .content("""{"email":"bc-firstlogin@test.local","password":"password-123"}"""),
                ).andReturn()
                statuses += result.response.status
            }
        }
        startLatch.countDown()
        executor.shutdown()
        check(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))

        assert(statuses.all { it == 200 || it == 409 }) { "동시 최초 로그인이 서버 오류가 되면 안 된다: $statuses" }
        assert(statuses.count { it == 200 } >= 1) { "최소 한 건은 성공해야 한다: $statuses" }
    }

    @Test
    fun `진행 중 방송은 재시작 시 재개되고 이전 단말이 송출 중이면 거절된다`() {
        val web = signupOwner("bc-resume@test.local")
        createProduct(web, "재개 상품")
        val app = appLogin("bc-resume@test.local", "password-123")

        val started = startBroadcast(app)
        val liveId = started.get("id").asLong()
        val firstKey = started.get("streamKey").asText()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app))
            .andExpect(status().isOk)

        // 이전 단말이 아직 송출 중(GetStream 감지) → 재시작 거절, 대시보드 종료 안내.
        stubIvs.streamAvailable = true
        mockMvc.perform(post("/api/broadcast/start").cookie(app))
            .andExpect(status().isConflict)

        // 송출이 끊긴 뒤(크래시 복구) → 같은 Live·같은 Key로 재개된다 (회전 없음).
        stubIvs.streamAvailable = false
        val createdBefore = stubIvs.keysCreated.get()
        val resumed = startBroadcast(app)
        assert(resumed.get("id").asLong() == liveId) { "새 Live를 만들지 않고 재개해야 한다" }
        assert(resumed.get("streamKey").asText() == firstKey) { "재개는 Key를 회전하지 않는다" }
        assert(stubIvs.keysCreated.get() == createdBefore)

        stubIvs.streamAvailable = true
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app)).andExpect(status().isOk)
    }

    @Test
    fun `재개 시 DB와 IVS의 Key가 어긋나면 실제 목록 기준으로 재발급한다`() {
        val web = signupOwner("bc-rekey@test.local")
        createProduct(web, "재발급 상품")
        val app = appLogin("bc-rekey@test.local", "password-123")

        val channelsBefore = stubIvs.channelKeys.keys.toSet()
        val started = startBroadcast(app)
        val liveId = started.get("id").asLong()
        val channelArn = (stubIvs.channelKeys.keys - channelsBefore).single()

        // 부분 실패 상황 재현: IVS에는 DB가 모르는 고아 Key만 남아 있다.
        stubIvs.channelKeys[channelArn] = mutableListOf("arn:aws:ivs:stub:stream-key/orphan")

        stubIvs.streamAvailable = false
        val resumed = startBroadcast(app)
        assert(resumed.get("streamKey").asText().startsWith("sk_stub_re_")) { "고아 정리 후 새 Key가 발급돼야 한다" }
        assert(stubIvs.channelKeys[channelArn]!!.size == 1) { "Channel의 Key는 정확히 1개여야 한다" }

        stubIvs.streamAvailable = true
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app)).andExpect(status().isOk)
    }

    @Test
    fun `종료는 멱등이며 IVS의 실제 Key 전체를 폐기한다`() {
        val web = signupOwner("bc-endkeys@test.local")
        createProduct(web, "종료 상품")
        val app = appLogin("bc-endkeys@test.local", "password-123")

        val channelsBefore = stubIvs.channelKeys.keys.toSet()
        val started = startBroadcast(app)
        val liveId = started.get("id").asLong()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app)).andExpect(status().isOk)
        val channelArn = (stubIvs.channelKeys.keys - channelsBefore).single()

        // 부분 실패가 남긴 고아 Key까지 종료가 함께 폐기해야 한다.
        stubIvs.channelKeys[channelArn]!!.add("arn:aws:ivs:stub:stream-key/orphan-end")
        val deletedBefore = stubIvs.keysDeleted.get()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
        assert(stubIvs.keysDeleted.get() == deletedBefore + 2) { "실제 Key 2개(정상+고아)를 모두 폐기해야 한다" }
        assert(stubIvs.channelKeys[channelArn]!!.isEmpty()) { "종료 후 유효한 Key가 없어야 한다" }

        // 멱등: 재요청도 성공으로 응답한다.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
    }

    @Test
    fun `IVS 중단 실패 시 종료는 502로 실패하고 재시도로 종료된다`() {
        val web = signupOwner("bc-stop-fail@test.local")
        createProduct(web, "중단 실패 상품")
        val app = appLogin("bc-stop-fail@test.local", "password-123")
        val liveId = startBroadcast(app).get("id").asLong()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app)).andExpect(status().isOk)

        // 2회 모두 실패 → 종료 확정 없이 502, 상태는 LIVE 유지 (실송출 방치 방지).
        stubIvs.failStopsRemaining = 2
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app))
            .andExpect(status().isBadGateway)
        mockMvc.perform(get("/api/broadcast/current").cookie(app))
            .andExpect(jsonPath("$.live.status").value("LIVE"))

        // 사용자 재시도 → 성공 → ENDED.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
    }

    @Test
    fun `Owner 강제 로그아웃은 진행 중 방송을 먼저 종료한다`() {
        val web = signupOwner("bc-forcelogout@test.local")
        createProduct(web, "강제 상품")
        mockMvc.perform(
            post("/api/streamers").cookie(web).contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"force-streamer","temporaryPassword":"temp-pass-123","name":"강제"}"""),
        ).andExpect(status().isCreated)
        val app = appLogin("force-streamer", "temp-pass-123")
        mockMvc.perform(
            post("/api/auth/password").cookie(app).contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"temp-pass-123","newPassword":"changed-pass-456"}"""),
        ).andExpect(status().isOk)

        val liveId = startBroadcast(app).get("id").asLong()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app)).andExpect(status().isOk)

        // Owner가 대시보드에서 강제 로그아웃 → 방송 종료(Key 폐기+중단) 후 세션 무효화.
        val deletedBefore = stubIvs.keysDeleted.get()
        val stopsBefore = stubIvs.stops.get()
        mockMvc.perform(post("/api/broadcast/app-session/logout").cookie(web))
            .andExpect(status().isOk)
        assert(stubIvs.keysDeleted.get() > deletedBefore) { "강제 로그아웃은 Key를 폐기해야 한다" }
        assert(stubIvs.stops.get() == stopsBefore + 1) { "강제 로그아웃은 송출을 중단해야 한다" }
        mockMvc.perform(get("/api/broadcast/current").cookie(web))
            .andExpect(jsonPath("$.live").value(org.hamcrest.Matchers.nullValue()))
        mockMvc.perform(get("/api/broadcast/current").cookie(app))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `상품 사전 구성이 있으면 그 순서로 연결된다`() {
        val web = signupOwner("bc-config@test.local")
        val p1 = createProduct(web, "구성 상품 1")
        val p2 = createProduct(web, "구성 상품 2")
        createProduct(web, "구성 제외 상품")

        // Owner가 구성 저장 — p2를 먼저.
        mockMvc.perform(
            put("/api/broadcast/config/products").cookie(web).contentType(MediaType.APPLICATION_JSON)
                .content("""{"productIds":[$p2,$p1]}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].productId").value(p2))

        val app = appLogin("bc-config@test.local", "password-123")
        val started = startBroadcast(app)
        assert(started.get("products").size() == 2) { "구성된 상품만 연결돼야 한다" }
        assert(started.get("products").get(0).get("productId").asLong() == p2) { "구성 순서를 따라야 한다" }
        mockMvc.perform(post("/api/broadcast/lives/${started.get("id").asLong()}/end").cookie(app))
            .andExpect(status().isOk)
    }

    @Test
    fun `판매 중 상품이 없으면 시작할 수 없다`() {
        signupOwner("bc-noproduct@test.local")
        val app = appLogin("bc-noproduct@test.local", "password-123")
        mockMvc.perform(post("/api/broadcast/start").cookie(app))
            .andExpect(status().isConflict)
    }

    @Test
    fun `동시 시작 경쟁에서도 IVS Channel은 하나만 생성된다`() {
        val web = signupOwner("bc-race@test.local")
        createProduct(web, "경쟁 상품")
        val app = appLogin("bc-race@test.local", "password-123")
        val userId = mapper.readTree(
            mockMvc.perform(get("/api/auth/me").cookie(app)).andReturn().response.contentAsString,
        ).get("id").asLong()

        // 한쪽은 생성, 다른 쪽은 재개(잠금 직렬화) — Channel은 1개만 만들어진다.
        stubIvs.streamAvailable = false
        val appSessionId = sessionIdOf(app)
        val before = stubIvs.created.get()
        val start = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val liveIds = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        repeat(2) {
            executor.submit {
                start.await()
                try {
                    liveIds += broadcastService.start(userId, appSessionId).id!!
                } catch (e: Exception) { /* 409 허용 */ }
            }
        }
        start.countDown()
        executor.shutdown()
        check(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))

        assert(stubIvs.created.get() - before == 1) { "Channel 생성은 1회여야 한다: ${stubIvs.created.get() - before}" }
        assert(liveIds.toSet().size == 1) { "두 요청 모두 같은 Live로 수렴해야 한다: $liveIds" }
        mockMvc.perform(post("/api/broadcast/lives/${liveIds.first()}/end").cookie(app))
            .andExpect(status().isOk)
    }

    @Test
    fun `재연결로 새 Stream Session이 생기면 이력에 추가된다`() {
        val web = signupOwner("bc-session-history@test.local")
        createProduct(web, "이력 상품")
        val app = appLogin("bc-session-history@test.local", "password-123")
        val liveId = startBroadcast(app).get("id").asLong()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app)).andExpect(status().isOk)

        stubIvs.streamIdSuffix = "b"
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))

        val sessions = streamSessionRepository.findAllByLiveIdOrderById(liveId)
        assert(sessions.size == 2) { "Stream Session 이력은 2행이어야 한다: ${sessions.map { it.ivsStreamId }}" }
        assert(sessions[0].endedAt != null) { "이전 Session은 닫혀야 한다" }
        assert(sessions[1].endedAt == null && sessions[1].ivsStreamId.endsWith("-b"))

        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app)).andExpect(status().isOk)
        assert(streamSessionRepository.findAllByLiveIdOrderById(liveId).all { it.endedAt != null })
    }

    @Test
    fun `같은 계정이라도 단말(앱 세션)이 아니면 자격을 받거나 시작·확정·전환할 수 없다`() {
        val web = signupOwner("bc-device@test.local")
        createProduct(web, "단말 상품")
        val app = appLogin("bc-device@test.local", "password-123")

        // 시작은 방송 단말 세션 전용 — 같은 계정의 Web 세션도 403.
        mockMvc.perform(post("/api/broadcast/start").cookie(web))
            .andExpect(status().isForbidden)

        val started = startBroadcast(app)
        val liveId = started.get("id").asLong()
        val lp = started.get("products").get(0).get("liveProductId").asLong()

        // Web 세션의 상태 조회는 가능하지만 송출 자격(streamKey)은 내려가지 않는다.
        mockMvc.perform(get("/api/broadcast/current").cookie(web))
            .andExpect(jsonPath("$.live.id").value(liveId))
            .andExpect(jsonPath("$.live.streamKey").value(org.hamcrest.Matchers.nullValue()))
        mockMvc.perform(get("/api/broadcast/current").cookie(app))
            .andExpect(jsonPath("$.live.streamKey").value(org.hamcrest.Matchers.startsWith("sk_stub")))

        // 확정(사용량 이력)·상품 전환도 방송 단말 전용.
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(web))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            put("/api/broadcast/lives/$liveId/current-product").cookie(web)
                .contentType(MediaType.APPLICATION_JSON).content("""{"liveProductId":$lp}"""),
        ).andExpect(status().isForbidden)

        // 같은 Shop이라도 단말·Owner가 아닌 Streamer Web 세션은 종료할 수 없다.
        mockMvc.perform(
            post("/api/streamers").cookie(web).contentType(MediaType.APPLICATION_JSON)
                .content("""{"loginId":"device-streamer","temporaryPassword":"temp-pass-123","name":"단말 외"}"""),
        ).andExpect(status().isCreated)
        val streamerWeb = webLogin("device-streamer", "temp-pass-123")
        mockMvc.perform(
            post("/api/auth/password").cookie(streamerWeb).contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"temp-pass-123","newPassword":"changed-pass-456"}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(streamerWeb))
            .andExpect(status().isForbidden)

        // 종료는 Owner 대시보드(Web)에서는 가능해야 한다 (강제 종료 경로).
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(web))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
    }

    @Test
    fun `앱 세션의 자발적 로그아웃은 진행 중 방송을 먼저 종료한다`() {
        val web = signupOwner("bc-logout@test.local")
        createProduct(web, "로그아웃 상품")
        val app = appLogin("bc-logout@test.local", "password-123")
        val liveId = startBroadcast(app).get("id").asLong()
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app)).andExpect(status().isOk)

        // 로그아웃이 슬롯만 비우면 이미 전달된 Key로 송출이 계속된다 — 종료를 선행해야 한다.
        val deletedBefore = stubIvs.keysDeleted.get()
        val stopsBefore = stubIvs.stops.get()
        mockMvc.perform(post("/api/auth/logout").cookie(app)).andExpect(status().isNoContent)
        assert(stubIvs.keysDeleted.get() > deletedBefore) { "로그아웃은 Key 폐기를 선행해야 한다" }
        assert(stubIvs.stops.get() == stopsBefore + 1) { "로그아웃은 송출 중단을 선행해야 한다" }
        mockMvc.perform(get("/api/broadcast/current").cookie(web))
            .andExpect(jsonPath("$.live").value(org.hamcrest.Matchers.nullValue()))
        // 슬롯이 비어 다른 흐름이 이어질 수 있다.
        appLogin("bc-logout@test.local", "password-123")
    }

    @Autowired
    lateinit var membershipRepository: com.liverepublic.server.tenant.MembershipRepository

    @Test
    fun `다중 Membership에서도 앱 세션과 방송은 같은 테넌트 규칙을 쓴다`() {
        // A: 자기 테넌트의 OWNER. B 테넌트의 STREAMER Membership을 직접 추가해
        // (API로는 아직 만들 수 없는 데이터) 다중 소속을 재현한다.
        val webA = signupOwner("bc-multi-a@test.local")
        createProduct(webA, "A 테넌트 상품")
        signupOwner("bc-multi-b@test.local")
        val userA = mapper.readTree(
            mockMvc.perform(get("/api/auth/me").cookie(webA)).andReturn().response.contentAsString,
        ).get("id").asLong()
        val tenantB = membershipRepository.findAllByUserId(
            mapper.readTree(
                mockMvc.perform(get("/api/auth/me").cookie(webLogin("bc-multi-b@test.local", "password-123")))
                    .andReturn().response.contentAsString,
            ).get("id").asLong(),
        ).first().tenantId
        membershipRepository.save(
            com.liverepublic.server.tenant.Membership(
                userId = userA, tenantId = tenantB, role = com.liverepublic.server.tenant.MembershipRole.STREAMER,
            ),
        )

        // OWNER 우선 규칙: 앱 세션 점유 테넌트와 방송 Shop 모두 A의 테넌트여야 한다.
        val app = appLogin("bc-multi-a@test.local", "password-123")
        val started = startBroadcast(app)
        assert(started.get("products").get(0).get("name").asText() == "A 테넌트 상품") {
            "방송은 OWNER 테넌트의 Shop에서 시작돼야 한다"
        }
        // A의 Owner Web 대시보드에서 자기 테넌트의 앱 세션이 보인다 (같은 테넌트를 점유했다는 증거).
        mockMvc.perform(get("/api/broadcast/app-session").cookie(webA))
            .andExpect(jsonPath("$.session.accountName").isNotEmpty)
        mockMvc.perform(post("/api/broadcast/lives/${started.get("id").asLong()}/end").cookie(app))
            .andExpect(status().isOk)
    }

    @Test
    fun `다른 Shop 사용자는 남의 방송을 조작할 수 없다`() {
        val webA = signupOwner("bc-a@test.local")
        createProduct(webA, "A 상품")
        val appA = appLogin("bc-a@test.local", "password-123")
        val liveId = startBroadcast(appA).get("id").asLong()

        signupOwner("bc-b@test.local")
        val appB = appLogin("bc-b@test.local", "password-123")
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(appB))
            .andExpect(status().isNotFound)
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(appA))
            .andExpect(status().isOk)
    }
}
