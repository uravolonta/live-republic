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
) : java.io.Serializable

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
        // BCrypt는 UTF-8 기준 72바이트까지만 처리한다. 한글 등 다중 바이트 문자는
        // 문자 수 검증(@Size)을 통과해도 초과할 수 있으므로 바이트 길이를 따로 검증한다.
        if (password.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_PASSWORD_BYTES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "비밀번호가 너무 깁니다. 더 짧은 비밀번호를 사용하세요.")
        }
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
        // 72바이트 초과 비밀번호는 저장될 수 없으므로 BCrypt 예외 대신 인증 실패로 처리한다.
        if (password.toByteArray(Charsets.UTF_8).size > BCRYPT_MAX_PASSWORD_BYTES) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        val user = userAccountRepository.findByEmail(email)
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        if (!passwordEncoder.matches(password, user.passwordHash)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        return AuthUser(id = user.id!!, email = user.email, name = user.name)
    }

    /** 사용자가 Owner로 속한 Shop의 id. 없으면 null. */
    @Transactional(readOnly = true)
    fun findOwnedShopId(userId: Long): Long? {
        val membership = membershipRepository.findByUserIdAndRole(userId, MembershipRole.OWNER) ?: return null
        return shopRepository.findByTenantId(membership.tenantId)?.id
    }
}
