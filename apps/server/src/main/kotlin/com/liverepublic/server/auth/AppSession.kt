package com.liverepublic.server.auth

import com.liverepublic.server.tenant.MembershipRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.LockModeType
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.http.HttpStatus
import org.springframework.session.Session
import org.springframework.session.SessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

/**
 * 방송 앱의 활성 세션 — 테넌트당 1개만 허용한다 (2026-08-28 사람 결정).
 * 이 단일 세션이 곧 "방송 단말"의 증명이며, Owner는 대시보드에서
 * 방송 종료와 함께 이 세션을 강제 로그아웃시킬 수 있다.
 */
@Entity
@Table(name = "app_session")
class AppSession(
    @Id
    @Column(name = "tenant_id")
    val tenantId: Long,

    @Column(name = "session_id", nullable = false)
    var sessionId: String,

    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "created_at", nullable = false)
    var createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface AppSessionRepository : JpaRepository<AppSession, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AppSession s where s.tenantId = :tenantId")
    fun findByTenantIdForUpdate(tenantId: Long): AppSession?

    fun findBySessionId(sessionId: String): AppSession?
}

@Service
class AppSessionService(
    private val appSessionRepository: AppSessionRepository,
    private val membershipRepository: MembershipRepository,
    private val sessionRepository: SessionRepository<out Session>,
) {

    fun tenantIdOf(userId: Long): Long =
        membershipRepository.findAllByUserId(userId).firstOrNull()?.tenantId
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "연결된 Shop이 없습니다. Owner Web에서 먼저 Shop을 만드세요.")

    /**
     * 방송 앱 로그인 시 테넌트의 앱 세션 슬롯을 차지한다.
     * - 같은 계정의 재로그인: 이전 세션을 자동 무효화하고 대체한다 (앱 크래시 복구 경로).
     * - 다른 계정: 이전 세션이 살아 있으면 거절 — Owner가 대시보드에서 로그아웃해야 한다.
     * - 이전 세션이 이미 만료·삭제됐으면 계정과 무관하게 대체한다.
     */
    @Transactional
    fun claim(userId: Long, newSessionId: String) {
        val tenantId = tenantIdOf(userId)
        val existing = appSessionRepository.findByTenantIdForUpdate(tenantId)
        if (existing != null && existing.sessionId != newSessionId) {
            val alive = sessionRepository.findById(existing.sessionId) != null
            if (alive && existing.userId != userId) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "다른 계정이 방송 앱에 로그인되어 있습니다. Owner가 대시보드에서 로그아웃한 뒤 다시 시도하세요.",
                )
            }
            sessionRepository.deleteById(existing.sessionId)
        }
        if (existing != null) {
            existing.sessionId = newSessionId
            existing.userId = userId
            existing.createdAt = OffsetDateTime.now()
        } else {
            appSessionRepository.save(AppSession(tenantId = tenantId, sessionId = newSessionId, userId = userId))
        }
    }

    /** 이 세션이 앱 세션이었다면 슬롯을 비운다 (앱의 자발적 로그아웃). */
    @Transactional
    fun release(sessionId: String) {
        appSessionRepository.findBySessionId(sessionId)?.let { appSessionRepository.delete(it) }
    }

    /** 테넌트의 현재 앱 세션 — 만료된 세션은 없는 것으로 정리해 응답한다. */
    @Transactional
    fun current(tenantId: Long): AppSession? {
        val existing = appSessionRepository.findByTenantIdForUpdate(tenantId) ?: return null
        if (sessionRepository.findById(existing.sessionId) == null) {
            appSessionRepository.delete(existing)
            return null
        }
        return existing
    }

    /** Owner의 강제 로그아웃 — 세션 저장소에서 지워 다음 요청부터 401이 된다. */
    @Transactional
    fun forceLogout(tenantId: Long) {
        appSessionRepository.findByTenantIdForUpdate(tenantId)?.let {
            sessionRepository.deleteById(it.sessionId)
            appSessionRepository.delete(it)
        }
    }
}
