package com.liverepublic.server.viewer

import com.liverepublic.server.TestcontainersConfiguration
import com.liverepublic.server.broadcast.StubIvsConfiguration
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

/** Customer 비로그인 시청 API (Issue #6). */
@Import(TestcontainersConfiguration::class, StubIvsConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ViewerFlowTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val mapper = JsonMapper.builder().build()

    private fun signupOwner(email: String): Cookie {
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123","name":"Viewer Owner"}"""),
        ).andExpect(status().isCreated)
        val result = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123"}"""),
        ).andExpect(status().isOk).andReturn()
        val session = requireNotNull(result.response.getCookie("SESSION"))
        mockMvc.perform(
            post("/api/shops").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$email 의 상점"}"""),
        ).andExpect(status().isCreated)
        return session
    }

    private fun appLogin(email: String): Cookie {
        val result = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .header("X-Client", "streamer-app")
                .content("""{"email":"$email","password":"password-123"}"""),
        ).andExpect(status().isOk).andReturn()
        return requireNotNull(result.response.getCookie("SESSION"))
    }

    @Test
    fun `Shop 상시 URL 하나로 방송 여부와 시청 정보를 확인한다`() {
        val web = signupOwner("viewer-shop@test.local")
        mockMvc.perform(
            post("/api/products").cookie(web).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"상시 URL 상품","price":5000,"optionGroups":[{"name":"색상","options":["검정"]}]}"""),
        ).andExpect(status().isCreated)
        val shopId = mapper.readTree(
            mockMvc.perform(get("/api/auth/me").cookie(web)).andReturn().response.contentAsString,
        ).get("shopId").asLong()

        // 방송 전 — 같은 주소가 "방송 중 아님"을 알려준다 (비로그인).
        mockMvc.perform(get("/api/viewer/shops/$shopId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.shopName").isNotEmpty)
            .andExpect(jsonPath("$.live").value(org.hamcrest.Matchers.nullValue()))

        // 방송 시작 → 같은 주소에 진행 중 방송이 실린다.
        val app = appLogin("viewer-shop@test.local")
        val liveId = mapper.readTree(
            mockMvc.perform(post("/api/broadcast/start").cookie(app))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        ).get("id").asLong()
        mockMvc.perform(get("/api/viewer/shops/$shopId"))
            .andExpect(jsonPath("$.live.id").value(liveId))
            .andExpect(jsonPath("$.live.status").value("STARTING"))
        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app)).andExpect(status().isOk)
        mockMvc.perform(get("/api/viewer/shops/$shopId"))
            .andExpect(jsonPath("$.live.status").value("LIVE"))
            .andExpect(jsonPath("$.live.playbackUrl").isNotEmpty)

        // 종료 → 다시 "방송 중 아님".
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app)).andExpect(status().isOk)
        mockMvc.perform(get("/api/viewer/shops/$shopId"))
            .andExpect(jsonPath("$.live").value(org.hamcrest.Matchers.nullValue()))

        mockMvc.perform(get("/api/viewer/shops/999999")).andExpect(status().isNotFound)
    }

    @Test
    fun `비로그인 시청자가 방송 상태·현재 상품·품절 여부를 본다 - 재고 수치는 노출되지 않는다`() {
        val web = signupOwner("viewer-flow@test.local")
        // 상품 2개 — 구성이 없으면 판매 중 전체(최신 등록 순)가 연결되므로,
        // '시청 상품 1'을 나중에 만들어 현재 상품이 되게 한다. '검정'만 재고를 넣는다.
        mockMvc.perform(
            post("/api/products").cookie(web).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"시청 상품 2","price":9000,"optionGroups":[{"name":"색상","options":["빨강"]}]}"""),
        ).andExpect(status().isCreated)
        val p1 = mapper.readTree(
            mockMvc.perform(
                post("/api/products").cookie(web).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"시청 상품 1","price":15000,"optionGroups":[{"name":"색상","options":["검정","흰색"]}]}"""),
            ).andExpect(status().isCreated).andReturn().response.contentAsString,
        )
        val p1Id = p1.get("id").asLong()
        val blackSkuId = p1.get("skus").toList().first { it.get("optionLabel").asText() == "검정" }.get("id").asLong()
        mockMvc.perform(
            put("/api/products/$p1Id/skus/$blackSkuId/inventory").cookie(web)
                .contentType(MediaType.APPLICATION_JSON).content("""{"onHand":3}"""),
        ).andExpect(status().isOk)

        val app = appLogin("viewer-flow@test.local")
        val started = mapper.readTree(
            mockMvc.perform(post("/api/broadcast/start").cookie(app))
                .andExpect(status().isOk).andReturn().response.contentAsString,
        )
        val liveId = started.get("id").asLong()

        // STARTING 동안은 재생 정보가 내려가지 않는다 (곧 시작 안내용 상태만).
        mockMvc.perform(get("/api/viewer/lives/$liveId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("STARTING"))
            .andExpect(jsonPath("$.playbackUrl").value(org.hamcrest.Matchers.nullValue()))

        mockMvc.perform(post("/api/broadcast/lives/$liveId/confirm").cookie(app)).andExpect(status().isOk)

        // LIVE — 쿠키 없이 재생 URL·현재 상품·Option별 품절 여부를 받는다.
        val body = mockMvc.perform(get("/api/viewer/lives/$liveId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LIVE"))
            .andExpect(jsonPath("$.playbackUrl").value(org.hamcrest.Matchers.startsWith("https://")))
            .andExpect(jsonPath("$.currentProduct.name").value("시청 상품 1"))
            .andExpect(jsonPath("$.currentProduct.price").value(15000))
            .andExpect(jsonPath("$.currentProduct.soldOut").value(false))
            // 폴링을 CDN·브라우저 캐시가 흡수하도록 캐시 헤더가 내려간다.
            .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("s-maxage")))
            .andReturn().response.contentAsString
        // 재고 수치는 응답 어디에도 없다 (2026-08-29 정책) — 품절 여부만.
        assert(!body.contains("available") && !body.contains("onHand")) { "재고 수치가 노출되면 안 된다: $body" }
        val options = mapper.readTree(body).get("currentProduct").get("options")
        assert(options.toList().first { it.get("label").asText() == "검정" }.get("soldOut").asBoolean() == false)
        assert(options.toList().first { it.get("label").asText() == "흰색" }.get("soldOut").asBoolean() == true)

        // Streamer가 상품을 전환하면 시청 응답도 바뀐다 — 재고 없는 상품은 품절(true).
        val secondLp = started.get("products").toList().first { it.get("name").asText() == "시청 상품 2" }
            .get("liveProductId").asLong()
        mockMvc.perform(
            put("/api/broadcast/lives/$liveId/current-product").cookie(app)
                .contentType(MediaType.APPLICATION_JSON).content("""{"liveProductId":$secondLp}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(get("/api/viewer/lives/$liveId"))
            .andExpect(jsonPath("$.currentProduct.name").value("시청 상품 2"))
            .andExpect(jsonPath("$.currentProduct.soldOut").value(true))

        // 종료된 Live — 재생·상품 없이 종료 상태를 안내한다 (실패 Flow).
        mockMvc.perform(post("/api/broadcast/lives/$liveId/end").cookie(app)).andExpect(status().isOk)
        mockMvc.perform(get("/api/viewer/lives/$liveId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ENDED"))
            .andExpect(jsonPath("$.playbackUrl").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.currentProduct").value(org.hamcrest.Matchers.nullValue()))

        // 존재하지 않는 방송.
        mockMvc.perform(get("/api/viewer/lives/999999"))
            .andExpect(status().isNotFound)
    }
}
