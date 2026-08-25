package com.liverepublic.server.broadcast

import com.liverepublic.server.auth.AuthUser
import com.liverepublic.server.live.Live
import com.liverepublic.server.live.LiveStatus
import com.liverepublic.server.product.ProductRepository
import com.liverepublic.server.product.ProductService
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime

data class CreateGuerrillaLiveRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    val productIds: List<Long> = emptyList(),
)

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

data class BroadcastLiveSummary(
    val id: Long,
    val title: String,
    val status: LiveStatus,
    val scheduledStartAt: OffsetDateTime,
    val productCount: Int,
)

data class BroadcastLiveDetail(
    val id: Long,
    val title: String,
    val status: LiveStatus,
    val scheduledStartAt: OffsetDateTime,
    val startedAt: OffsetDateTime?,
    val endedAt: OffsetDateTime?,
    val currentLiveProductId: Long?,
    val products: List<BroadcastProductInfo>,
    /** 임대 토큰을 제시한 방송 단말에만 내려간다 (송출 가로채기 방지). */
    val ingestEndpoint: String?,
    val streamKey: String?,
    val playbackUrl: String?,
    /** start 응답에서만 내려가는 송출 임대 토큰 — 단말이 보관하고 이후 요청 헤더로 제시한다. */
    val broadcastToken: String? = null,
    /** 이 요청 단말·계정이 할 수 있는 것 — 화면은 이 값으로 버튼을 제어한다. */
    val canBroadcast: Boolean = false,
    val canControl: Boolean = false,
    val canForceEnd: Boolean = false,
)

@RestController
@RequestMapping("/api/broadcast")
class BroadcastController(
    private val broadcastService: BroadcastService,
    private val productRepository: ProductRepository,
    private val productService: ProductService,
) {

    /** 게릴라 Live 생성 시 상품 선택용 — Streamer도 접근 가능한 판매 중 상품 목록. */
    @GetMapping("/products")
    fun products(@AuthenticationPrincipal user: AuthUser): List<BroadcastProductInfo> {
        val shopId = broadcastService.broadcasterShopId(user.id)
        val products = productRepository.findAllByShopIdAndDeletedAtIsNullOrderByIdDesc(shopId)
        val skusByProduct = productService.listSkusByProducts(products.mapNotNull { it.id })
        return products.mapIndexed { index, product ->
            BroadcastProductInfo(
                liveProductId = 0,
                productId = product.id!!,
                name = product.name,
                price = product.price,
                position = index,
                skus = skusByProduct[product.id].orEmpty().map {
                    BroadcastSkuInfo(optionLabel = it.optionLabel, available = it.available)
                },
            )
        }
    }

    @GetMapping("/lives")
    fun list(@AuthenticationPrincipal user: AuthUser): List<BroadcastLiveSummary> {
        val lives = broadcastService.listBroadcastableLives(user.id)
        return lives.map { live ->
            BroadcastLiveSummary(
                id = live.id!!,
                title = live.title,
                status = live.status,
                scheduledStartAt = live.scheduledStartAt,
                productCount = broadcastService.activeLiveProducts(live.id!!).size,
            )
        }
    }

    @PostMapping("/lives")
    @ResponseStatus(HttpStatus.CREATED)
    fun createGuerrilla(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: CreateGuerrillaLiveRequest,
    ): BroadcastLiveDetail {
        val live = broadcastService.createGuerrillaLive(user.id, request.title.trim(), request.productIds)
        return toDetail(live, user.id)
    }

    @GetMapping("/lives/{liveId}")
    fun detail(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @RequestHeader(name = BROADCAST_TOKEN_HEADER, required = false) token: String?,
    ): BroadcastLiveDetail = toDetail(broadcastService.getLive(user.id, liveId), user.id, token)

    @PostMapping("/lives/{liveId}/start")
    fun start(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): BroadcastLiveDetail {
        val result = broadcastService.start(user.id, liveId)
        return toDetail(result.live, user.id, result.broadcastToken)
            .copy(broadcastToken = result.broadcastToken)
    }

    /** SDK 연결(CONNECTED) 확인 후 방송 중 확정 — 방송 단말(임대 토큰)만 호출할 수 있다. */
    @PostMapping("/lives/{liveId}/confirm")
    fun confirm(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @RequestHeader(name = BROADCAST_TOKEN_HEADER, required = false) token: String?,
    ): BroadcastLiveDetail = toDetail(broadcastService.confirm(user.id, liveId, token), user.id, token)

    @PostMapping("/lives/{liveId}/end")
    fun end(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @RequestHeader(name = BROADCAST_TOKEN_HEADER, required = false) token: String?,
    ): BroadcastLiveDetail = toDetail(broadcastService.end(user.id, liveId, token), user.id, token)

    @PutMapping("/lives/{liveId}/current-product")
    fun switchProduct(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @RequestHeader(name = BROADCAST_TOKEN_HEADER, required = false) token: String?,
        @Valid @RequestBody request: SwitchProductRequest,
    ): BroadcastLiveDetail =
        toDetail(broadcastService.switchCurrentProduct(user.id, liveId, request.liveProductId, token), user.id, token)

    private fun toDetail(live: Live, requesterId: Long, token: String? = null): BroadcastLiveDetail {
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
        // 송출 자격은 시작 중(STARTING)·방송 중(LIVE)에, 그리고 임대 토큰을 제시한
        // 방송 단말에만 내려간다 — 같은 계정의 다른 단말도 가로챌 수 없다.
        val active = live.status == LiveStatus.STARTING || live.status == LiveStatus.LIVE
        val holdsLease = active && broadcastService.holdsBroadcastLease(live, token)
        val isOwner = broadcastService.isShopOwner(live, requesterId)
        return BroadcastLiveDetail(
            id = live.id!!,
            title = live.title,
            status = live.status,
            scheduledStartAt = live.scheduledStartAt,
            startedAt = live.startedAt,
            endedAt = live.endedAt,
            currentLiveProductId = live.currentLiveProductId,
            products = productInfos,
            ingestEndpoint = if (holdsLease) live.ivsIngestEndpoint else null,
            streamKey = if (holdsLease) live.ivsStreamKey else null,
            playbackUrl = if (holdsLease) live.ivsPlaybackUrl else null,
            canBroadcast = holdsLease ||
                (active && live.startedByUserId == requesterId) || // 시작 계정은 start 재호출로 임대를 갱신할 수 있다
                (live.status == LiveStatus.SCHEDULED),
            canControl = holdsLease,
            canForceEnd = active && isOwner,
        )
    }

    companion object {
        const val BROADCAST_TOKEN_HEADER = "X-Broadcast-Token"
    }
}
