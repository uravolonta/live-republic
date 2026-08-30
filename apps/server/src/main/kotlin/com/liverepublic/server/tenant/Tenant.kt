package com.liverepublic.server.tenant

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/** 데이터와 설정이 격리되는 Seller 운영 경계. */
@Entity
@Table(name = "tenant")
class Tenant(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null,
)

enum class MembershipRole { OWNER, STREAMER }

/** 누가 어떤 Tenant에 어떤 Role로 접근할 수 있는지. */
@Entity
@Table(name = "membership")
class Membership(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "tenant_id", nullable = false)
    val tenantId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val role: MembershipRole,
)

interface TenantRepository : JpaRepository<Tenant, Long>

/**
 * 사용자 → 테넌트 결정의 단일 규칙. 앱 세션과 방송이 서로 다른 규칙으로 테넌트를
 * 고르면(임의 첫 행 vs OWNER 우선) 다중 Membership 데이터에서 앱 세션을 점유한
 * 테넌트와 실제 방송 Shop이 어긋날 수 있다 — 모든 경로가 이 결정을 공유한다.
 * 규칙: OWNER 우선, 동순위면 가장 오래된 Membership (결정적).
 */
@org.springframework.stereotype.Service
class MembershipResolver(private val membershipRepository: MembershipRepository) {

    fun primary(userId: Long): Membership? =
        membershipRepository.findAllByUserId(userId)
            .sortedWith(compareBy({ it.role != MembershipRole.OWNER }, { it.id }))
            .firstOrNull()

    /** 기준 Membership이 OWNER인가 (자기 테넌트의 Owner 권한 판정). */
    fun isOwner(userId: Long): Boolean = primary(userId)?.role == MembershipRole.OWNER
}

interface MembershipRepository : JpaRepository<Membership, Long> {
    fun findByUserIdAndRole(userId: Long, role: MembershipRole): Membership?
    fun findAllByUserId(userId: Long): List<Membership>
    fun findAllByTenantIdAndRole(tenantId: Long, role: MembershipRole): List<Membership>
    fun findByUserIdAndTenantId(userId: Long, tenantId: Long): Membership?
}
