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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.json.JsonMapper

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProductFlowTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private val mapper = JsonMapper.builder().build()

    /** 가입 → 로그인 → Shop 생성까지 마친 Owner 세션을 만든다. */
    private fun ownerSession(email: String): Cookie {
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123","name":"상품 Owner"}"""),
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
    fun `Option 2그룹 상품은 조합별 SKU 4개가 생성된다`() {
        val session = ownerSession("product-combo@test.local")
        val productId = createProduct(
            session,
            """{"name":"티셔츠","price":15000,"description":"부드러운 면",
               "optionGroups":[{"name":"색상","options":["빨강","파랑"]},{"name":"사이즈","options":["M","L"]}]}""",
        )

        mockMvc.perform(get("/api/products/$productId").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("티셔츠"))
            .andExpect(jsonPath("$.skus.length()").value(4))
            .andExpect(jsonPath("$.skus[0].optionLabel").value("빨강 / M"))
            .andExpect(jsonPath("$.skus[3].optionLabel").value("파랑 / L"))
            .andExpect(jsonPath("$.skus[0].available").value(0))
    }

    @Test
    fun `Option 없는 상품은 기본 SKU 하나가 생성된다`() {
        val session = ownerSession("product-single@test.local")
        val productId = createProduct(session, """{"name":"양말","price":3000,"optionGroups":[]}""")

        mockMvc.perform(get("/api/products/$productId").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.skus.length()").value(1))
            .andExpect(jsonPath("$.skus[0].optionLabel").value("기본"))
    }

    @Test
    fun `SKU 수량을 설정하면 Available에 반영된다`() {
        val session = ownerSession("product-stock@test.local")
        val productId = createProduct(
            session,
            """{"name":"모자","price":9000,"optionGroups":[{"name":"색상","options":["검정"]}]}""",
        )
        val skuId = mapper.readTree(
            mockMvc.perform(get("/api/products/$productId").cookie(session))
                .andReturn().response.contentAsString,
        ).get("skus").get(0).get("id").asLong()

        mockMvc.perform(
            put("/api/products/$productId/skus/$skuId/inventory").cookie(session)
                .contentType(MediaType.APPLICATION_JSON).content("""{"onHand":30}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.onHand").value(30))
            .andExpect(jsonPath("$.available").value(30))
    }

    @Test
    fun `상품 기본 정보를 수정해도 SKU 구조는 유지된다`() {
        val session = ownerSession("product-edit@test.local")
        val productId = createProduct(
            session,
            """{"name":"가방","price":20000,"optionGroups":[{"name":"색상","options":["갈색","검정"]}]}""",
        )

        mockMvc.perform(
            put("/api/products/$productId").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"가죽 가방","price":25000,"description":"천연 가죽"}"""),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("가죽 가방"))
            .andExpect(jsonPath("$.price").value(25000))
            .andExpect(jsonPath("$.skus.length()").value(2))
    }

    @Test
    fun `다른 Shop의 상품은 조회·수정할 수 없다`() {
        val sessionA = ownerSession("product-a@test.local")
        val productId = createProduct(sessionA, """{"name":"A의 상품","price":1000,"optionGroups":[]}""")

        val sessionB = ownerSession("product-b@test.local")
        mockMvc.perform(get("/api/products/$productId").cookie(sessionB))
            .andExpect(status().isNotFound)
        mockMvc.perform(
            put("/api/products/$productId").cookie(sessionB).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"탈취 시도","price":1}"""),
        ).andExpect(status().isNotFound)

        // B의 목록에도 A의 상품이 보이지 않는다.
        mockMvc.perform(get("/api/products").cookie(sessionB))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `잘못된 입력은 400으로 거절된다`() {
        val session = ownerSession("product-invalid@test.local")

        // 음수 가격
        mockMvc.perform(
            post("/api/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"불량","price":-1,"optionGroups":[]}"""),
        ).andExpect(status().isBadRequest)

        // 중복 Option
        mockMvc.perform(
            post("/api/products").cookie(session).contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"불량","price":1000,"optionGroups":[{"name":"색상","options":["빨강","빨강"]}]}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `로그인하지 않으면 상품에 접근할 수 없다`() {
        mockMvc.perform(get("/api/products")).andExpect(status().isUnauthorized)
    }
}
