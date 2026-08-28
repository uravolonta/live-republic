package com.liverepublic.server.broadcast

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.io.Serializable

/**
 * 다음 방송의 판매 상품 사전 구성 (Owner Web에서 관리, 2026-08-28 사람 결정).
 * 앱은 시작 시 이 구성을 사용하고, 구성이 없으면 판매 중 상품 전체를 연결한다.
 */
@Entity
@Table(name = "broadcast_product_config")
@IdClass(BroadcastProductConfigId::class)
class BroadcastProductConfig(
    @Id
    @Column(name = "shop_id")
    val shopId: Long,

    @Id
    @Column(name = "product_id")
    val productId: Long,

    @Column(nullable = false)
    var position: Int,
)

data class BroadcastProductConfigId(
    val shopId: Long = 0,
    val productId: Long = 0,
) : Serializable

interface BroadcastProductConfigRepository : JpaRepository<BroadcastProductConfig, BroadcastProductConfigId> {
    fun findAllByShopIdOrderByPosition(shopId: Long): List<BroadcastProductConfig>

    @Modifying
    @Query("delete from BroadcastProductConfig c where c.shopId = :shopId")
    fun deleteAllByShopId(shopId: Long)
}
