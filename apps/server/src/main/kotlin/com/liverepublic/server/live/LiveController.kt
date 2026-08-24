package com.liverepublic.server.live

import com.liverepublic.server.auth.AuthUser
import com.liverepublic.server.product.ProductRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
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

data class SaveLiveRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotNull val scheduledStartAt: OffsetDateTime,
    @field:Size(max = 500)
    @field:Pattern(regexp = "^https?://.*$", message = "썸네일 URL은 http(s)로 시작해야 합니다.")
    val thumbnailUrl: String? = null,
)

data class SetLiveProductsRequest(val productIds: List<Long> = emptyList())

data class LiveProductInfo(
    val productId: Long,
    val name: String,
    val price: Int,
    val position: Int,
)

data class LiveDetailResponse(
    val id: Long,
    val title: String,
    val status: LiveStatus,
    val scheduledStartAt: OffsetDateTime,
    val thumbnailUrl: String?,
    val products: List<LiveProductInfo>,
    val ready: Boolean,
    val notReadyReasons: List<String>,
)

data class LiveSummaryResponse(
    val id: Long,
    val title: String,
    val status: LiveStatus,
    val scheduledStartAt: OffsetDateTime,
    val thumbnailUrl: String?,
    val productCount: Int,
    val ready: Boolean,
)

@RestController
@RequestMapping("/api/lives")
class LiveController(
    private val liveService: LiveService,
    private val productRepository: ProductRepository,
) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: SaveLiveRequest,
    ): LiveDetailResponse {
        val live = liveService.createLive(
            user.id, request.title.trim(), request.scheduledStartAt,
            request.thumbnailUrl?.trim()?.ifEmpty { null },
        )
        return toDetail(live)
    }

    @GetMapping
    fun list(@AuthenticationPrincipal user: AuthUser): List<LiveSummaryResponse> {
        val lives = liveService.listLives(user.id)
        val productsByLive = liveService.listLiveProductsByLives(lives.mapNotNull { it.id })
        // 삭제된 상품은 판매 화면에서 숨기므로 상품 수·준비 상태에서도 제외한다.
        val activeProductIds = productRepository
            .findAllById(productsByLive.values.flatten().map { it.productId }.distinct())
            .filter { it.deletedAt == null }
            .mapNotNull { it.id }
            .toSet()
        return lives.map { live ->
            val productCount = productsByLive[live.id]?.count { it.productId in activeProductIds } ?: 0
            LiveSummaryResponse(
                id = live.id!!,
                title = live.title,
                status = live.status,
                scheduledStartAt = live.scheduledStartAt,
                thumbnailUrl = live.thumbnailUrl,
                productCount = productCount,
                ready = liveService.notReadyReasons(productCount).isEmpty(),
            )
        }
    }

    @GetMapping("/{liveId}")
    fun detail(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): LiveDetailResponse = toDetail(liveService.getLive(user.id, liveId))

    @PutMapping("/{liveId}")
    fun update(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @Valid @RequestBody request: SaveLiveRequest,
    ): LiveDetailResponse = toDetail(
        liveService.updateLive(
            user.id, liveId, request.title.trim(), request.scheduledStartAt,
            request.thumbnailUrl?.trim()?.ifEmpty { null },
        ),
    )

    @PutMapping("/{liveId}/products")
    fun setProducts(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @RequestBody request: SetLiveProductsRequest,
    ): LiveDetailResponse = toDetail(liveService.setProducts(user.id, liveId, request.productIds))

    @PostMapping("/{liveId}/cancel")
    fun cancel(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): LiveDetailResponse = toDetail(liveService.cancel(user.id, liveId))

    private fun toDetail(live: Live): LiveDetailResponse {
        val liveProducts = liveService.listLiveProducts(live.id!!)
        // 삭제된 상품은 라인업 표시·준비 상태에서 제외한다.
        val products = productRepository.findAllById(liveProducts.map { it.productId })
            .filter { it.deletedAt == null }
            .associateBy { it.id }
        val productInfos = liveProducts.mapNotNull { lp ->
            products[lp.productId]?.let { product ->
                LiveProductInfo(
                    productId = product.id!!,
                    name = product.name,
                    price = product.price,
                    position = lp.position,
                )
            }
        }
        val notReadyReasons = liveService.notReadyReasons(productInfos.size)
        return LiveDetailResponse(
            id = live.id!!,
            title = live.title,
            status = live.status,
            scheduledStartAt = live.scheduledStartAt,
            thumbnailUrl = live.thumbnailUrl,
            products = productInfos,
            ready = notReadyReasons.isEmpty(),
            notReadyReasons = notReadyReasons,
        )
    }
}
