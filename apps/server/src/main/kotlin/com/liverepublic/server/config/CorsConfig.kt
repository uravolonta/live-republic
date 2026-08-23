package com.liverepublic.server.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Web Client(Vercel Preview, 로컬 개발)에서 API를 호출할 수 있게 하는 CORS 설정.
 * 허용 Origin은 배포 환경변수로 제어한다.
 */
@Configuration
class CorsConfig(
    @Value("\${cors.allowed-origin-patterns}") private val allowedOriginPatterns: List<String>,
) : WebMvcConfigurer {

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(*allowedOriginPatterns.toTypedArray())
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
    }
}
