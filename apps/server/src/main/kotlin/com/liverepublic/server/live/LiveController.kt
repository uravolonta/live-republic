package com.liverepublic.server.live

import com.liverepublic.server.auth.AuthUser
import com.liverepublic.server.product.ProductRepository
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

data class SaveLiveRequest(
    @field:NotBlank @field:Size(max = 200) val title: String,
    @field:NotNull val scheduledStartAt: OffsetDateTime,
)

data class AssignStreamerRequest(val streamerUserId: Long?)

data class SetLiveProductsRequest(val productIds: List<Long> = emptyList())

data class LiveStreamerInfo(val userId: Long, val name: String, val loginId: String)

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
    val streamer: LiveStreamerInfo?,
    val products: List<LiveProductInfo>,
    val ready: Boolean,
    val notReadyReasons: List<String>,
)

data class LiveSummaryResponse(
    val id: Long,
    val title: String,
    val status: LiveStatus,
    val scheduledStartAt: OffsetDateTime,
    val streamerName: String?,
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
        val live = liveService.createLive(user.id, request.title.trim(), request.scheduledStartAt)
        return detail(user, live.id!!)
    }

    @GetMapping
    fun list(@AuthenticationPrincipal user: AuthUser): List<LiveSummaryResponse> {
        val lives = liveService.listLives(user.id)
        val productsByLive = liveService.listLiveProductsByLives(lives.mapNotNull { it.id })
        return lives.map { live ->
            val productCount = productsByLive[live.id]?.size ?: 0
            LiveSummaryResponse(
                id = live.id!!,
                title = live.title,
                status = live.status,
                scheduledStartAt = live.scheduledStartAt,
                streamerName = liveService.streamerInfo(live.streamerUserId)?.first,
                productCount = productCount,
                ready = liveService.notReadyReasons(live, productCount).isEmpty(),
            )
        }
    }

    @GetMapping("/{liveId}")
    fun detail(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
    ): LiveDetailResponse {
        val live = liveService.getLive(user.id, liveId)
        return toDetail(live)
    }

    @PutMapping("/{liveId}")
    fun update(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @Valid @RequestBody request: SaveLiveRequest,
    ): LiveDetailResponse =
        toDetail(liveService.updateLive(user.id, liveId, request.title.trim(), request.scheduledStartAt))

    @PutMapping("/{liveId}/streamer")
    fun assignStreamer(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable liveId: Long,
        @RequestBody request: AssignStreamerRequest,
    ): LiveDetailResponse = toDetail(liveService.assignStreamer(user.id, liveId, request.streamerUserId))

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
        val products = productRepository.findAllById(liveProducts.map { it.productId }).associateBy { it.id }
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
        val streamer = liveService.streamerInfo(live.streamerUserId)
        val notReadyReasons = liveService.notReadyReasons(live, productInfos.size)
        return LiveDetailResponse(
            id = live.id!!,
            title = live.title,
            status = live.status,
            scheduledStartAt = live.scheduledStartAt,
            streamer = streamer?.let {
                LiveStreamerInfo(userId = live.streamerUserId!!, name = it.first, loginId = it.second)
            },
            products = productInfos,
            ready = notReadyReasons.isEmpty(),
            notReadyReasons = notReadyReasons,
        )
    }
}
