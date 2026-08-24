package com.liverepublic.server.broadcast

import com.liverepublic.server.live.Live
import com.liverepublic.server.live.LiveProductRepository
import com.liverepublic.server.live.LiveRepository
import com.liverepublic.server.live.LiveStatus
import com.liverepublic.server.product.ProductRepository
import com.liverepublic.server.shop.ShopRepository
import com.liverepublic.server.tenant.MembershipRepository
import com.liverepublic.server.tenant.MembershipRole
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

/**
 * 방송 시작·종료와 현재 판매 상품 전환 (Issue #5).
 * 접근 주체는 같은 Shop의 OWNER 또는 STREAMER Membership이다 — Owner 전용인
 * LiveService와 달리 Streamer 서브계정도 사용한다.
 */
@Service
class BroadcastService(
    private val membershipRepository: MembershipRepository,
    private val shopRepository: ShopRepository,
    private val liveRepository: LiveRepository,
    private val liveProductRepository: LiveProductRepository,
    private val productRepository: ProductRepository,
    private val ivsService: IvsService,
) {

    /** 요청자의 방송 가능한 Shop (OWNER 또는 STREAMER Membership). */
    fun broadcasterShopId(userId: Long): Long {
        val membership = membershipRepository.findByUserIdAndRole(userId, MembershipRole.OWNER)
            ?: membershipRepository.findByUserIdAndRole(userId, MembershipRole.STREAMER)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 Shop이 없습니다.")
        return shopRepository.findByTenantId(membership.tenantId)?.id
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 Shop이 없습니다.")
    }

    /** 방송 화면에서 다루는 Live: 방송 중인 것 먼저, 그다음 예정. */
    @Transactional(readOnly = true)
    fun listBroadcastableLives(userId: Long): List<Live> {
        val shopId = broadcasterShopId(userId)
        return liveRepository.findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId, LiveStatus.LIVE) +
            liveRepository.findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId, LiveStatus.SCHEDULED)
    }

    @Transactional(readOnly = true)
    fun getLive(userId: Long, liveId: Long): Live =
        liveRepository.findByIdAndShopId(liveId, broadcasterShopId(userId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")

    /** 게릴라 Live: 사전 예고 없이 즉시 만들어 시작할 수 있는 예정 Live를 생성한다. */
    @Transactional
    fun createGuerrillaLive(userId: Long, title: String, productIds: List<Long>): Live {
        val shopId = broadcasterShopId(userId)
        if (productIds.toSet().size != productIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 상품을 중복 연결할 수 없습니다.")
        }
        val owned = productRepository.findAllById(productIds).filter { it.shopId == shopId && it.deletedAt == null }
        if (owned.size != productIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 Shop의 판매 중인 상품만 연결할 수 있습니다.")
        }
        val live = liveRepository.save(
            Live(shopId = shopId, title = title, scheduledStartAt = OffsetDateTime.now()),
        )
        liveProductRepository.saveAll(
            productIds.mapIndexed { index, productId ->
                com.liverepublic.server.live.LiveProduct(liveId = live.id!!, productId = productId, position = index)
            },
        )
        return live
    }

    /**
     * 방송 시작 요청: SCHEDULED + 판매 상품 1개 이상일 때만 STARTING으로 전이하고
     * 송출 자격을 발급한다. 실제 방송 중(LIVE) 확정은 SDK 연결 확인 후 confirm()이 한다.
     * Shop 행 잠금으로 시작 슬롯을 선점해 동시 시작이 AWS Channel을 중복 생성하지
     * 못하게 하고, 부분 유일 Index(STARTING·LIVE)가 최종 방어선이다.
     * STARTING 상태에서 다시 호출하면 재시도로 보고 같은 자격을 반환한다.
     */
    @Transactional
    fun start(userId: Long, liveId: Long): Live {
        val shopId = broadcasterShopId(userId)
        // Shop 단위 직렬화: AWS Channel 생성 전에 시작 슬롯을 확정한다.
        shopRepository.findByIdForUpdate(shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 Shop이 없습니다.")
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        if (live.status == LiveStatus.STARTING) {
            return live // 연결 재시도 — 발급된 자격을 그대로 다시 사용한다.
        }
        if (live.status != LiveStatus.SCHEDULED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "예정 상태의 Live만 시작할 수 있습니다. (현재: ${live.status})")
        }
        val products = activeLiveProducts(liveId)
        if (products.isEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "판매 상품이 연결되지 않은 Live는 시작할 수 없습니다.")
        }
        if (liveRepository.existsByShopIdAndStatus(shopId, LiveStatus.STARTING) ||
            liveRepository.existsByShopIdAndStatus(shopId, LiveStatus.LIVE)
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 방송 중이거나 시작 중인 Live가 있습니다.")
        }

        if (live.ivsChannelArn == null) {
            val channel = ivsService.createChannel("live-republic-$liveId")
            live.ivsChannelArn = channel.channelArn
            live.ivsIngestEndpoint = channel.ingestEndpoint
            live.ivsStreamKey = channel.streamKey
            live.ivsPlaybackUrl = channel.playbackUrl
        }
        live.status = LiveStatus.STARTING
        live.startedByUserId = userId
        live.currentLiveProductId = products.first().id
        live.updatedAt = OffsetDateTime.now()
        return try {
            liveRepository.saveAndFlush(live)
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 방송 중이거나 시작 중인 Live가 있습니다.")
        }
    }

    /**
     * 방송 중 확정: 앱이 SDK 연결(CONNECTED)을 확인한 뒤 호출한다.
     * IVS에서 실제 Stream Session을 조회해 식별자와 실제 시작 시각을 기록한다.
     */
    @Transactional
    fun confirm(userId: Long, liveId: Long): Live {
        val shopId = broadcasterShopId(userId)
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        if (live.status == LiveStatus.LIVE) return live // 중복 확인 허용
        if (live.status != LiveStatus.STARTING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "시작 중인 Live만 방송 중으로 확정할 수 있습니다. (현재: ${live.status})")
        }
        val streamSessionId = live.ivsChannelArn?.let { ivsService.currentStreamSessionId(it) }
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "IVS에서 송출이 아직 감지되지 않았습니다. 잠시 후 다시 시도하세요.")
        live.status = LiveStatus.LIVE
        live.startedAt = OffsetDateTime.now()
        live.ivsStreamSessionId = streamSessionId
        live.updatedAt = OffsetDateTime.now()
        return live
    }

    /**
     * 방송 종료: LIVE → ENDED. STARTING이면 시작 취소로 보고 SCHEDULED로 되돌린다.
     * IVS 송출 중단 실패는 기록하고 1회 재시도한다 — 실패해도 DB 종료는 확정한다
     * (송출 자체는 앱의 session.stop()이 1차로 중단하며, Stream Key는 더 이상 노출되지 않는다).
     */
    @Transactional
    fun end(userId: Long, liveId: Long): Live {
        val shopId = broadcasterShopId(userId)
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        when (live.status) {
            LiveStatus.STARTING -> {
                // 연결 실패 등으로 시작을 취소한다. Channel은 재사용을 위해 유지한다.
                live.status = LiveStatus.SCHEDULED
                live.startedByUserId = null
                live.currentLiveProductId = null
                live.updatedAt = OffsetDateTime.now()
                return live
            }
            LiveStatus.LIVE -> Unit
            else -> throw ResponseStatusException(
                HttpStatus.CONFLICT, "방송 중이거나 시작 중인 Live만 종료할 수 있습니다. (현재: ${live.status})",
            )
        }
        live.status = LiveStatus.ENDED
        live.endedAt = OffsetDateTime.now()
        live.updatedAt = OffsetDateTime.now()
        live.ivsChannelArn?.let { arn -> stopStreamWithRetry(arn, liveId) }
        return live
    }

    private fun stopStreamWithRetry(channelArn: String, liveId: Long) {
        repeat(2) { attempt ->
            try {
                ivsService.stopStream(channelArn)
                return
            } catch (e: Exception) {
                log.warn("IVS 송출 중단 실패 (live={}, channel={}, 시도 {}/2): {}", liveId, channelArn, attempt + 1, e.message)
            }
        }
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(BroadcastService::class.java)
    }

    /** 방송 중 현재 판매 상품 전환. */
    @Transactional
    fun switchCurrentProduct(userId: Long, liveId: Long, liveProductId: Long): Live {
        val shopId = broadcasterShopId(userId)
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        if (live.status != LiveStatus.LIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "방송 중에만 현재 상품을 전환할 수 있습니다.")
        }
        val liveProduct = activeLiveProducts(liveId).firstOrNull { it.id == liveProductId }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "이 Live에 연결된 상품이 아닙니다.")
        live.currentLiveProductId = liveProduct.id
        live.updatedAt = OffsetDateTime.now()
        return live
    }

    /** 삭제되지 않은 상품의 Live 연결 목록 (표시 순서). */
    @Transactional(readOnly = true)
    fun activeLiveProducts(liveId: Long): List<com.liverepublic.server.live.LiveProduct> {
        val liveProducts = liveProductRepository.findAllByLiveIdOrderByPosition(liveId)
        val activeIds = productRepository.findAllById(liveProducts.map { it.productId })
            .filter { it.deletedAt == null }
            .mapNotNull { it.id }
            .toSet()
        return liveProducts.filter { it.productId in activeIds }
    }

}
