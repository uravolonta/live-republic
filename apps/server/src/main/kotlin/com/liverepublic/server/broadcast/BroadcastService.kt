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
    private val streamSessionRepository: com.liverepublic.server.live.LiveStreamSessionRepository,
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

    /**
     * 방송 화면에서 다루는 Live: 방송 중 → 시작 중 → 예정 순.
     * STARTING은 Shop의 방송 슬롯을 점유하므로 반드시 목록에 보여 재진입(송출 재개)
     * 또는 시작 취소가 가능해야 한다.
     */
    @Transactional(readOnly = true)
    fun listBroadcastableLives(userId: Long): List<Live> {
        val shopId = broadcasterShopId(userId)
        return liveRepository.findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId, LiveStatus.LIVE) +
            liveRepository.findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId, LiveStatus.STARTING) +
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
            // 연결 재시도 — 시작자 본인만 발급된 자격을 다시 사용할 수 있다 (송출 가로채기 방지).
            if (live.startedByUserId != userId) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "다른 단말에서 시작 중인 Live입니다.")
            }
            return live
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
            live.ivsStreamKeyArn = channel.streamKeyArn
            live.ivsPlaybackUrl = channel.playbackUrl
        } else if (live.ivsStreamKey == null) {
            // 이전 종료에서 Key를 폐기했으므로 Channel 재사용 시 새 Key를 발급한다.
            val key = ivsService.createStreamKey(live.ivsChannelArn!!)
            live.ivsStreamKey = key.value
            live.ivsStreamKeyArn = key.arn
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
     * 방송 중 확정: 앱이 SDK 연결(CONNECTED)을 확인할 때마다 호출한다.
     * IVS에서 실제 Stream Session을 조회해 이력(live_stream_session)에 기록한다 —
     * 재연결로 새 Session이 생기면 이전 이력을 닫고 새 행을 추가한다.
     */
    @Transactional
    fun confirm(userId: Long, liveId: Long): Live {
        val shopId = broadcasterShopId(userId)
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        if (live.status != LiveStatus.STARTING && live.status != LiveStatus.LIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "시작 중이거나 방송 중인 Live만 확정할 수 있습니다. (현재: ${live.status})")
        }
        val channelArn = live.ivsChannelArn
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "IVS Channel이 없습니다. 방송을 다시 시작하세요.")
        val streamSessionId = ivsService.currentStreamSessionId(channelArn)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "IVS에서 송출이 아직 감지되지 않았습니다. 잠시 후 다시 시도하세요.")

        val now = OffsetDateTime.now()
        val openSession = streamSessionRepository.findFirstByLiveIdAndEndedAtIsNullOrderByIdDesc(liveId)
        if (openSession?.ivsStreamId != streamSessionId) {
            // 열린 세션 유일 인덱스 위반을 막기 위해, 이전 세션 마감을 새 행 삽입보다 먼저 flush한다.
            openSession?.let {
                it.endedAt = now
                streamSessionRepository.saveAndFlush(it)
            }
            streamSessionRepository.save(
                com.liverepublic.server.live.LiveStreamSession(
                    liveId = liveId, ivsChannelArn = channelArn,
                    ivsStreamId = streamSessionId, startedAt = now,
                ),
            )
        }
        live.ivsStreamSessionId = streamSessionId
        if (live.status == LiveStatus.STARTING) {
            live.status = LiveStatus.LIVE
            live.startedAt = now
        }
        live.updatedAt = now
        return live
    }

    /**
     * 방송 종료: LIVE → ENDED, STARTING → SCHEDULED(시작 취소).
     * 두 경우 모두 서버가 IVS 송출 중단을 시도한다 — STARTING도 SDK가 이미 연결됐을 수 있다.
     * 중단이 2회 모두 실패하면 종료를 확정하지 않고 502로 실패시켜(운영 경보 로그 포함)
     * 실제 송출이 계속되는데 시스템만 종료로 표시되는 상태를 막는다. 사용자는 재시도한다.
     */
    @Transactional
    fun end(userId: Long, liveId: Long): Live {
        val shopId = broadcasterShopId(userId)
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        // 멱등: 이미 종료(ENDED)됐거나 시작 취소로 SCHEDULED로 돌아간 뒤의 재요청은
        // 성공으로 응답한다 — 응답 유실 후 재시도가 오류로 보이지 않게 한다.
        if (live.status == LiveStatus.ENDED || live.status == LiveStatus.SCHEDULED) {
            return live
        }
        if (live.status != LiveStatus.STARTING && live.status != LiveStatus.LIVE) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT, "방송 중이거나 시작 중인 Live만 종료할 수 있습니다. (현재: ${live.status})",
            )
        }
        requireStarterOrOwner(live, userId)
        live.ivsChannelArn?.let { arn -> stopStreamOrThrow(arn, liveId) }
        // 발급된 Stream Key를 폐기해 자동 재연결·재송출을 영구 차단한다. 재시작 시 새 Key를 발급한다.
        live.ivsStreamKeyArn?.let { keyArn -> deleteStreamKeyOrThrow(keyArn, liveId) }
        live.ivsStreamKey = null
        live.ivsStreamKeyArn = null

        val now = OffsetDateTime.now()
        streamSessionRepository.findFirstByLiveIdAndEndedAtIsNullOrderByIdDesc(liveId)?.let { it.endedAt = now }
        if (live.status == LiveStatus.STARTING) {
            // 연결 실패 등으로 시작을 취소한다. Channel은 재사용을 위해 유지한다.
            live.status = LiveStatus.SCHEDULED
            live.startedByUserId = null
            live.currentLiveProductId = null
        } else {
            live.status = LiveStatus.ENDED
            live.endedAt = now
        }
        live.updatedAt = now
        return live
    }

    /** 방송 중 조작(전환·종료)은 시작자 본인 또는 Shop Owner만 할 수 있다. */
    private fun requireStarterOrOwner(live: Live, userId: Long) {
        if (live.startedByUserId == userId) return
        val ownerMembership = membershipRepository.findByUserIdAndRole(userId, MembershipRole.OWNER)
        val shop = ownerMembership?.let { shopRepository.findByTenantId(it.tenantId) }
        if (shop?.id != live.shopId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "방송을 시작한 계정 또는 Owner만 조작할 수 있습니다.")
        }
    }

    private fun deleteStreamKeyOrThrow(streamKeyArn: String, liveId: Long) {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                ivsService.deleteStreamKey(streamKeyArn)
                return
            } catch (e: Exception) {
                lastError = e
                log.error("IVS Stream Key 폐기 실패 (live={}, 시도 {}/2): {}", liveId, attempt + 1, e.message)
            }
        }
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "송출 자격 폐기에 실패했습니다. 잠시 후 종료를 다시 시도하세요.",
            lastError,
        )
    }

    private fun stopStreamOrThrow(channelArn: String, liveId: Long) {
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                ivsService.stopStream(channelArn)
                return
            } catch (e: Exception) {
                lastError = e
                log.error("IVS 송출 중단 실패 (live={}, channel={}, 시도 {}/2): {}", liveId, channelArn, attempt + 1, e.message)
            }
        }
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY,
            "IVS 송출 중단에 실패했습니다. 잠시 후 종료를 다시 시도하세요.",
            lastError,
        )
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
        requireStarterOrOwner(live, userId)
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
