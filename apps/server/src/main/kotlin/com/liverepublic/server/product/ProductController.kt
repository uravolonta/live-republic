package com.liverepublic.server.product

import com.liverepublic.server.auth.AuthUser
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
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

data class OptionGroupRequest(
    @field:NotBlank @field:Size(max = 50) val name: String,
    val options: List<@Size(min = 1, max = 50) String>,
)

data class CreateProductRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Min(0) val price: Int,
    @field:Size(max = 2000) val description: String? = null,
    val optionGroups: List<OptionGroupRequest> = emptyList(),
)

data class UpdateProductRequest(
    @field:NotBlank @field:Size(max = 200) val name: String,
    @field:Min(0) val price: Int,
    @field:Size(max = 2000) val description: String? = null,
)

data class UpdateOnHandRequest(
    @field:Min(0) val onHand: Int,
)

data class SkuResponse(
    val id: Long,
    val optionLabel: String,
    val onHand: Int,
    val reserved: Int,
    val sold: Int,
    val available: Int,
) {
    companion object {
        fun from(sku: Sku) = SkuResponse(
            id = sku.id!!,
            optionLabel = sku.optionLabel,
            onHand = sku.onHand,
            reserved = sku.reserved,
            sold = sku.sold,
            available = sku.available,
        )
    }
}

data class ProductResponse(
    val id: Long,
    val name: String,
    val price: Int,
    val description: String?,
    val skus: List<SkuResponse>,
) {
    companion object {
        fun from(product: Product, skus: List<Sku>) = ProductResponse(
            id = product.id!!,
            name = product.name,
            price = product.price,
            description = product.description,
            skus = skus.map(SkuResponse::from),
        )
    }
}

@RestController
@RequestMapping("/api/products")
class ProductController(private val productService: ProductService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: CreateProductRequest,
    ): ProductResponse {
        val productId = productService.createProduct(
            userId = user.id,
            name = request.name.trim(),
            price = request.price,
            description = request.description?.trim()?.ifEmpty { null },
            optionGroups = request.optionGroups.map { group ->
                NewOptionGroup(name = group.name.trim(), options = group.options.map { it.trim() })
            },
        )
        return get(user, productId)
    }

    @GetMapping
    fun list(@AuthenticationPrincipal user: AuthUser): List<ProductResponse> {
        val products = productService.listProducts(user.id)
        val skusByProduct = productService.listSkusByProducts(products.mapNotNull { it.id })
        return products.map { ProductResponse.from(it, skusByProduct[it.id] ?: emptyList()) }
    }

    @GetMapping("/{productId}")
    fun get(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable productId: Long,
    ): ProductResponse {
        val product = productService.getProduct(user.id, productId)
        return ProductResponse.from(product, productService.listSkus(productId))
    }

    @PutMapping("/{productId}")
    fun update(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable productId: Long,
        @Valid @RequestBody request: UpdateProductRequest,
    ): ProductResponse {
        productService.updateProduct(
            userId = user.id,
            productId = productId,
            name = request.name.trim(),
            price = request.price,
            description = request.description?.trim()?.ifEmpty { null },
        )
        return get(user, productId)
    }

    @PutMapping("/{productId}/skus/{skuId}/inventory")
    fun updateOnHand(
        @AuthenticationPrincipal user: AuthUser,
        @PathVariable productId: Long,
        @PathVariable skuId: Long,
        @Valid @RequestBody request: UpdateOnHandRequest,
    ): SkuResponse = SkuResponse.from(
        productService.updateOnHand(user.id, productId, skuId, request.onHand),
    )
}
