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

enum class LiveStatus { SCHEDULED, LIVE, ENDED, CANCELLED }

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

    /** 썸네일 URL (선택). 이미지 업로드 인프라는 별도 티켓. */
    @Column(name = "thumbnail_url")
    var thumbnailUrl: String? = null,

    /** 실제 방송 시각 — 예정 시각(scheduledStartAt)과 구분된다. */
    @Column(name = "started_at")
    var startedAt: OffsetDateTime? = null,

    @Column(name = "ended_at")
    var endedAt: OffsetDateTime? = null,

    /** 방송을 시작한 사용자 (같은 Shop의 OWNER 또는 STREAMER). */
    @Column(name = "started_by_user_id")
    var startedByUserId: Long? = null,

    @Column(name = "ivs_channel_arn")
    var ivsChannelArn: String? = null,

    @Column(name = "ivs_ingest_endpoint")
    var ivsIngestEndpoint: String? = null,

    @Column(name = "ivs_stream_key")
    var ivsStreamKey: String? = null,

    @Column(name = "ivs_playback_url")
    var ivsPlaybackUrl: String? = null,

    /** 방송 중 현재 판매 상품 (live_product id). */
    @Column(name = "current_live_product_id")
    var currentLiveProductId: Long? = null,

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
    fun findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId: Long, status: LiveStatus): List<Live>
    fun findByIdAndShopId(id: Long, shopId: Long): Live?
    fun existsByShopIdAndStatus(shopId: Long, status: LiveStatus): Boolean

    /** 방송 시작·종료·상품 전환의 동시 실행을 직렬화하기 위한 쓰기 잠금 조회. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
        "select l from Live l where l.id = :id and l.shopId = :shopId",
    )
    fun findByIdAndShopIdForUpdate(id: Long, shopId: Long): Live?
}

interface LiveProductRepository : JpaRepository<LiveProduct, Long> {
    fun findAllByLiveIdOrderByPosition(liveId: Long): List<LiveProduct>
    fun findAllByLiveIdInOrderByPosition(liveIds: List<Long>): List<LiveProduct>

    @Modifying
    @Query("delete from LiveProduct lp where lp.liveId = :liveId")
    fun deleteAllByLiveId(liveId: Long)
}
