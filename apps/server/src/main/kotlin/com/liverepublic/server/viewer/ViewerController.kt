package com.liverepublic.server.viewer

import com.liverepublic.server.live.LiveProductRepository
import com.liverepublic.server.live.LiveRepository
import com.liverepublic.server.live.LiveStatus
import com.liverepublic.server.product.ProductRepository
import com.liverepublic.server.product.ProductService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * 시청 화면의 Option별 품절 여부 — 정책(2026-08-29): 시청 화면에 재고 수치를
 * 노출하지 않고 품절 여부만 전달한다. 재고 정합의 최종 방어는 주문 시점 검증(#7).
 */
data class ViewerOption(val label: String, val soldOut: Boolean)

data class ViewerProduct(
    val productId: Long,
    val name: String,
    val price: Int,
    /** 모든 Option이 품절이면 상품 품절 — 구매 버튼 비활성화 기준. */
    val soldOut: Boolean,
    val options: List<ViewerOption>,
)

data class ViewerLiveResponse(
    val id: Long,
    val title: String,
    val status: LiveStatus,
    /** SNS 공유 미리보기(Open Graph)용. */
    val thumbnailUrl: String?,
    /** 방송 중(LIVE)일 때만 내려간다. */
    val playbackUrl: String?,
    val currentProduct: ViewerProduct?,
)

/**
 * Shop의 상시 시청 URL 응답 (2026-08-30 사람 결정): 공유 링크는 방송 id가 아니라
 * Shop 단위다 — Shop당 방송이 1개이므로 시청자는 이 URL 하나로 "지금 방송 중인가"만
 * 확인하면 된다. live가 null이면 방송 중이 아니다.
 */
data class ViewerShopResponse(
    val shopId: Long,
    val shopName: String,
    val live: ViewerLiveResponse?,
)

/**
 * Customer 비로그인 시청 API (Issue #6). 시청자는 3초 폴링으로 이 응답을 받아
 * 현재 판매 상품 전환·품절을 반영한다 — 응답은 짧게 캐시되어(CDN 포함) 시청자
 * 수와 무관하게 원 서버 부하가 일정하다 (2026-08-29 Realtime State 결정).
 */
@RestController
@RequestMapping("/api/viewer")
class ViewerController(
    private val liveRepository: LiveRepository,
    private val liveProductRepository: LiveProductRepository,
    private val productRepository: ProductRepository,
    private val productService: ProductService,
    private val shopRepository: com.liverepublic.server.shop.ShopRepository,
) {

    /** Shop 상시 시청 URL — 진행 중(STARTING·LIVE) 방송이 있으면 함께 내려간다. */
    @GetMapping("/shops/{shopId}")
    fun shop(@PathVariable shopId: Long): ResponseEntity<ViewerShopResponse> {
        val shop = shopRepository.findById(shopId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 상점입니다.")
        }
        val active = liveRepository.findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId, LiveStatus.LIVE)
            .firstOrNull()
            ?: liveRepository.findAllByShopIdAndStatusOrderByScheduledStartAtDesc(shopId, LiveStatus.STARTING)
                .firstOrNull()
        return cached(ViewerShopResponse(shopId = shop.id!!, shopName = shop.name, live = active?.let { toViewerLive(it) }))
    }

    @GetMapping("/lives/{liveId}")
    fun live(@PathVariable liveId: Long): ResponseEntity<ViewerLiveResponse> {
        val live = liveRepository.findById(liveId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 방송입니다.")
        }
        return cached(toViewerLive(live))
    }

    private fun toViewerLive(live: com.liverepublic.server.live.Live): ViewerLiveResponse {
        val currentProduct = live.currentLiveProductId
            ?.let { liveProductRepository.findById(it).orElse(null) }
            ?.let { lp ->
                productRepository.findById(lp.productId).orElse(null)
                    ?.takeIf { it.deletedAt == null }
                    ?.let { product ->
                        val options = productService.listSkus(product.id!!).map { sku ->
                            ViewerOption(label = sku.optionLabel, soldOut = sku.available <= 0)
                        }
                        ViewerProduct(
                            productId = product.id!!,
                            name = product.name,
                            price = product.price,
                            soldOut = options.isEmpty() || options.all { it.soldOut },
                            options = options,
                        )
                    }
            }
        return ViewerLiveResponse(
            id = live.id!!,
            title = live.title,
            status = live.status,
            thumbnailUrl = live.thumbnailUrl,
            playbackUrl = if (live.status == LiveStatus.LIVE) live.ivsPlaybackUrl else null,
            currentProduct = if (live.status == LiveStatus.LIVE) currentProduct else null,
        )
    }

    // 3초 폴링을 CDN이 흡수하도록 짧게 캐시한다. stale 허용을 1초로 제한해
    // 상품 전환·종료가 폴링 한 주기(3초) 안에 시청자에게 닿게 한다.
    private fun <T : Any> cached(body: T): ResponseEntity<T> = ResponseEntity.ok()
        .header("Cache-Control", "public, max-age=0, s-maxage=1, stale-while-revalidate=1")
        .body(body)
}
