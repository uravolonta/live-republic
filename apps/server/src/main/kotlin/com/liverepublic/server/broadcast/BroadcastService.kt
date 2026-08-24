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
     * 방송 시작: SCHEDULED + 판매 상품 1개 이상일 때만.
     * IVS Channel을 (없으면) 생성하고 LIVE로 전환한다. 방송 중 Live는 Shop당 최대 1개 —
     * 잠금과 부분 유일 Index(uq_live_one_active_per_shop)가 보장한다.
     */
    @Transactional
    fun start(userId: Long, liveId: Long): Live {
        val shopId = broadcasterShopId(userId)
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        if (live.status != LiveStatus.SCHEDULED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "예정 상태의 Live만 시작할 수 있습니다. (현재: ${live.status})")
        }
        val products = activeLiveProducts(liveId)
        if (products.isEmpty()) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "판매 상품이 연결되지 않은 Live는 시작할 수 없습니다.")
        }
        if (liveRepository.existsByShopIdAndStatus(shopId, LiveStatus.LIVE)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 방송 중인 Live가 있습니다. 종료 후 시작하세요.")
        }

        if (live.ivsChannelArn == null) {
            val channel = ivsService.createChannel("live-republic-$liveId")
            live.ivsChannelArn = channel.channelArn
            live.ivsIngestEndpoint = channel.ingestEndpoint
            live.ivsStreamKey = channel.streamKey
            live.ivsPlaybackUrl = channel.playbackUrl
        }
        live.status = LiveStatus.LIVE
        live.startedAt = OffsetDateTime.now()
        live.startedByUserId = userId
        live.currentLiveProductId = products.first().id
        live.updatedAt = OffsetDateTime.now()
        return try {
            liveRepository.saveAndFlush(live)
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 방송 중인 Live가 있습니다. 종료 후 시작하세요.")
        }
    }

    /** 방송 종료: LIVE → ENDED. IVS 송출도 중단시킨다. */
    @Transactional
    fun end(userId: Long, liveId: Long): Live {
        val shopId = broadcasterShopId(userId)
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        if (live.status != LiveStatus.LIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "방송 중인 Live만 종료할 수 있습니다. (현재: ${live.status})")
        }
        live.status = LiveStatus.ENDED
        live.endedAt = OffsetDateTime.now()
        live.updatedAt = OffsetDateTime.now()
        live.ivsChannelArn?.let { arn ->
            try {
                ivsService.stopStream(arn)
            } catch (e: Exception) {
                // 송출 중단 실패가 종료 처리를 막지 않는다. 다음 시작 시 새 Channel을 쓰지 않고 재사용한다.
            }
        }
        return live
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
