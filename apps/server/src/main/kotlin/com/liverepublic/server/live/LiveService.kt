package com.liverepublic.server.live

import com.liverepublic.server.product.ProductRepository
import com.liverepublic.server.product.ProductService
import com.liverepublic.server.shop.ShopRepository
import com.liverepublic.server.tenant.MembershipRepository
import com.liverepublic.server.tenant.MembershipRole
import com.liverepublic.server.user.UserAccountRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@Service
class LiveService(
    private val productService: ProductService,
    private val productRepository: ProductRepository,
    private val shopRepository: ShopRepository,
    private val membershipRepository: MembershipRepository,
    private val userAccountRepository: UserAccountRepository,
    private val liveRepository: LiveRepository,
    private val liveProductRepository: LiveProductRepository,
) {

    @Transactional
    fun createLive(userId: Long, title: String, scheduledStartAt: OffsetDateTime): Live {
        val shopId = productService.ownerShopId(userId)
        return liveRepository.save(
            Live(shopId = shopId, title = title, scheduledStartAt = scheduledStartAt),
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
    fun updateLive(userId: Long, liveId: Long, title: String, scheduledStartAt: OffsetDateTime): Live {
        val live = getMutableLive(userId, liveId)
        live.title = title
        live.scheduledStartAt = scheduledStartAt
        live.updatedAt = OffsetDateTime.now()
        return live
    }

    /** 담당자 지정: 같은 Shop의 활성 OWNER 또는 STREAMER Membership만 허용. null이면 해제. */
    @Transactional
    fun assignStreamer(userId: Long, liveId: Long, streamerUserId: Long?): Live {
        val live = getMutableLive(userId, liveId)
        if (streamerUserId != null) {
            val tenantId = shopRepository.findById(live.shopId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Shop을 찾을 수 없습니다.") }
                .tenantId
            val membership = membershipRepository.findByUserIdAndTenantId(streamerUserId, tenantId)
            if (membership == null ||
                (membership.role != MembershipRole.OWNER && membership.role != MembershipRole.STREAMER)
            ) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 Shop의 Owner 또는 Streamer만 담당자로 지정할 수 있습니다.")
            }
        }
        live.streamerUserId = streamerUserId
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
        val ownedProducts = productRepository.findAllById(productIds).filter { it.shopId == live.shopId }
        if (ownedProducts.size != productIds.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 Shop의 상품만 연결할 수 있습니다.")
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

    /** 담당자 표시 정보. */
    @Transactional(readOnly = true)
    fun streamerInfo(streamerUserId: Long?): Pair<String, String>? {
        if (streamerUserId == null) return null
        val user = userAccountRepository.findById(streamerUserId).orElse(null) ?: return null
        return user.name to user.email
    }

    /**
     * 방송 준비 미완료 사유 (Issue #4: 표시만, 시작 거절은 Issue #5).
     * 상품은 생성 시 SKU가 반드시 만들어지므로 SKU 유효성은 상품 존재로 판정한다.
     */
    fun notReadyReasons(live: Live, productCount: Int): List<String> {
        val reasons = mutableListOf<String>()
        if (live.streamerUserId == null) reasons += "담당자가 연결되지 않았습니다."
        if (productCount == 0) reasons += "판매 상품이 연결되지 않았습니다."
        return reasons
    }

    private fun getMutableLive(userId: Long, liveId: Long): Live {
        val live = getLive(userId, liveId)
        if (live.status != LiveStatus.SCHEDULED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "취소된 Live는 수정할 수 없습니다.")
        }
        return live
    }
}
