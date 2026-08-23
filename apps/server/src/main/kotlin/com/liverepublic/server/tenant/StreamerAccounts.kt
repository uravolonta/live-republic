package com.liverepublic.server.tenant

import com.liverepublic.server.auth.AuthUser
import com.liverepublic.server.shop.ShopRepository
import com.liverepublic.server.user.UserAccount
import com.liverepublic.server.user.UserAccountRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class CreateStreamerRequest(
    // 로그인 ID: 영소문자·숫자·마침표·밑줄·하이픈, 4~50자 (전역 유일)
    @field:Pattern(regexp = "^[a-z0-9._-]{4,50}$", message = "로그인 ID는 영소문자, 숫자, '.', '_', '-'로 4~50자여야 합니다.")
    val loginId: String,
    @field:Size(min = 8, max = 72)
    @field:Pattern(regexp = "^[!-~]+$", message = "임시 비밀번호는 영문 대소문자, 숫자, 특수문자만 사용할 수 있습니다.")
    val temporaryPassword: String,
    @field:NotBlank @field:Size(max = 100) val name: String,
)

data class StreamerResponse(
    val userId: Long,
    val loginId: String,
    val name: String,
    /** 아직 임시 비밀번호 상태인지 — 최초 로그인·변경 완료 여부를 Owner가 확인할 수 있다. */
    val mustChangePassword: Boolean,
)

/** Owner가 자기 Shop의 방송용 Streamer 서브계정을 만들고 조회한다. */
@Service
class StreamerAccountService(
    private val membershipRepository: MembershipRepository,
    private val shopRepository: ShopRepository,
    private val userAccountRepository: UserAccountRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    fun ownerTenantId(userId: Long): Long =
        membershipRepository.findByUserIdAndRole(userId, MembershipRole.OWNER)?.tenantId
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "운영 중인 Shop이 없습니다.")

    @Transactional
    fun createStreamer(ownerUserId: Long, loginId: String, temporaryPassword: String, name: String): StreamerResponse {
        val tenantId = ownerTenantId(ownerUserId)
        if (userAccountRepository.existsByEmail(loginId)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 로그인 ID입니다.")
        }
        val user = try {
            userAccountRepository.save(
                UserAccount(
                    email = loginId,
                    passwordHash = requireNotNull(passwordEncoder.encode(temporaryPassword)),
                    name = name,
                    mustChangePassword = true,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 로그인 ID입니다.")
        }
        membershipRepository.save(
            Membership(userId = user.id!!, tenantId = tenantId, role = MembershipRole.STREAMER),
        )
        return StreamerResponse(
            userId = user.id!!, loginId = user.email, name = user.name,
            mustChangePassword = true,
        )
    }

    @Transactional(readOnly = true)
    fun listStreamers(ownerUserId: Long): List<StreamerResponse> {
        val tenantId = ownerTenantId(ownerUserId)
        val memberships = membershipRepository.findAllByTenantIdAndRole(tenantId, MembershipRole.STREAMER)
        val users = userAccountRepository.findAllById(memberships.map { it.userId }).associateBy { it.id }
        return memberships.mapNotNull { membership ->
            users[membership.userId]?.let { user ->
                StreamerResponse(
                    userId = user.id!!, loginId = user.email, name = user.name,
                    mustChangePassword = user.mustChangePassword,
                )
            }
        }
    }
}

@RestController
@RequestMapping("/api/streamers")
class StreamerController(private val streamerAccountService: StreamerAccountService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: CreateStreamerRequest,
    ): StreamerResponse = streamerAccountService.createStreamer(
        ownerUserId = user.id,
        loginId = request.loginId.trim(),
        temporaryPassword = request.temporaryPassword,
        name = request.name.trim(),
    )

    @GetMapping
    fun list(@AuthenticationPrincipal user: AuthUser): List<StreamerResponse> =
        streamerAccountService.listStreamers(user.id)
}
