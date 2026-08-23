package com.liverepublic.server.status

import com.liverepublic.server.auth.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(StatusController::class)
@Import(SecurityConfig::class)
class StatusControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `상태 응답을 반환한다`() {
        mockMvc.perform(get("/api/status"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.service").value("live-republic-server"))
            .andExpect(jsonPath("$.status").value("ok"))
    }
}
