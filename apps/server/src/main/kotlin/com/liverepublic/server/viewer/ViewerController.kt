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
    /** 방송 중(LIVE)일 때만 내려간다. */
    val playbackUrl: String?,
    val currentProduct: ViewerProduct?,
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
) {

    @GetMapping("/lives/{liveId}")
    fun live(@PathVariable liveId: Long): ResponseEntity<ViewerLiveResponse> {
        val live = liveRepository.findById(liveId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "존재하지 않는 방송입니다.")
        }
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
        val body = ViewerLiveResponse(
            id = live.id!!,
            title = live.title,
            status = live.status,
            playbackUrl = if (live.status == LiveStatus.LIVE) live.ivsPlaybackUrl else null,
            currentProduct = if (live.status == LiveStatus.LIVE) currentProduct else null,
        )
        // 3초 폴링을 CDN·브라우저 캐시가 흡수하도록 짧게 캐시한다.
        return ResponseEntity.ok()
            .header("Cache-Control", "public, max-age=1, s-maxage=2, stale-while-revalidate=5")
            .body(body)
    }
}
