package com.liverepublic.server.live

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime

/** LIVE·ENDED는 Issue #5에서 추가한다. */
enum class LiveStatus { SCHEDULED, CANCELLED }

/**
 * 예정 Live — 변경·취소 가능한 사전 예고. 예정 시각이 지나도 자동으로 상태가 바뀌지 않으며,
 * 실제 방송 시작은 Seller의 명시적 동작(Issue #5)으로만 수행한다.
 */
@Entity
@Table(name = "live")
class Live(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "shop_id", nullable = false)
    val shopId: Long,

    @Column(nullable = false)
    var title: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: LiveStatus = LiveStatus.SCHEDULED,

    @Column(name = "scheduled_start_at", nullable = false)
    var scheduledStartAt: OffsetDateTime,

    /** Live 담당자: 같은 Shop의 활성 OWNER 또는 STREAMER. 연결 전에는 null. */
    @Column(name = "streamer_user_id")
    var streamerUserId: Long? = null,

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

/** Live에 연결된 판매 상품과 표시 순서. */
@Entity
@Table(name = "live_product")
class LiveProduct(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "live_id", nullable = false)
    val liveId: Long,

    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(nullable = false)
    val position: Int,
)

interface LiveRepository : JpaRepository<Live, Long> {
    fun findAllByShopIdOrderByScheduledStartAtDesc(shopId: Long): List<Live>
    fun findByIdAndShopId(id: Long, shopId: Long): Live?
}

interface LiveProductRepository : JpaRepository<LiveProduct, Long> {
    fun findAllByLiveIdOrderByPosition(liveId: Long): List<LiveProduct>
    fun findAllByLiveIdInOrderByPosition(liveIds: List<Long>): List<LiveProduct>

    @Modifying
    @Query("delete from LiveProduct lp where lp.liveId = :liveId")
    fun deleteAllByLiveId(liveId: Long)
}
