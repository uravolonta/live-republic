package com.liverepublic.server.product

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.OffsetDateTime

/**
 * Seller가 판매하는 상품. 가격은 상품 단위이며 Option 추가금은 이 Slice에 없다.
 * 커머스 도메인 엔티티는 Shop을 참조한다 (Tenant는 계정·Membership 격리 경계).
 */
@Entity
@Table(name = "product")
class Product(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "shop_id", nullable = false)
    val shopId: Long,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var price: Int,

    @Column
    var description: String? = null,

    /** soft delete 시각. 삭제된 상품은 목록·판매 화면에서 숨기고 데이터는 보존한다. */
    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    val createdAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)

/** Product의 선택 조건 묶음 (예: 색상). */
@Entity
@Table(name = "product_option_group")
class ProductOptionGroup(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val position: Int,
)

/** Option Group 안의 선택지 (예: 빨강). */
@Entity
@Table(name = "product_option")
class ProductOption(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "option_group_id", nullable = false)
    val optionGroupId: Long,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val position: Int,
)

/** 재고와 판매 단위. Available = onHand - reserved. */
@Entity
@Table(name = "sku")
class Sku(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "product_id", nullable = false)
    val productId: Long,

    @Column(name = "option_label", nullable = false)
    val optionLabel: String,

    /** 그룹명을 포함한 안정적 조합 식별자 (예: "색상=빨강 / 사이즈=M"). 구조 변경 시 동일성 판단에 사용한다. */
    @Column(name = "option_key", nullable = false)
    val optionKey: String,

    @Column(name = "on_hand", nullable = false)
    var onHand: Int = 0,

    @Column(nullable = false)
    var reserved: Int = 0,

    @Column(nullable = false)
    var sold: Int = 0,

    /** Option 구조 변경으로 사라진 조합의 보관 시각. 판매 이력(Sold)은 그대로 유지된다. */
    @Column(name = "archived_at")
    var archivedAt: OffsetDateTime? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
) {
    /** VS-001 §4: Available = On Hand − Reserved − Sold. */
    val available: Int
        get() = onHand - reserved - sold
}

@Entity
@Table(name = "sku_option")
@jakarta.persistence.IdClass(SkuOptionId::class)
class SkuOption(
    @Id
    @Column(name = "sku_id")
    val skuId: Long,

    @Id
    @Column(name = "option_id")
    val optionId: Long,
)

data class SkuOptionId(
    val skuId: Long = 0,
    val optionId: Long = 0,
) : java.io.Serializable

interface ProductRepository : JpaRepository<Product, Long> {
    fun findAllByShopIdAndDeletedAtIsNullOrderByIdDesc(shopId: Long): List<Product>
    fun findByIdAndShopIdAndDeletedAtIsNull(id: Long, shopId: Long): Product?
}

interface ProductOptionGroupRepository : JpaRepository<ProductOptionGroup, Long> {
    fun findAllByProductIdOrderByPosition(productId: Long): List<ProductOptionGroup>

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("delete from ProductOptionGroup g where g.productId = :productId")
    fun deleteAllByProductId(productId: Long)
}

interface ProductOptionRepository : JpaRepository<ProductOption, Long> {
    fun findAllByOptionGroupIdInOrderByPosition(optionGroupIds: List<Long>): List<ProductOption>

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
        "delete from ProductOption o where o.optionGroupId in (select g.id from ProductOptionGroup g where g.productId = :productId)",
    )
    fun deleteAllByProductId(productId: Long)
}

interface SkuRepository : JpaRepository<Sku, Long> {
    fun findAllByProductIdOrderById(productId: Long): List<Sku>
    fun findAllByProductIdAndArchivedAtIsNullOrderById(productId: Long): List<Sku>
    fun findAllByProductIdInAndArchivedAtIsNullOrderById(productIds: List<Long>): List<Sku>
    fun findByIdAndProductId(id: Long, productId: Long): Sku?
}

interface SkuOptionRepository : JpaRepository<SkuOption, SkuOptionId> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
        "delete from SkuOption so where so.skuId in (select s.id from Sku s where s.productId = :productId)",
    )
    fun deleteAllByProductId(productId: Long)
}
