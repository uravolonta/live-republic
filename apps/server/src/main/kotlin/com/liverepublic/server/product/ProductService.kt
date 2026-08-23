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

/** 생성할 SKU 하나 — Option 조합(그룹 순서대로)과 초기 재고. */
data class NewSkuSpec(val optionNames: List<String>, val onHand: Int)

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
     * 상품과 Option 구조를 등록하고 Option 조합의 Cartesian Product로 SKU를 생성한다 (화면 등록).
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
        val combinations: List<List<String>> =
            optionGroups.fold(listOf(emptyList<String>())) { acc, group ->
                acc.flatMap { combo -> group.options.map { combo + it } }
            }
        return createProductWithSkus(
            shopId = ownerShopId(userId),
            name = name,
            price = price,
            description = description,
            optionGroups = optionGroups,
            skus = combinations.map { NewSkuSpec(optionNames = it, onHand = 0) },
        )
    }

    /**
     * 명시된 Option 조합만 SKU로 생성한다 (Excel 등록: 한 행 = 한 SKU).
     * 호출자가 shopId 소유를 이미 검증했어야 한다.
     */
    @Transactional
    fun createProductWithSkus(
        shopId: Long,
        name: String,
        price: Int,
        description: String?,
        optionGroups: List<NewOptionGroup>,
        skus: List<NewSkuSpec>,
    ): Long {
        validateOptionGroups(optionGroups)
        if (skus.isEmpty() || skus.size > MAX_SKUS_PER_PRODUCT) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "SKU는 상품당 1개 이상 ${MAX_SKUS_PER_PRODUCT}개 이하여야 합니다. (요청: ${skus.size}개)",
            )
        }

        val product = productRepository.save(
            Product(shopId = shopId, name = name, price = price, description = description),
        )
        val productId = product.id!!

        // Option Group·Option 저장 (그룹 순서대로, Option은 그룹당 일괄 저장)
        val optionByGroupAndName = mutableMapOf<Pair<Int, String>, ProductOption>()
        optionGroups.forEachIndexed { gi, group ->
            val savedGroup = optionGroupRepository.save(
                ProductOptionGroup(productId = productId, name = group.name, position = gi),
            )
            optionRepository.saveAll(
                group.options.mapIndexed { oi, optionName ->
                    ProductOption(optionGroupId = savedGroup.id!!, name = optionName, position = oi)
                },
            ).forEach { option -> optionByGroupAndName[gi to option.name] = option }
        }

        // 명시된 조합만 SKU로 생성한다. 재고는 생성 시점에 함께 설정한다.
        skus.forEach { spec ->
            if (spec.optionNames.size != optionGroups.size) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "SKU 조합은 모든 Option Group의 값을 가져야 합니다.")
            }
            spec.optionNames.forEachIndexed { gi, optionName ->
                if (!optionByGroupAndName.containsKey(gi to optionName)) {
                    throw ResponseStatusException(HttpStatus.BAD_REQUEST, "선언되지 않은 Option입니다: $optionName")
                }
            }
        }
        if (skus.map { it.optionNames }.toSet().size != skus.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 Option 조합의 SKU가 중복됐습니다.")
        }
        val savedSkus = skuRepository.saveAll(
            skus.map { spec ->
                val label = if (spec.optionNames.isEmpty()) "기본" else spec.optionNames.joinToString(" / ")
                Sku(productId = productId, optionLabel = label, onHand = spec.onHand)
            },
        )
        skuOptionRepository.saveAll(
            savedSkus.zip(skus).flatMap { (sku, spec) ->
                spec.optionNames.mapIndexed { gi, optionName ->
                    SkuOption(skuId = sku.id!!, optionId = optionByGroupAndName.getValue(gi to optionName).id!!)
                }
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
        // 그룹 이름이 겹치면 조합(SKU)을 이름으로 구분할 수 없다.
        if (optionGroups.map { it.name }.toSet().size != optionGroups.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Option Group 이름이 중복됩니다. 그룹마다 다른 이름을 사용하세요.")
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
    }
}
