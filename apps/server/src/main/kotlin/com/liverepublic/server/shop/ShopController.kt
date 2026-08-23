package com.liverepublic.server.shop

import com.liverepublic.server.auth.AuthUser
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class CreateShopRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
)

data class UpdateShopRequest(
    @field:NotBlank @field:Size(max = 100) val name: String,
    @field:Size(max = 50) val bankName: String? = null,
    @field:Size(max = 50) val bankAccountNumber: String? = null,
    @field:Size(max = 50) val bankAccountHolder: String? = null,
    @field:Size(max = 50) val courierName: String? = null,
    @field:Min(0) val baseShippingFee: Int? = null,
)

data class ShopResponse(
    val id: Long,
    val name: String,
    val bankName: String?,
    val bankAccountNumber: String?,
    val bankAccountHolder: String?,
    val courierName: String?,
    val baseShippingFee: Int?,
) {
    companion object {
        fun from(shop: Shop) = ShopResponse(
            id = shop.id!!,
            name = shop.name,
            bankName = shop.bankName,
            bankAccountNumber = shop.bankAccountNumber,
            bankAccountHolder = shop.bankAccountHolder,
            courierName = shop.courierName,
            baseShippingFee = shop.baseShippingFee,
        )
    }
}

@RestController
@RequestMapping("/api/shops")
class ShopController(private val shopService: ShopService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: CreateShopRequest,
    ): ShopResponse = ShopResponse.from(shopService.createShop(user.id, request.name.trim()))

    @GetMapping("/my")
    fun my(@AuthenticationPrincipal user: AuthUser): ShopResponse =
        ShopResponse.from(shopService.findMyShop(user.id))

    @PutMapping("/my")
    fun update(
        @AuthenticationPrincipal user: AuthUser,
        @Valid @RequestBody request: UpdateShopRequest,
    ): ShopResponse = ShopResponse.from(
        shopService.updateMyShop(
            userId = user.id,
            name = request.name.trim(),
            bankName = request.bankName?.trim()?.ifEmpty { null },
            bankAccountNumber = request.bankAccountNumber?.trim()?.ifEmpty { null },
            bankAccountHolder = request.bankAccountHolder?.trim()?.ifEmpty { null },
            courierName = request.courierName?.trim()?.ifEmpty { null },
            baseShippingFee = request.baseShippingFee,
        ),
    )
}
