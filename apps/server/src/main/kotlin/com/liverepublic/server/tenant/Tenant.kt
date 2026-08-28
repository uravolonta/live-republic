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

interface MembershipRepository : JpaRepository<Membership, Long> {
    fun findByUserIdAndRole(userId: Long, role: MembershipRole): Membership?
    fun findAllByUserId(userId: Long): List<Membership>
    fun findAllByTenantIdAndRole(tenantId: Long, role: MembershipRole): List<Membership>
    fun findByUserIdAndTenantId(userId: Long, tenantId: Long): Membership?
}
