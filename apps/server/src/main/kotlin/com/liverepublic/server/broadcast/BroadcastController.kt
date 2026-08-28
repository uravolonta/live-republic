package com.liverepublic.server.broadcast

import com.liverepublic.server.auth.AuthUser
import com.liverepublic.server.live.Live
import com.liverepublic.server.live.LiveStatus
import com.liverepublic.server.product.ProductRepository
import com.liverepublic.server.product.ProductService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class SwitchProductRequest(
    @field:NotNull val liveProductId: Long,
)

data class BroadcastSkuInfo(val optionLabel: String, val available: Int)

data class BroadcastProductInfo(
    val liveProductId: Long,
    val productId: Long,
    val name: String,
    val price: Int,
    val position: Int,
    val skus: List<BroadcastSkuInfo>,
)

data class BroadcastLiveDetail(
    val id: Long,
    val title: String,
    val status: LiveStatus,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val currentLiveProductId: Long?,
    val products: List<BroadcastProductInfo>,
    /** 진행 중(STARTING·LIVE)일 때만 내려간다 — 앱 세션은 테넌트당 1개이므로 곧 방송 단말이다. */
    val ingestEndpoint: String?,
    val streamKey: String?,
    val playbackUrl: String?,
)

/** 진행 중 방송이 없으면 live = null — 앱은 이 상태에서 '방송 시작'을 노출한다. */
data class CurrentBroadcastResponse(val live: BroadcastLiveDetail?)

data class AppSessionInfo(val accountName: String, val loginAt: OffsetDateTime)

data class AppSessionResponse(val session: AppSessionInfo?)

data class ProductConfigRequest(val productIds: List<Long> = emptyList())

data class ProductConfigEntry(val productId: Long, val name: String, val position: Int)

@RestController
@RequestMapping("/api/broadcast")
class BroadcastController(
    private val broadcastService: BroadcastService,
    private val productRepository: ProductRepository,
    private val productService: ProductService,
    private val userRepository: com.liverepublic.server.user.UserAccountRepository,
) {

    /** 앱 진입 시 현재 상태 — 진행 중 방송이 있으면 그 상세(자격 포함)를 돌려준다. */
    @GetMapping("/current")
    fun current(@AuthenticationPrincipal user: AuthUser): CurrentBroadcastResponse =
        CurrentBroadcastResponse(broadcastService.currentBroadcast(user.id)?.let { toDetail(it) })

    /**
     * 방송 즉시 시작 또는 재개. 예약 선택 없이 사전 구성(없으면 판매 중 전체) 상품으로
     * 새 Live를 만들어 시작한다. 진행 중 방송이 있으면 재개 자격을 돌려준다.
     */
    @PostMapping("/start")
    fun start(@AuthenticationPrincipal user: AuthUser): BroadcastLiveDetail =
        toDetail(broadcastService.start(user.id))

    /** SDK 연결(CONNECTED) 확인 후 방송 중 확정. */
    @PostMapping("/lives/{liveId}/confirm")
    fun confirm(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): BroadcastLiveDetail = toDetail(broadcastService.confirm(user.id, liveId))

    @PostMapping("/lives/{liveId}/end")
    fun end(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): BroadcastLiveDetail = toDetail(broadcastService.end(user.id, liveId))

    @PutMapping("/lives/{liveId}/current-product")
    fun switchProduct(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @Valid @RequestBody request: SwitchProductRequest,
    ): BroadcastLiveDetail =
        toDetail(broadcastService.switchCurrentProduct(user.id, liveId, request.liveProductId))

    // ── Owner 대시보드 (방송 제어) ────────────────────────────────────────────

    /** 현재 앱 세션 (Owner) — 어떤 계정이 방송 앱에 로그인되어 있는지. */
    @GetMapping("/app-session")
    fun appSession(@AuthenticationPrincipal user: AuthUser): AppSessionResponse {
        val session = broadcastService.currentAppSession(user.id) ?: return AppSessionResponse(null)
        val name = userRepository.findById(session.userId).map { it.name }.orElse("(알 수 없음)")
        return AppSessionResponse(AppSessionInfo(accountName = name, loginAt = session.createdAt))
    }

    /** 앱 강제 로그아웃 (Owner) — 진행 중 방송이 있으면 먼저 종료(Key 폐기→중단)한다. */
    @PostMapping("/app-session/logout")
    fun forceLogout(@AuthenticationPrincipal user: AuthUser): AppSessionResponse {
        broadcastService.forceLogoutApp(user.id)
        return AppSessionResponse(null)
    }

    /** 다음 방송의 판매 상품 사전 구성 (Owner). */
    @GetMapping("/config/products")
    fun productConfig(@AuthenticationPrincipal user: AuthUser): List<ProductConfigEntry> =
        toConfigEntries(broadcastService.productConfig(user.id))

    @PutMapping("/config/products")
    fun saveProductConfig(
        @AuthenticationPrincipal user: AuthUser,
        @RequestBody request: ProductConfigRequest,
    ): List<ProductConfigEntry> =
        toConfigEntries(broadcastService.saveProductConfig(user.id, request.productIds))

    private fun toConfigEntries(config: List<BroadcastProductConfig>): List<ProductConfigEntry> {
        val names = productRepository.findAllById(config.map { it.productId }).associate { it.id to it.name }
        return config.map { ProductConfigEntry(productId = it.productId, name = names[it.productId] ?: "", position = it.position) }
    }

    private fun toDetail(live: Live): BroadcastLiveDetail {
        val liveProducts = broadcastService.activeLiveProducts(live.id!!)
        val products = productRepository.findAllById(liveProducts.map { it.productId }).associateBy { it.id }
        val skusByProduct = productService.listSkusByProducts(liveProducts.map { it.productId })
        val productInfos = liveProducts.mapNotNull { lp ->
            products[lp.productId]?.let { product ->
                BroadcastProductInfo(
                    liveProductId = lp.id!!,
                    productId = product.id!!,
                    name = product.name,
                    price = product.price,
                    position = lp.position,
                    skus = skusByProduct[product.id].orEmpty().map {
                        BroadcastSkuInfo(optionLabel = it.optionLabel, available = it.available)
                    },
                )
            }
        }
        val active = live.status == LiveStatus.STARTING || live.status == LiveStatus.LIVE
        return BroadcastLiveDetail(
            id = live.id!!,
            title = live.title,
            status = live.status,
            startedAt = live.startedAt,
            endedAt = live.endedAt,
            currentLiveProductId = live.currentLiveProductId,
            products = productInfos,
            ingestEndpoint = if (active) live.ivsIngestEndpoint else null,
            streamKey = if (active) live.ivsStreamKey else null,
            playbackUrl = if (active) live.ivsPlaybackUrl else null,
        )
    }
}
