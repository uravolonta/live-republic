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

    @Column(name = "on_hand", nullable = false)
    var onHand: Int = 0,

    @Column(nullable = false)
    var reserved: Int = 0,

    @Column(nullable = false)
    var sold: Int = 0,

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
    fun findAllByShopIdOrderByIdDesc(shopId: Long): List<Product>
    fun findByIdAndShopId(id: Long, shopId: Long): Product?
}

interface ProductOptionGroupRepository : JpaRepository<ProductOptionGroup, Long> {
    fun findAllByProductIdOrderByPosition(productId: Long): List<ProductOptionGroup>
}

interface ProductOptionRepository : JpaRepository<ProductOption, Long> {
    fun findAllByOptionGroupIdInOrderByPosition(optionGroupIds: List<Long>): List<ProductOption>
}

interface SkuRepository : JpaRepository<Sku, Long> {
    fun findAllByProductIdOrderById(productId: Long): List<Sku>
    fun findAllByProductIdInOrderById(productIds: List<Long>): List<Sku>
    fun findByIdAndProductId(id: Long, productId: Long): Sku?
}

interface SkuOptionRepository : JpaRepository<SkuOption, SkuOptionId>
