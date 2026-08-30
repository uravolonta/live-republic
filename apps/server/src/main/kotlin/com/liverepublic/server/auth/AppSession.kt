package com.liverepublic.server.auth

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
    private val membershipResolver: com.liverepublic.server.tenant.MembershipResolver,
    private val sessionRepository: SessionRepository<out Session>,
    transactionManager: org.springframework.transaction.PlatformTransactionManager,
) {

    private val transactionTemplate =
        org.springframework.transaction.support.TransactionTemplate(transactionManager)

    fun tenantIdOf(userId: Long): Long =
        membershipResolver.primary(userId)?.tenantId
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "연결된 Shop이 없습니다. Owner Web에서 먼저 Shop을 만드세요.")

    /**
     * 방송 앱 로그인 시 테넌트의 앱 세션 슬롯을 차지한다.
     * - 같은 계정의 재로그인: 이전 세션을 자동 무효화하고 대체한다 (앱 크래시 복구 경로).
     * - 다른 계정: 이전 세션이 살아 있으면 거절 — Owner가 대시보드에서 로그아웃해야 한다.
     * - 이전 세션이 이미 만료·삭제됐으면 계정과 무관하게 대체한다.
     */
    fun claim(userId: Long, newSessionId: String) {
        try {
            transactionTemplate.execute { doClaim(userId, newSessionId) }
        } catch (e: org.springframework.dao.DataIntegrityViolationException) {
            // 행이 없을 때는 잠금 대상이 없어 최초 로그인 2건이 동시에 INSERT를 경쟁할
            // 수 있다 — 진 쪽은 새 트랜잭션에서, 이제 존재하는 행 기준으로 다시 판정한다.
            transactionTemplate.execute { doClaim(userId, newSessionId) }
        }
    }

    private fun doClaim(userId: Long, newSessionId: String) {
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
            appSessionRepository.saveAndFlush(
                AppSession(tenantId = tenantId, sessionId = newSessionId, userId = userId),
            )
        }
    }

    /**
     * 잠금을 잡은 채로 "이 세션이 방송 단말인가"를 판정한다 — 호출자의 트랜잭션에
     * 참여해 커밋까지 잠금이 유지되므로, 검사 통과 후 재로그인·강제 로그아웃이
     * 세션을 대체하는 검사-사용(TOCTOU) 경쟁을 claim()/forceLogout()과 직렬화로 막는다.
     */
    @Transactional
    fun isAppSessionLocked(userId: Long, sessionId: String?): Boolean {
        if (sessionId == null) return false
        val membership = membershipResolver.primary(userId) ?: return false
        return appSessionRepository.findByTenantIdForUpdate(membership.tenantId)?.sessionId == sessionId
    }

    /** 세션 ID로 앱 세션 행 조회 — 자발적 로그아웃의 방송 종료 선행 판단에 쓴다. */
    @Transactional(readOnly = true)
    fun bySessionId(sessionId: String): AppSession? = appSessionRepository.findBySessionId(sessionId)

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

    /**
     * 이 요청 세션이 테넌트의 방송 앱 세션(=방송 단말)인가.
     * 같은 계정의 Web 세션은 방송 조작·자격 수신 대상이 아니다 — 세션이 단말의
     * 증명이라는 정책의 완결을 위해 자격·확정은 이 세션에만 허용한다.
     */
    @Transactional(readOnly = true)
    fun isAppSession(userId: Long, sessionId: String?): Boolean {
        if (sessionId == null) return false
        val membership = membershipResolver.primary(userId) ?: return false
        return appSessionRepository.findById(membership.tenantId).orElse(null)?.sessionId == sessionId
    }

    /**
     * 테넌트의 앱 세션 행 잠금만 선점한다 (호출자 트랜잭션에 참여).
     * 모든 방송 조작이 app_session → Shop/Live 순서로 잠그므로, 강제 로그아웃도
     * 같은 순서를 지켜 교착을 피한다.
     */
    @Transactional
    fun lockTenant(tenantId: Long) {
        appSessionRepository.findByTenantIdForUpdate(tenantId)
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
