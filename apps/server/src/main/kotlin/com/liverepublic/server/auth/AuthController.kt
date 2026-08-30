package com.liverepublic.server.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class SignupRequest(
    @field:Email @field:NotBlank val email: String,
    // 정책(2026-08-23 사람 결정): 비밀번호는 영문 대소문자·숫자·특수문자만 허용한다.
    // [!-~]는 공백을 제외한 인쇄 가능한 ASCII 전체이므로 한글 등 다중 바이트 문자를 거른다.
    @field:Size(min = 8, max = 72)
    @field:Pattern(regexp = "^[!-~]+$", message = "비밀번호는 영문 대소문자, 숫자, 특수문자만 사용할 수 있습니다.")
    val password: String,
    @field:NotBlank @field:Size(max = 100) val name: String,
)

data class LoginRequest(
    @field:NotBlank val email: String,
    @field:NotBlank val password: String,
)

data class MeResponse(
    val id: Long,
    val email: String,
    val name: String,
    val shopId: Long?,
    val mustChangePassword: Boolean,
    val isStreamer: Boolean,
)

data class ChangePasswordRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank val newPassword: String,
)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
    private val securityContextRepository: SecurityContextRepository,
    private val appSessionService: AppSessionService,
    private val broadcastService: com.liverepublic.server.broadcast.BroadcastService,
) {

    companion object {
        const val STREAMER_APP_CLIENT = "streamer-app"
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: SignupRequest): MeResponse {
        val user = authService.signup(request.email.trim(), request.password, request.name.trim())
        return MeResponse(
            id = user.id, email = user.email, name = user.name, shopId = null,
            mustChangePassword = false, isStreamer = false,
        )
    }

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        @org.springframework.web.bind.annotation.RequestHeader(name = "X-Client", required = false) client: String?,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): MeResponse {
        val user = authService.authenticate(request.email.trim(), request.password)

        // 세션 고정 공격 방지를 위해 로그인 시 세션을 새로 발급한다.
        httpRequest.getSession(false)?.invalidate()
        val session = httpRequest.getSession(true)
        // 방송 앱은 테넌트당 1개 세션만 허용한다 (2026-08-28 사람 결정) —
        // 같은 계정 재로그인은 이전 세션을 대체하고, 다른 계정은 거절된다.
        if (client == STREAMER_APP_CLIENT) {
            try {
                appSessionService.claim(user.id, session.id)
            } catch (e: Exception) {
                session.invalidate()
                throw e
            }
        }
        saveAuthentication(user, httpRequest, httpResponse)

        return toMeResponse(user)
    }

    @PostMapping("/password")
    fun changePassword(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: ChangePasswordRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ): MeResponse {
        val updated = authService.changePassword(user.id, request.currentPassword, request.newPassword)
        // Session Principal의 mustChangePassword 상태를 즉시 갱신한다.
        saveAuthentication(updated, httpRequest, httpResponse)
        return toMeResponse(updated)
    }

    private fun saveAuthentication(
        user: AuthUser,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse,
    ) {
        val authentication = UsernamePasswordAuthenticationToken(
            user, null, listOf(SimpleGrantedAuthority("ROLE_USER")),
        )
        val context = SecurityContextHolder.createEmptyContext().apply { this.authentication = authentication }
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, httpRequest, httpResponse)
    }

    private fun toMeResponse(user: AuthUser): MeResponse = MeResponse(
        id = user.id, email = user.email, name = user.name,
        shopId = authService.findOwnedShopId(user.id),
        mustChangePassword = user.mustChangePassword,
        isStreamer = authService.isStreamer(user.id),
    )

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(httpRequest: HttpServletRequest) {
        httpRequest.getSession(false)?.let { session ->
            // 앱 세션(방송 단말)의 자발적 로그아웃도 진행 중 방송의 종료를 선행한다 —
            // 슬롯만 비우면 이미 전달된 Key로 RTMPS 송출이 계속되는 채로 다른 계정이
            // 슬롯을 차지할 수 있다. 종료(Key 폐기→중단) 실패 시 로그아웃도 실패한다(재시도).
            broadcastService.endActiveBroadcastForAppSession(session.id)
            appSessionService.release(session.id)
            session.invalidate()
        }
        SecurityContextHolder.clearContext()
    }

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal user: AuthUser): MeResponse = toMeResponse(user)
}
