package com.liverepublic.server.product

import com.liverepublic.server.shop.ShopRepository
import com.liverepublic.server.tenant.MembershipRepository
import com.liverepublic.server.tenant.MembershipRole
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

data class NewOptionGroup(val name: String, val options: List<String>)

/** 상품 하나가 가질 수 있는 최대 SKU(Option 조합) 수. */
const val MAX_SKUS_PER_PRODUCT = 100L

@Service
class ProductService(
    private val membershipRepository: MembershipRepository,
    private val shopRepository: ShopRepository,
    private val productRepository: ProductRepository,
    private val optionGroupRepository: ProductOptionGroupRepository,
    private val optionRepository: ProductOptionRepository,
    private val skuRepository: SkuRepository,
    private val skuOptionRepository: SkuOptionRepository,
) {

    /** 사용자가 Owner인 Shop. 모든 상품 접근의 격리 경계다 (Membership → Tenant → Shop). */
    fun ownerShopId(userId: Long): Long {
        val membership = membershipRepository.findByUserIdAndRole(userId, MembershipRole.OWNER)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "운영 중인 Shop이 없습니다.")
        return shopRepository.findByTenantId(membership.tenantId)?.id
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "운영 중인 Shop이 없습니다.")
    }

    /**
     * 상품과 Option 구조를 등록하고 Option 조합의 Cartesian Product로 SKU를 생성한다.
     * Option Group이 없으면 "기본" SKU 하나를 만든다.
     */
    @Transactional
    fun createProduct(
        userId: Long,
        name: String,
        price: Int,
        description: String?,
        optionGroups: List<NewOptionGroup>,
    ): Long {
        val shopId = ownerShopId(userId)
        validateOptionGroups(optionGroups)

        val product = productRepository.save(
            Product(shopId = shopId, name = name, price = price, description = description),
        )
        val productId = product.id!!

        // Option Group·Option 저장 (그룹 순서대로, Option은 그룹당 일괄 저장)
        val savedOptionsByGroup: List<List<ProductOption>> = optionGroups.mapIndexed { gi, group ->
            val savedGroup = optionGroupRepository.save(
                ProductOptionGroup(productId = productId, name = group.name, position = gi),
            )
            optionRepository.saveAll(
                group.options.mapIndexed { oi, optionName ->
                    ProductOption(optionGroupId = savedGroup.id!!, name = optionName, position = oi)
                },
            )
        }

        // Option 조합별 SKU 일괄 생성
        val combinations: List<List<ProductOption>> =
            savedOptionsByGroup.fold(listOf(emptyList())) { acc, options ->
                acc.flatMap { combo -> options.map { combo + it } }
            }
        val savedSkus = skuRepository.saveAll(
            combinations.map { combo ->
                val label = if (combo.isEmpty()) "기본" else combo.joinToString(" / ") { it.name }
                Sku(productId = productId, optionLabel = label)
            },
        )
        skuOptionRepository.saveAll(
            savedSkus.zip(combinations).flatMap { (sku, combo) ->
                combo.map { option -> SkuOption(skuId = sku.id!!, optionId = option.id!!) }
            },
        )
        return productId
    }

    @Transactional(readOnly = true)
    fun listProducts(userId: Long): List<Product> =
        productRepository.findAllByShopIdOrderByIdDesc(ownerShopId(userId))

    @Transactional(readOnly = true)
    fun getProduct(userId: Long, productId: Long): Product =
        productRepository.findByIdAndShopId(productId, ownerShopId(userId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.")

    @Transactional(readOnly = true)
    fun listSkus(productId: Long): List<Sku> = skuRepository.findAllByProductIdOrderById(productId)

    @Transactional(readOnly = true)
    fun listSkusByProducts(productIds: List<Long>): Map<Long, List<Sku>> =
        if (productIds.isEmpty()) emptyMap()
        else skuRepository.findAllByProductIdInOrderById(productIds).groupBy { it.productId }

    /** 상품명·가격·설명만 수정한다. Option 구조 변경은 이 Slice 범위 밖이다. */
    @Transactional
    fun updateProduct(userId: Long, productId: Long, name: String, price: Int, description: String?): Product {
        val product = getProduct(userId, productId)
        product.name = name
        product.price = price
        product.description = description
        product.updatedAt = OffsetDateTime.now()
        return product
    }

    /**
     * SKU의 On Hand(실물 보유 선언값)를 설정한다.
     * 이미 고객에게 약속된 수량(확보 Reserved + 판매 확정 Sold) 아래로 내리면
     * Available이 음수(초과판매 상태)가 되므로 거절한다.
     */
    @Transactional
    fun updateOnHand(userId: Long, productId: Long, skuId: Long, onHand: Int): Sku {
        getProduct(userId, productId) // Tenant 소유 검증
        val sku = skuRepository.findByIdAndProductId(skuId, productId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "SKU를 찾을 수 없습니다.")
        if (onHand < sku.reserved + sku.sold) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "입금대기 확보 수량(${sku.reserved})과 판매 확정 수량(${sku.sold})의 합보다 적게 설정할 수 없습니다.",
            )
        }
        sku.onHand = onHand
        sku.updatedAt = OffsetDateTime.now()
        return sku
    }

    private fun validateOptionGroups(optionGroups: List<NewOptionGroup>) {
        if (optionGroups.size > 3) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Option Group은 최대 3개까지 가능합니다.")
        }
        optionGroups.forEach { group ->
            if (group.name.isBlank() || group.name.length > 50) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Option Group 이름은 1자 이상 50자 이하여야 합니다.")
            }
            if (group.options.isEmpty() || group.options.size > 20) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Option은 그룹당 1개 이상 20개 이하여야 합니다.")
            }
            if (group.options.any { it.isBlank() || it.length > 50 }) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Option 이름은 1자 이상 50자 이하여야 합니다.")
            }
            // 조합 표시 이름을 " / "로 만들기 때문에 이름에 구분자가 들어가면
            // 서로 다른 조합이 같은 표시 이름을 가질 수 있다. 쉼표는 Excel/화면 입력 구분자다.
            if (group.options.any { it.contains('/') || it.contains(',') }) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Option 이름에는 '/'와 ','를 사용할 수 없습니다.")
            }
            if (group.options.toSet().size != group.options.size) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 그룹에 중복된 Option이 있습니다.")
            }
        }
        // 조합 폭발 방지: 상품 하나의 SKU 수를 제한한다.
        val combinationCount = optionGroups.fold(1L) { acc, group -> acc * group.options.size }
        if (combinationCount > MAX_SKUS_PER_PRODUCT) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Option 조합(SKU)은 상품당 최대 ${MAX_SKUS_PER_PRODUCT}개까지 가능합니다. (요청: ${combinationCount}개)",
            )
        }
    }
}
