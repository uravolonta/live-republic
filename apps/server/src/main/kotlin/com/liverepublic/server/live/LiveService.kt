package com.liverepublic.server.live

import com.liverepublic.server.product.ProductRepository
import com.liverepublic.server.product.ProductService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@Service
class LiveService(
    private val productService: ProductService,
    private val productRepository: ProductRepository,
    private val liveRepository: LiveRepository,
    private val liveProductRepository: LiveProductRepository,
) {

    @Transactional
    fun createLive(userId: Long, title: String, scheduledStartAt: OffsetDateTime, thumbnailUrl: String?): Live {
        val shopId = productService.ownerShopId(userId)
        return liveRepository.save(
            Live(shopId = shopId, title = title, scheduledStartAt = scheduledStartAt, thumbnailUrl = thumbnailUrl),
        )
    }

    @Transactional(readOnly = true)
    fun listLives(userId: Long): List<Live> =
        liveRepository.findAllByShopIdOrderByScheduledStartAtDesc(productService.ownerShopId(userId))

    @Transactional(readOnly = true)
    fun getLive(userId: Long, liveId: Long): Live =
        liveRepository.findByIdAndShopId(liveId, productService.ownerShopId(userId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Live를 찾을 수 없습니다.")

    @Transactional
    fun updateLive(
        userId: Long,
        liveId: Long,
        title: String,
        scheduledStartAt: OffsetDateTime,
        thumbnailUrl: String?,
    ): Live {
        val live = getMutableLive(userId, liveId)
        live.title = title
        live.scheduledStartAt = scheduledStartAt
        live.thumbnailUrl = thumbnailUrl
        live.updatedAt = OffsetDateTime.now()
        return live
    }

    /** 판매 상품 목록을 통째로 교체한다 — 추가·제거·순서 변경을 하나의 동작으로 처리한다. */
    @Transactional
    fun setProducts(userId: Long, liveId: Long, productIds: List<Long>): Live {
        val live = getMutableLive(userId, liveId)
        if (productIds.toSet().size != productIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 상품을 중복 연결할 수 없습니다.")
        }
        // 삭제(soft delete)된 상품은 새로 연결할 수 없다.
        val ownedProducts = productRepository.findAllById(productIds)
            .filter { it.shopId == live.shopId && it.deletedAt == null }
        if (ownedProducts.size != productIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 Shop의 판매 중인 상품만 연결할 수 있습니다.")
        }
        liveProductRepository.deleteAllByLiveId(liveId)
        liveProductRepository.flush()
        liveProductRepository.saveAll(
            productIds.mapIndexed { index, productId ->
                LiveProduct(liveId = liveId, productId = productId, position = index)
            },
        )
        live.updatedAt = OffsetDateTime.now()
        return live
    }

    @Transactional
    fun cancel(userId: Long, liveId: Long): Live {
        val live = getMutableLive(userId, liveId)
        live.status = LiveStatus.CANCELLED
        live.updatedAt = OffsetDateTime.now()
        return live
    }

    @Transactional(readOnly = true)
    fun listLiveProducts(liveId: Long): List<LiveProduct> =
        liveProductRepository.findAllByLiveIdOrderByPosition(liveId)

    @Transactional(readOnly = true)
    fun listLiveProductsByLives(liveIds: List<Long>): Map<Long, List<LiveProduct>> =
        if (liveIds.isEmpty()) emptyMap()
        else liveProductRepository.findAllByLiveIdInOrderByPosition(liveIds).groupBy { it.liveId }

    /**
     * 방송 준비 미완료 사유 (Issue #4: 표시만, 시작 거절은 Issue #5).
     * 실제 진행자는 방송 시작 시점(Issue #5)에 기록하므로 담당자는 준비 조건이 아니다.
     * 상품은 생성 시 SKU가 반드시 만들어지므로 SKU 유효성은 상품 존재로 판정한다.
     */
    fun notReadyReasons(productCount: Int): List<String> {
        val reasons = mutableListOf<String>()
        if (productCount == 0) reasons += "판매 상품이 연결되지 않았습니다."
        return reasons
    }

    private fun getMutableLive(userId: Long, liveId: Long): Live {
        val live = getLive(userId, liveId)
        if (live.status != LiveStatus.SCHEDULED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "예정 상태의 Live만 수정할 수 있습니다. (현재: ${live.status})")
        }
        return live
    }
}
