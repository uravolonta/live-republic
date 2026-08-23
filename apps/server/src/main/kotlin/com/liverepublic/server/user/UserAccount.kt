package com.liverepublic.server.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

@Entity
@Table(name = "app_user")
class UserAccount(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

    @Column(nullable = false)
    val name: String,

    /** Streamer 서브계정은 임시 비밀번호로 생성되며 최초 로그인 후 변경 전까지 보호 기능이 제한된다. */
    @Column(name = "must_change_password", nullable = false)
    var mustChangePassword: Boolean = false,

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null,
)

interface UserAccountRepository : JpaRepository<UserAccount, Long> {
    fun findByEmail(email: String): UserAccount?
    fun existsByEmail(email: String): Boolean
}
