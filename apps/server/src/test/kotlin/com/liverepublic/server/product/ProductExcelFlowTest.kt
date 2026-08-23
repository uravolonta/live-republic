package com.liverepublic.server.product

import com.liverepublic.server.TestcontainersConfiguration
import jakarta.servlet.http.Cookie
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayOutputStream

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class ProductExcelFlowTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun ownerSession(email: String): Cookie {
        mockMvc.perform(
            post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"$email","password":"password-123","name":"Excel Owner"}"""),
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

    /** rows: 헤더를 제외한 데이터 행. */
    private fun xlsx(rows: List<List<String>>): MockMultipartFile {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet("상품")
            val header = sheet.createRow(0)
            ProductExcelService.HEADERS.forEachIndexed { i, name -> header.createCell(i).setCellValue(name) }
            rows.forEachIndexed { ri, values ->
                val row = sheet.createRow(ri + 1)
                values.forEachIndexed { ci, value -> row.createCell(ci).setCellValue(value) }
            }
            val out = ByteArrayOutputStream()
            workbook.write(out)
            return MockMultipartFile(
                "file", "products.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                out.toByteArray(),
            )
        }
    }

    @Test
    fun `템플릿을 내려받을 수 있다`() {
        val session = ownerSession("excel-template@test.local")
        mockMvc.perform(get("/api/products/excel/template").cookie(session))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString(".xlsx")))
    }

    @Test
    fun `여러 상품과 SKU 재고를 한 번에 등록한다`() {
        val session = ownerSession("excel-upload@test.local")
        val file = xlsx(
            listOf(
                listOf("티셔츠", "15000", "부드러운 면", "색상", "빨강", "사이즈", "M", "", "", "10"),
                listOf("티셔츠", "15000", "부드러운 면", "색상", "빨강", "사이즈", "L", "", "", "5"),
                listOf("티셔츠", "15000", "부드러운 면", "색상", "파랑", "사이즈", "M", "", "", "0"),
                listOf("티셔츠", "15000", "부드러운 면", "색상", "파랑", "사이즈", "L", "", "", "8"),
                listOf("양말", "3000", "", "", "", "", "", "", "", "30"),
            ),
        )
        mockMvc.perform(multipart("/api/products/excel").file(file).cookie(session))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.createdProducts").value(2))
            .andExpect(jsonPath("$.createdSkus").value(5))

        // 재고가 행대로 반영됐는지 확인
        val list = mockMvc.perform(get("/api/products").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andReturn().response.contentAsString
        assert(list.contains("\"available\":10")) { "빨강/M 재고 10이 없다: $list" }
        assert(list.contains("\"available\":30")) { "양말 재고 30이 없다: $list" }
    }

    @Test
    fun `오류 행이 있으면 아무것도 등록되지 않고 행별 이유를 반환한다`() {
        val session = ownerSession("excel-invalid@test.local")
        val file = xlsx(
            listOf(
                listOf("모자", "9000", "", "색상", "검정", "", "", "", "", "10"),
                listOf("모자", "가격오류", "", "색상", "흰색", "", "", "", "", "5"),
                listOf("모자", "9000", "", "색상", "검정", "", "", "", "", "3"), // 중복 조합
            ),
        )
        mockMvc.perform(multipart("/api/products/excel").file(file).cookie(session))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors").isArray)
            .andExpect(jsonPath("$.errors[0].row").value(3))

        // 부분 등록 없음
        mockMvc.perform(get("/api/products").cookie(session))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `같은 상품의 가격이 행마다 다르면 거절된다`() {
        val session = ownerSession("excel-price@test.local")
        val file = xlsx(
            listOf(
                listOf("가방", "20000", "", "색상", "갈색", "", "", "", "", "1"),
                listOf("가방", "25000", "", "색상", "검정", "", "", "", "", "1"),
            ),
        )
        mockMvc.perform(multipart("/api/products/excel").file(file).cookie(session))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.containsString("가격")))
    }

    @Test
    fun `로그인하지 않으면 업로드할 수 없다`() {
        val file = xlsx(listOf(listOf("상품", "1000", "", "", "", "", "", "", "", "1")))
        mockMvc.perform(multipart("/api/products/excel").file(file))
            .andExpect(status().isUnauthorized)
    }
}
