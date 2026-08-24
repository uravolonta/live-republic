package com.liverepublic.server.product

import com.liverepublic.server.TestcontainersConfiguration
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProductLifecycleTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var skuRepository: SkuRepository

    private val mapper = JsonMapper.builder().build()

    private fun ownerSession(email: String): Cookie {
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123","name":"Lifecycle Owner"}"""),
        ).andExpect(status().isCreated)
        val login = mockMvc.perform(
            post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123"}"""),
        ).andExpect(status().isOk).andReturn()
        val session = requireNotNull(login.response.getCookie("SESSION"))
        mockMvc.perform(
            post("/api/shops").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$email 의 상점"}"""),
        ).andExpect(status().isCreated)
        return session
    }

    private fun createProduct(session: Cookie, body: String): Long {
        val result = mockMvc.perform(
            post("/api/products").cookie(session).contentType(MediaType.APPLICATION_JSON).content(body),
        ).andExpect(status().isCreated).andReturn()
        return mapper.readTree(result.response.contentAsString).get("id").asLong()
    }

    @Test
    fun `삭제한 상품은 목록·상세·Live 라인업에서 사라진다`() {
        val session = ownerSession("del@test.local")
        val productId = createProduct(session, """{"name":"삭제될 상품","price":1000,"optionGroups":[]}""")
        val keepId = createProduct(session, """{"name":"남는 상품","price":2000,"optionGroups":[]}""")

        // Live에 연결해 둔다.
        val liveId = mapper.readTree(
            mockMvc.perform(
                post("/api/lives").cookie(session).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"title":"삭제 검증 방송","scheduledStartAt":"2027-05-01T20:00:00+09:00"}"""),
            ).andReturn().response.contentAsString,
        ).get("id").asLong()
        mockMvc.perform(
            put("/api/lives/$liveId/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"productIds":[$productId,$keepId]}"""),
        ).andExpect(status().isOk)

        mockMvc.perform(delete("/api/products/$productId").cookie(session))
            .andExpect(status().isNoContent)

        // 목록·상세에서 사라진다.
        mockMvc.perform(get("/api/products").cookie(session))
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("남는 상품"))
        mockMvc.perform(get("/api/products/$productId").cookie(session))
            .andExpect(status().isNotFound)

        // Live 라인업 표시와 상품 수에서도 제외된다.
        mockMvc.perform(get("/api/lives/$liveId").cookie(session))
            .andExpect(jsonPath("$.products.length()").value(1))
            .andExpect(jsonPath("$.products[0].name").value("남는 상품"))

        // 삭제된 상품은 Live에 새로 연결할 수 없다.
        mockMvc.perform(
            put("/api/lives/$liveId/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"productIds":[$productId]}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `Option 추가 시 기존 조합의 재고가 유지되고 새 조합이 생긴다`() {
        val session = ownerSession("addopt@test.local")
        val productId = createProduct(
            session,
            """{"name":"구조 변경 상품","price":5000,"optionGroups":[{"name":"색상","options":["검정"]}]}""",
        )
        val skuId = mapper.readTree(
            mockMvc.perform(get("/api/products/$productId").cookie(session)).andReturn().response.contentAsString,
        ).get("skus").get(0).get("id").asLong()
        mockMvc.perform(
            put("/api/products/$productId/skus/$skuId/inventory").cookie(session)
                .contentType(MediaType.APPLICATION_JSON).content("""{"onHand":7}"""),
        ).andExpect(status().isOk)

        // 색상에 흰색 추가
        mockMvc.perform(
            put("/api/products/$productId/options").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"optionGroups":[{"name":"색상","options":["검정","흰색"]}]}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.skus.length()").value(2))

        val skus = mapper.readTree(
            mockMvc.perform(get("/api/products/$productId").cookie(session)).andReturn().response.contentAsString,
        ).get("skus")
        val byLabel = (0 until skus.size()).associate { skus.get(it).get("optionLabel").asText() to skus.get(it) }
        assert(byLabel.getValue("검정").get("onHand").asInt() == 7) { "기존 재고가 유지돼야 한다" }
        assert(byLabel.getValue("흰색").get("onHand").asInt() == 0)
    }

    @Test
    fun `사라지는 조합은 보관되고 같은 조합을 다시 추가하면 이력이 복원된다`() {
        val session = ownerSession("archive@test.local")
        val productId = createProduct(
            session,
            """{"name":"보관 상품","price":5000,"optionGroups":[{"name":"색상","options":["검정","흰색"]}]}""",
        )
        // 흰색 SKU에 판매 이력(Sold)을 만들어 둔다 (#7 전이므로 직접 세팅).
        val whiteSku = skuRepository.findAllByProductIdOrderById(productId).first { it.optionLabel == "흰색" }
        whiteSku.onHand = 5
        whiteSku.sold = 3
        skuRepository.save(whiteSku)

        // 흰색 제거 → 보관
        mockMvc.perform(
            put("/api/products/$productId/options").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"optionGroups":[{"name":"색상","options":["검정"]}]}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.skus.length()").value(1))
            .andExpect(jsonPath("$.skus[0].optionLabel").value("검정"))

        // 데이터는 보존된다 (physical row 유지, archived).
        val archived = skuRepository.findAllByProductIdOrderById(productId).first { it.optionLabel == "흰색" }
        assert(archived.archivedAt != null && archived.sold == 3)

        // 같은 조합을 다시 추가하면 복원되어 이력이 유지된다.
        mockMvc.perform(
            put("/api/products/$productId/options").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"optionGroups":[{"name":"색상","options":["검정","흰색"]}]}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.skus.length()").value(2))
        val restored = skuRepository.findAllByProductIdOrderById(productId).first { it.optionLabel == "흰색" }
        assert(restored.archivedAt == null && restored.sold == 3 && restored.onHand == 5) { "복원 시 이력·재고 유지" }
    }

    @Test
    fun `확보 수량이 있는 SKU는 구조 변경·삭제가 거절된다`() {
        val session = ownerSession("reserved@test.local")
        val productId = createProduct(
            session,
            """{"name":"확보 상품","price":5000,"optionGroups":[{"name":"색상","options":["검정"]}]}""",
        )
        val sku = skuRepository.findAllByProductIdOrderById(productId).first()
        sku.onHand = 5
        sku.reserved = 2
        skuRepository.save(sku)

        // 검정을 없애는 구조 변경 거절
        mockMvc.perform(
            put("/api/products/$productId/options").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"optionGroups":[{"name":"색상","options":["흰색"]}]}"""),
        ).andExpect(status().isConflict)

        // 삭제 거절
        mockMvc.perform(delete("/api/products/$productId").cookie(session))
            .andExpect(status().isConflict)

        // 검정을 유지하는 변경(옵션 추가)은 허용된다.
        mockMvc.perform(
            put("/api/products/$productId/options").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"optionGroups":[{"name":"색상","options":["검정","흰색"]}]}"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `다른 Shop의 상품은 삭제·구조 변경할 수 없다`() {
        val sessionA = ownerSession("lc-a@test.local")
        val productId = createProduct(sessionA, """{"name":"A의 상품","price":1000,"optionGroups":[]}""")
        val sessionB = ownerSession("lc-b@test.local")

        mockMvc.perform(delete("/api/products/$productId").cookie(sessionB))
            .andExpect(status().isNotFound)
        mockMvc.perform(
            put("/api/products/$productId/options").cookie(sessionB).contentType(MediaType.APPLICATION_JSON)
                .content("""{"optionGroups":[]}"""),
        ).andExpect(status().isNotFound)
    }
}
