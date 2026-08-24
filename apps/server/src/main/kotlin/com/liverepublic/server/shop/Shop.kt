package com.liverepublic.server.shop

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/** Customer가 방문하고 Seller가 운영하는 독립 판매 공간. */
@Entity
@Table(name = "shop")
class Shop(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "tenant_id", nullable = false, unique = true)
    val tenantId: Long,

    @Column(nullable = false)
    var name: String,

    @Column(name = "bank_name")
    var bankName: String? = null,

    @Column(name = "bank_account_number")
    var bankAccountNumber: String? = null,

    @Column(name = "bank_account_holder")
    var bankAccountHolder: String? = null,

    @Column(name = "courier_name")
    var courierName: String? = null,

    @Column(name = "base_shipping_fee")
    var baseShippingFee: Int? = null,

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

interface ShopRepository : JpaRepository<Shop, Long> {
    fun findByTenantId(tenantId: Long): Shop?

    /** Shop 단위 직렬화(방송 시작 슬롯 선점 등)를 위한 쓰기 잠금 조회. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from Shop s where s.id = :id")
    fun findByIdForUpdate(id: Long): Shop?
}
