package com.liverepublic.server.auth

import com.liverepublic.server.shop.ShopRepository
import com.liverepublic.server.tenant.MembershipRepository
import com.liverepublic.server.tenant.MembershipRole
import com.liverepublic.server.user.UserAccount
import com.liverepublic.server.user.UserAccountRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

/** 인증된 사용자를 나타내는 Session Principal. */
data class AuthUser(
    val id: Long,
    val email: String,
    val name: String,
    val mustChangePassword: Boolean = false,
) : java.io.Serializable {
    companion object {
        // 필드가 추가되어도 기존 세션을 읽을 수 있도록 고정한다.
        private const val serialVersionUID = 1L
    }
}

private const val BCRYPT_MAX_PASSWORD_BYTES = 72

@Service
class AuthService(
    private val userAccountRepository: UserAccountRepository,
    private val membershipRepository: MembershipRepository,
    private val shopRepository: ShopRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    @Transactional
    fun signup(email: String, password: String, name: String): AuthUser {
        // 비밀번호 charset·길이는 SignupRequest의 @Pattern(인쇄 가능한 ASCII)과 @Size가 검증한다.
        // ASCII 72자 이하 = 72바이트 이하이므로 BCrypt 한계를 넘지 않는다.
        if (userAccountRepository.existsByEmail(email)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.")
        }
        val user = try {
            userAccountRepository.save(
                UserAccount(email = email, passwordHash = requireNotNull(passwordEncoder.encode(password)), name = name),
            )
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 가입된 이메일입니다.")
        }
        return AuthUser(id = user.id!!, email = user.email, name = user.name)
    }

    @Transactional(readOnly = true)
    fun authenticate(email: String, password: String): AuthUser {
        // 로그인 입력은 charset 검증이 없으므로, BCrypt 한계(72바이트)를 넘는 값은
        // 저장됐을 수 없는 비밀번호로 보고 예외 대신 인증 실패로 처리한다.
        if (password.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_PASSWORD_BYTES) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        val user = userAccountRepository.findByEmail(email)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        return AuthUser(
            id = user.id!!, email = user.email, name = user.name,
            mustChangePassword = user.mustChangePassword,
        )
    }

    /** 자신의 비밀번호를 변경한다. 최초 변경 강제 상태를 해제한다. */
    @Transactional
    fun changePassword(userId: Long, currentPassword: String, newPassword: String): AuthUser {
        if (newPassword.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_PASSWORD_BYTES ||
            !newPassword.matches(Regex("^[!-~]{8,72}$"))
        ) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "비밀번호는 8자 이상의 영문 대소문자, 숫자, 특수문자만 사용할 수 있습니다.",
            )
        }
        val user = userAccountRepository.findById(userId)
            .orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "사용자를 찾을 수 없습니다.") }
        if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "현재 비밀번호가 올바르지 않습니다.")
        }
        user.passwordHash = requireNotNull(passwordEncoder.encode(newPassword))
        user.mustChangePassword = false
        return AuthUser(id = user.id!!, email = user.email, name = user.name, mustChangePassword = false)
    }

    /** 사용자가 Owner로 속한 Shop의 id. 없으면 null. */
    @Transactional(readOnly = true)
    fun findOwnedShopId(userId: Long): Long? {
        val membership = membershipRepository.findByUserIdAndRole(userId, MembershipRole.OWNER) ?: return null
        return shopRepository.findByTenantId(membership.tenantId)?.id
    }

    /** STREAMER Membership 보유 여부 (Client가 화면 분기에 사용). */
    @Transactional(readOnly = true)
    fun isStreamer(userId: Long): Boolean =
        membershipRepository.findByUserIdAndRole(userId, MembershipRole.STREAMER) != null
}
