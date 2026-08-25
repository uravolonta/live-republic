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
    /** STARTING·LIVE 상태에서 방송을 시작한 계정에만 내려간다 (송출 재개용, 가로채기 방지). */
    val ingestEndpoint: String?,
    val streamKey: String?,
    val playbackUrl: String?,
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
    ): BroadcastLiveDetail = toDetail(broadcastService.getLive(user.id, liveId), user.id)

    @PostMapping("/lives/{liveId}/start")
    fun start(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): BroadcastLiveDetail = toDetail(broadcastService.start(user.id, liveId), user.id)

    /** SDK 연결(CONNECTED) 확인 후 방송 중 확정 — 실제 Stream Session을 기록한다. */
    @PostMapping("/lives/{liveId}/confirm")
    fun confirm(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): BroadcastLiveDetail = toDetail(broadcastService.confirm(user.id, liveId), user.id)

    @PostMapping("/lives/{liveId}/end")
    fun end(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): BroadcastLiveDetail = toDetail(broadcastService.end(user.id, liveId), user.id)

    @PutMapping("/lives/{liveId}/current-product")
    fun switchProduct(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @Valid @RequestBody request: SwitchProductRequest,
    ): BroadcastLiveDetail = toDetail(broadcastService.switchCurrentProduct(user.id, liveId, request.liveProductId), user.id)

    private fun toDetail(live: Live, requesterId: Long): BroadcastLiveDetail {
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
        // 송출 자격은 시작 중(STARTING)·방송 중(LIVE)에, 그리고 방송을 시작한 계정에만
        // 내려간다 — 같은 Shop의 다른 단말이 같은 Key로 송출을 가로채는 것을 막는다.
        val isLive = (live.status == LiveStatus.STARTING || live.status == LiveStatus.LIVE) &&
            live.startedByUserId == requesterId
        return BroadcastLiveDetail(
            id = live.id!!,
            title = live.title,
            status = live.status,
            scheduledStartAt = live.scheduledStartAt,
            startedAt = live.startedAt,
            endedAt = live.endedAt,
            currentLiveProductId = live.currentLiveProductId,
            products = productInfos,
            ingestEndpoint = if (isLive) live.ivsIngestEndpoint else null,
            streamKey = if (isLive) live.ivsStreamKey else null,
            playbackUrl = if (isLive) live.ivsPlaybackUrl else null,
        )
    }
}
