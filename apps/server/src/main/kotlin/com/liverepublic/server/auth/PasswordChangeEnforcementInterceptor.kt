package com.liverepublic.server.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 임시 비밀번호 상태(mustChangePassword)의 사용자는 비밀번호를 변경하기 전까지
 * 내 계정 조회·비밀번호 변경·로그아웃 외의 보호 기능을 사용할 수 없다. (Issue #4)
 */
@Component
class PasswordChangeEnforcementInterceptor : HandlerInterceptor {

    private val allowedPaths = setOf(
        "/api/auth/me",
        "/api/auth/password",
        "/api/auth/logout",
        "/api/auth/login",
        "/api/auth/signup",
        "/api/status",
    )

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? AuthUser
            ?: return true
        if (!principal.mustChangePassword) return true
        if (request.requestURI in allowedPaths || !request.requestURI.startsWith("/api/")) return true
        // 클라이언트가 다른 사유의 403과 구분할 수 있도록 전용 헤더를 함께 내린다.
        response.setHeader("X-Password-Change-Required", "true")
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "비밀번호를 변경한 뒤 사용할 수 있습니다.")
        return false
    }
}

@Configuration
class PasswordChangeEnforcementConfig(
    private val interceptor: PasswordChangeEnforcementInterceptor,
) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**")
    }
}
