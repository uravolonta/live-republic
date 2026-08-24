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
                val label = labelOf(spec.optionNames)
                Sku(
                    productId = productId,
                    optionLabel = label,
                    optionKey = keyOf(optionGroups, spec.optionNames),
                    onHand = spec.onHand,
                )
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
        productRepository.findAllByShopIdAndDeletedAtIsNullOrderByIdDesc(ownerShopId(userId))

    @Transactional(readOnly = true)
    fun getProduct(userId: Long, productId: Long): Product =
        productRepository.findByIdAndShopIdAndDeletedAtIsNull(productId, ownerShopId(userId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.")

    /** 보관(archive)되지 않은 판매 중 SKU 목록. */
    @Transactional(readOnly = true)
    fun listSkus(productId: Long): List<Sku> =
        skuRepository.findAllByProductIdAndArchivedAtIsNullOrderById(productId)

    @Transactional(readOnly = true)
    fun listSkusByProducts(productIds: List<Long>): Map<Long, List<Sku>> =
        if (productIds.isEmpty()) emptyMap()
        else skuRepository.findAllByProductIdInAndArchivedAtIsNullOrderById(productIds).groupBy { it.productId }

    /**
     * 상품 soft delete — 목록·판매 화면에서 숨기고 데이터는 보존해 이력이 깨지지 않게 한다.
     * 확보(Reserved) 수량이 남은 SKU가 있으면 미입금 주문이 걸려 있으므로 거절한다.
     */
    @Transactional
    fun deleteProduct(userId: Long, productId: Long) {
        val product = getProductForUpdate(userId, productId)
        val reservedSku = skuRepository.findAllByProductIdOrderById(productId).firstOrNull { it.reserved > 0 }
        if (reservedSku != null) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "확보(입금대기) 수량이 남은 SKU(${reservedSku.optionLabel})가 있어 삭제할 수 없습니다.",
            )
        }
        product.deletedAt = OffsetDateTime.now()
        product.updatedAt = OffsetDateTime.now()
    }

    /**
     * Option 구조를 교체하고 SKU를 재계산한다 (새 구조의 전체 조합 기준).
     * - 유지되는 조합: SKU와 재고 유지
     * - 사라지는 조합: 보관(archive) — 확보(Reserved) 수량이 있으면 거절
     * - 과거 보관됐던 같은 조합: 복원해 판매 이력(Sold) 유지
     * - 새 조합: 재고 0의 새 SKU
     */
    @Transactional
    fun replaceOptionStructure(userId: Long, productId: Long, optionGroups: List<NewOptionGroup>): Product {
        // 쓰기 잠금으로 동시 구조 변경을 직렬화한다. (product_id, option_key) UNIQUE가 최종 방어선이다.
        val product = getProductForUpdate(userId, productId)
        validateOptionGroups(optionGroups)

        val newCombos: List<List<String>> =
            optionGroups.fold(listOf(emptyList<String>())) { acc, group ->
                acc.flatMap { combo -> group.options.map { combo + it } }
            }
        if (newCombos.size > MAX_SKUS_PER_PRODUCT) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Option 조합(SKU)은 상품당 최대 ${MAX_SKUS_PER_PRODUCT}개까지 가능합니다. (요청: ${newCombos.size}개)",
            )
        }
        // 동일성 판단은 표시 이름이 아니라 그룹명을 포함한 조합 키로 한다.
        // (색상=빨강 → 소재=빨강처럼 그룹이 바뀌면 다른 조합이다.)
        val newKeys = newCombos.map { keyOf(optionGroups, it) }

        val allSkus = skuRepository.findAllByProductIdOrderById(productId)
        val activeSkus = allSkus.filter { it.archivedAt == null }

        // 사라지는 조합에 확보 수량이 있으면 미입금 주문이 걸려 있으므로 거절한다.
        activeSkus.firstOrNull { it.optionKey !in newKeys && it.reserved > 0 }?.let {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "확보(입금대기) 수량이 남은 SKU(${it.optionLabel})는 구조 변경으로 없앨 수 없습니다.",
            )
        }

        // 기존 Option 구조 행을 교체한다 (SKU 행은 보존).
        skuOptionRepository.deleteAllByProductId(productId)
        optionRepository.deleteAllByProductId(productId)
        optionGroupRepository.deleteAllByProductId(productId)

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

        // SKU 재계산: 유지 조합 재사용, 보관됐던 같은 조합 복원, 새 조합 생성 — 모두 조합 키 기준
        val skuByKey = allSkus.associateBy { it.optionKey }
        val now = OffsetDateTime.now()
        val skuOptions = mutableListOf<SkuOption>()
        newCombos.forEach { combo ->
            val key = keyOf(optionGroups, combo)
            val sku = skuByKey[key]?.also { existing ->
                if (existing.archivedAt != null) {
                    existing.archivedAt = null
                    existing.updatedAt = now
                }
            } ?: skuRepository.save(
                Sku(productId = productId, optionLabel = labelOf(combo), optionKey = key),
            )
            combo.forEachIndexed { gi, optionName ->
                skuOptions += SkuOption(skuId = sku.id!!, optionId = optionByGroupAndName.getValue(gi to optionName).id!!)
            }
        }
        skuOptionRepository.saveAll(skuOptions)

        // 사라지는 활성 조합 보관 (판매 이력 유지)
        activeSkus.filter { it.optionKey !in newKeys }.forEach {
            it.archivedAt = now
            it.updatedAt = now
        }
        product.updatedAt = now
        return product
    }

    private fun labelOf(combo: List<String>): String =
        if (combo.isEmpty()) "기본" else combo.joinToString(" / ")

    private fun getProductForUpdate(userId: Long, productId: Long): Product =
        productRepository.findByIdAndShopIdForUpdate(productId, ownerShopId(userId))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "상품을 찾을 수 없습니다.")

    /**
     * 그룹명을 포함한 안정적 조합 키. 이름에 어떤 문자가 오더라도 이스케이프
     * ('\'→'\\', '='→'\=', '/'→'\/')로 키가 단사(injective)임을 보장한다.
     * combo는 optionGroups와 같은 순서의 옵션 값 목록이다.
     */
    private fun keyOf(optionGroups: List<NewOptionGroup>, combo: List<String>): String =
        if (combo.isEmpty()) "기본"
        else combo.mapIndexed { gi, optionName ->
            "${escapeKeyPart(optionGroups[gi].name)}=${escapeKeyPart(optionName)}"
        }.joinToString(" / ")

    private fun escapeKeyPart(name: String): String =
        name.replace("\\", "\\\\").replace("=", "\\=").replace("/", "\\/")

    /** 현재 Option 구조 (화면의 구조 변경 폼 prefill용). */
    @Transactional(readOnly = true)
    fun listOptionStructure(productId: Long): List<NewOptionGroup> {
        val groups = optionGroupRepository.findAllByProductIdOrderByPosition(productId)
        if (groups.isEmpty()) return emptyList()
        val optionsByGroup = optionRepository
            .findAllByOptionGroupIdInOrderByPosition(groups.mapNotNull { it.id })
            .groupBy { it.optionGroupId }
        return groups.map { group ->
            NewOptionGroup(name = group.name, options = optionsByGroup[group.id].orEmpty().map { it.name })
        }
    }

    /**
     * 상품명·가격·설명만 수정한다. Option 구조 변경은 replaceOptionStructure가 담당한다.
     * 쓰기 잠금으로 읽어 동시 삭제 커밋을 스냅샷 flush가 되돌리는 것을 막는다
     * (잠금 조회의 deleted_at 조건이 재평가되어 삭제 후에는 404가 된다).
     */
    @Transactional
    fun updateProduct(userId: Long, productId: Long, name: String, price: Int, description: String?): Product {
        val product = getProductForUpdate(userId, productId)
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
        // 쓰기 잠금: 동시 삭제·구조 변경(보관)과 직렬화해 보관된 SKU에 재고를 쓰는 경합을 막는다.
        getProductForUpdate(userId, productId)
        val sku = skuRepository.findByIdAndProductId(skuId, productId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "SKU를 찾을 수 없습니다.")
        if (sku.archivedAt != null) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "보관된 SKU는 재고를 수정할 수 없습니다.")
        }
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
            // (조합 키는 이스케이프 인코딩이라 이름 제한이 필요 없다.)
            if (group.options.any { it.contains('/') || it.contains(',') }) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Option 이름에는 '/'와 ','를 사용할 수 없습니다.")
            }
            if (group.options.toSet().size != group.options.size) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "같은 그룹에 중복된 Option이 있습니다.")
            }
        }
    }
}
