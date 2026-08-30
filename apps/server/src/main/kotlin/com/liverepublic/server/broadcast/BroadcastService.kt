package com.liverepublic.server.broadcast

import com.liverepublic.server.auth.AppSessionService
import com.liverepublic.server.live.Live
import com.liverepublic.server.live.LiveProductRepository
import com.liverepublic.server.live.LiveRepository
import com.liverepublic.server.live.LiveStatus
import com.liverepublic.server.product.ProductRepository
import com.liverepublic.server.shop.ShopRepository
import com.liverepublic.server.tenant.MembershipRole
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 방송 시작·종료와 현재 판매 상품 전환 (Issue #5).
 * 정책(2026-08-28 사람 결정): 방송 앱은 테넌트당 1개 세션만 로그인되므로 그 세션이
 * 곧 방송 단말이다. 방송은 예약 선택 없이 즉시 시작하며, 판매 상품은 Owner Web의
 * 사전 구성(없으면 판매 중 전체)을 사용한다. Owner는 대시보드에서 강제 종료할 수 있다.
 */
@Service
class BroadcastService(
    private val membershipResolver: com.liverepublic.server.tenant.MembershipResolver,
    private val shopRepository: ShopRepository,
    private val liveRepository: LiveRepository,
    private val liveProductRepository: LiveProductRepository,
    private val streamSessionRepository: com.liverepublic.server.live.LiveStreamSessionRepository,
    private val productRepository: ProductRepository,
    private val configRepository: BroadcastProductConfigRepository,
    private val appSessionService: AppSessionService,
    private val ivsService: IvsService,
) {

    /**
     * 요청자의 방송 가능한 Shop — 테넌트 결정은 앱 세션과 같은 단일 규칙
     * (MembershipResolver)을 쓴다: 다른 규칙을 쓰면 앱 세션을 점유한 테넌트와
     * 방송 Shop이 어긋날 수 있다.
     */
    fun broadcasterShopId(userId: Long): Long {
        val membership = membershipResolver.primary(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 Shop이 없습니다.")
        return shopRepository.findByTenantId(membership.tenantId)?.id
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 Shop이 없습니다.")
    }

    /** Shop의 진행 중(STARTING·LIVE) 방송. 없으면 null. */
    @Transactional(readOnly = true)
    fun currentBroadcast(userId: Long): Live? {
        val shopId = broadcasterShopId(userId)
        return liveRepository.findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId, LiveStatus.LIVE)
            .firstOrNull()
            ?: liveRepository.findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId, LiveStatus.STARTING)
                .firstOrNull()
    }

    @Transactional(readOnly = true)
    fun getLive(userId: Long, liveId: Long): Live =
        liveRepository.findByIdAndShopId(liveId, broadcasterShopId(userId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")

    /**
     * 방송 시작 또는 재개:
     * - 진행 중 방송이 없으면 즉시 새 Live를 만들어 STARTING으로 전이하고 자격을 응답한다.
     *   판매 상품은 사전 구성(없으면 판매 중 전체) 순서로 연결된다.
     * - 진행 중 방송이 있으면 재개다(앱 크래시·재로그인 복구). 단, IVS에서 실제 송출이
     *   아직 진행 중이면(이전 단말이 살아 있음) 시작을 거절하고 대시보드 종료를 안내한다 —
     *   앱 경로에서는 Key를 회전하지 않는다(회전은 종료 시 한 곳뿐).
     * 실제 방송 중(LIVE) 확정은 SDK 연결 확인 후 confirm()이 한다.
     */
    /**
     * 단말(앱 세션) 검증 — 조작 트랜잭션 안에서 app_session 행 잠금을 잡고 판정한다.
     * Controller에서 검사하면 검사와 실행 사이에 재로그인·강제 로그아웃이 세션을
     * 대체하는 TOCTOU 경쟁이 생긴다 (claim/forceLogout과 같은 행 잠금으로 직렬화).
     */
    private fun requireDeviceSession(userId: Long, sessionId: String?) {
        if (!appSessionService.isAppSessionLocked(userId, sessionId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "방송 앱(단말 세션)에서만 사용할 수 있습니다.")
        }
    }

    @Transactional
    fun start(userId: Long, sessionId: String?): Live {
        requireDeviceSession(userId, sessionId)
        val shopId = broadcasterShopId(userId)
        // Shop 단위 직렬화: AWS Channel 생성 전에 시작 슬롯을 확정한다.
        shopRepository.findByIdForUpdate(shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 Shop이 없습니다.")

        val active = liveRepository.findActiveByShopIdForUpdate(shopId, ACTIVE_STATUSES)
        if (active != null) {
            return resume(active)
        }

        val products = broadcastProducts(shopId)
        if (products.isEmpty()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT, "판매 중인 상품이 없습니다. Owner Web에서 상품을 등록·구성한 뒤 시작하세요.",
            )
        }
        val now = OffsetDateTime.now()
        val live = Live(
            shopId = shopId,
            title = "라이브 방송 ${TITLE_FORMAT.format(now.atZoneSameInstant(SEOUL))}",
            scheduledStartAt = now,
            status = LiveStatus.STARTING,
            startedByUserId = userId,
        )
        val channel = ivsService.createChannel("live-republic-shop-$shopId-${now.toEpochSecond()}")
        live.ivsChannelArn = channel.channelArn
        live.ivsIngestEndpoint = channel.ingestEndpoint
        live.ivsStreamKey = channel.streamKey
        live.ivsStreamKeyArn = channel.streamKeyArn
        live.ivsPlaybackUrl = channel.playbackUrl
        live.updatedAt = now
        val saved = try {
            liveRepository.saveAndFlush(live)
        } catch (e: DataIntegrityViolationException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 방송 중이거나 시작 중인 Live가 있습니다.")
        }
        liveProductRepository.saveAll(
            products.mapIndexed { index, product ->
                com.liverepublic.server.live.LiveProduct(liveId = saved.id!!, productId = product.id!!, position = index)
            },
        )
        saved.currentLiveProductId = liveProductRepository.findAllByLiveIdOrderByPosition(saved.id!!).first().id
        return saved
    }

    /** 진행 중 방송의 재개 — 이전 단말이 실제로 송출 중이면 거절한다. */
    private fun resume(live: Live): Live {
        val channelArn = live.ivsChannelArn
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "IVS Channel이 없습니다. 대시보드에서 방송을 종료한 뒤 다시 시작하세요.")
        if (ivsService.currentStreamSessionId(channelArn) != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "이전 단말이 아직 송출 중입니다. 잠시 후 다시 시도하거나, 대시보드에서 방송을 종료한 뒤 시작하세요.",
            )
        }
        // 이전 종료 시도의 부분 실패 등으로 DB의 Key와 IVS 실제 Key가 어긋났을 수 있다 —
        // 실제 목록을 기준으로 검증하고, 어긋났으면 정리 후 재발급한다 (Channel당 Key 1개 한도).
        val actualKeys = listStreamKeysOrThrow(channelArn, live.id!!)
        if (live.ivsStreamKey == null || live.ivsStreamKeyArn == null || live.ivsStreamKeyArn !in actualKeys) {
            actualKeys.forEach { deleteStreamKeyOrThrow(it, live.id!!) }
            val key = ivsService.createStreamKey(channelArn)
            live.ivsStreamKey = key.value
            live.ivsStreamKeyArn = key.arn
            live.updatedAt = OffsetDateTime.now()
        }
        return live
    }

    /** 이번 방송의 판매 상품 — 사전 구성 순서, 구성이 없으면 판매 중 전체(최신순). */
    private fun broadcastProducts(shopId: Long): List<com.liverepublic.server.product.Product> {
        val active = productRepository.findAllByShopIdAndDeletedAtIsNullOrderByIdDesc(shopId)
        val configured = configRepository.findAllByShopIdOrderByPosition(shopId)
        if (configured.isEmpty()) return active
        val byId = active.associateBy { it.id }
        val picked = configured.mapNotNull { byId[it.productId] }
        // 구성된 상품이 전부 삭제·판매 중지된 경우에는 전체로 대체한다.
        return picked.ifEmpty { active }
    }

    /**
     * 방송 중 확정: 앱이 SDK 연결(CONNECTED)을 확인할 때마다 호출한다.
     * IVS에서 실제 Stream Session을 조회해 이력(live_stream_session)에 기록한다 —
     * 재연결로 새 Session이 생기면 이전 이력을 닫고 새 행을 추가한다.
     */
    @Transactional
    fun confirm(userId: Long, liveId: Long, sessionId: String?): Live {
        requireDeviceSession(userId, sessionId)
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
     * 방송 종료: STARTING·LIVE → ENDED. 방송 단말(앱 세션) 또는 Shop Owner(대시보드
     * 강제 종료)만 호출할 수 있다 — 같은 Shop의 다른 Streamer Web 세션은 403.
     * 폐기 → 중단 순서: StopStream 직후 자동 재연결이 다시 붙는 경쟁을 막기 위해
     * Channel의 실제 Stream Key를 전부 폐기한 뒤 송출을 중단한다.
     * 중단이 2회 모두 실패하면 종료를 확정하지 않고 502로 실패시켜 재시도를 받는다.
     * 멱등: 이미 ENDED인 방송의 재요청은 성공으로 응답한다.
     */
    @Transactional
    fun end(userId: Long, liveId: Long, sessionId: String?): Live {
        if (!appSessionService.isAppSessionLocked(userId, sessionId) && !isOwner(userId)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "방송 단말 또는 Owner만 종료할 수 있습니다.")
        }
        val shopId = broadcasterShopId(userId)
        val live = liveRepository.findByIdAndShopIdForUpdate(liveId, shopId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")
        if (live.status == LiveStatus.ENDED) {
            return live
        }
        if (live.status != LiveStatus.STARTING && live.status != LiveStatus.LIVE) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT, "방송 중이거나 시작 중인 Live만 종료할 수 있습니다. (현재: ${live.status})",
            )
        }
        live.ivsChannelArn?.let { channelArn ->
            // DB의 ARN이 아니라 IVS 실제 목록 기준으로 전부 폐기한다 — 부분 실패로
            // 남은 고아 Key까지 정리해 "종료 후 유효한 Key 없음"을 보장한다.
            listStreamKeysOrThrow(channelArn, liveId).forEach { deleteStreamKeyOrThrow(it, liveId) }
        }
        live.ivsStreamKey = null
        live.ivsStreamKeyArn = null
        live.ivsChannelArn?.let { arn -> stopStreamOrThrow(arn, liveId) }

        val now = OffsetDateTime.now()
        streamSessionRepository.findFirstByLiveIdAndEndedAtIsNullOrderByIdDesc(liveId)?.let { it.endedAt = now }
        live.status = LiveStatus.ENDED
        live.endedAt = now
        live.updatedAt = now
        return live
    }

    /**
     * Owner의 앱 강제 로그아웃 — 진행 중 방송이 있으면 먼저 종료한다(Key 폐기 → 중단).
     * 로그아웃만 하면 앱 UI는 죽어도 RTMPS 송출은 계속되기 때문이다.
     */
    @Transactional
    fun forceLogoutApp(ownerUserId: Long) {
        val tenantId = ownerMembershipOrThrow(ownerUserId).tenantId
        val shopId = ownerShopIdOrThrow(ownerUserId)
        // 잠금 순서 통일(app_session → Shop/Live): 단말의 start/confirm과 교착하지 않고,
        // 이 잠금이 커밋까지 유지되어 진행 중인 단말 조작과 직렬화된다.
        appSessionService.lockTenant(tenantId)
        liveRepository.findActiveByShopIdForUpdate(shopId, ACTIVE_STATUSES)?.let { active ->
            end(ownerUserId, active.id!!, sessionId = null) // Owner 권한 경로
        }
        appSessionService.forceLogout(tenantId)
    }

    /** 요청자가 자기 테넌트의 Owner인가 (강제 종료 권한 — 대상 Live는 항상 자기 Shop 범위). */
    private fun isOwner(userId: Long): Boolean = membershipResolver.isOwner(userId)

    /**
     * 앱 세션의 자발적 로그아웃 전에 진행 중 방송을 종료한다 (불변식 C: 로그아웃만으로는
     * 이미 전달된 Key로 RTMPS 송출이 계속된다 — Owner 강제 로그아웃과 같은 규칙).
     */
    @Transactional
    fun endActiveBroadcastForAppSession(sessionId: String) {
        val appSession = appSessionService.bySessionId(sessionId) ?: return // 앱 세션이 아니면 무관
        // 잠금 순서 통일: app_session → Shop/Live
        appSessionService.lockTenant(appSession.tenantId)
        val shopId = shopRepository.findByTenantId(appSession.tenantId)?.id ?: return
        liveRepository.findActiveByShopIdForUpdate(shopId, ACTIVE_STATUSES)?.let { active ->
            end(appSession.userId, active.id!!, sessionId)
        }
    }

    /** Owner 대시보드용 — 현재 앱 세션 정보 (없으면 null). */
    @Transactional
    fun currentAppSession(ownerUserId: Long): com.liverepublic.server.auth.AppSession? =
        appSessionService.current(ownerMembershipOrThrow(ownerUserId).tenantId)

    private fun ownerMembershipOrThrow(userId: Long): com.liverepublic.server.tenant.Membership {
        val membership = membershipResolver.primary(userId)
        if (membership?.role != MembershipRole.OWNER) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Owner만 사용할 수 있습니다.")
        }
        return membership
    }

    private fun ownerShopIdOrThrow(userId: Long): Long =
        shopRepository.findByTenantId(ownerMembershipOrThrow(userId).tenantId)?.id
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "연결된 Shop이 없습니다.")

    /** 방송 중 현재 판매 상품 전환 — 방송 단말 전용. */
    @Transactional
    fun switchCurrentProduct(userId: Long, liveId: Long, liveProductId: Long, sessionId: String?): Live {
        requireDeviceSession(userId, sessionId)
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

    /** Owner의 방송 상품 사전 구성 조회. */
    @Transactional(readOnly = true)
    fun productConfig(ownerUserId: Long): List<BroadcastProductConfig> =
        configRepository.findAllByShopIdOrderByPosition(ownerShopIdOrThrow(ownerUserId))

    /** Owner의 방송 상품 사전 구성 저장 (전체 교체). */
    @Transactional
    fun saveProductConfig(ownerUserId: Long, productIds: List<Long>): List<BroadcastProductConfig> {
        val shopId = ownerShopIdOrThrow(ownerUserId)
        // 동시 전체 교체가 섞이거나 PK가 충돌하지 않도록 Shop 행 잠금으로 직렬화한다.
        shopRepository.findByIdForUpdate(shopId)
        if (productIds.toSet().size != productIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 상품을 중복 구성할 수 없습니다.")
        }
        val owned = productRepository.findAllById(productIds).filter { it.shopId == shopId && it.deletedAt == null }
        if (owned.size != productIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 Shop의 판매 중인 상품만 구성할 수 있습니다.")
        }
        configRepository.deleteAllByShopId(shopId)
        configRepository.flush()
        return configRepository.saveAll(
            productIds.mapIndexed { index, productId ->
                BroadcastProductConfig(shopId = shopId, productId = productId, position = index)
            },
        )
    }

    private fun listStreamKeysOrThrow(channelArn: String, liveId: Long): List<String> = try {
        ivsService.listStreamKeyArns(channelArn)
    } catch (e: Exception) {
        log.error("IVS Stream Key 목록 조회 실패 (live={}, channel={}): {}", liveId, channelArn, e.message)
        throw ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "송출 자격 조회에 실패했습니다. 잠시 후 다시 시도하세요.", e,
        )
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
        private val ACTIVE_STATUSES = listOf(LiveStatus.STARTING, LiveStatus.LIVE)
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val TITLE_FORMAT = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    }
}
