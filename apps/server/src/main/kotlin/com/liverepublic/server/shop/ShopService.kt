package com.liverepublic.server.shop

import com.liverepublic.server.tenant.Membership
import com.liverepublic.server.tenant.MembershipRepository
import com.liverepublic.server.tenant.MembershipRole
import com.liverepublic.server.tenant.Tenant
import com.liverepublic.server.tenant.TenantRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime

@Service
class ShopService(
    private val tenantRepository: TenantRepository,
    private val membershipRepository: MembershipRepository,
    private val shopRepository: ShopRepository,
) {

    /** Tenant, Shop과 Owner Membership을 한 Transaction으로 생성한다. 이 Slice에서는 1 Tenant = 1 Shop. */
    @Transactional
    fun createShop(userId: Long, name: String): Shop {
        if (membershipRepository.findByUserIdAndRole(userId, MembershipRole.OWNER) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "이미 운영 중인 Shop이 있습니다.")
        }
        val tenant = tenantRepository.save(Tenant())
        membershipRepository.save(
            Membership(userId = userId, tenantId = tenant.id!!, role = MembershipRole.OWNER),
        )
        return shopRepository.save(Shop(tenantId = tenant.id!!, name = name))
    }

    /** 사용자가 Owner인 Shop. Membership이 격리 경계를 판정한다. */
    @Transactional(readOnly = true)
    fun findMyShop(userId: Long): Shop {
        val membership = membershipRepository.findByUserIdAndRole(userId, MembershipRole.OWNER)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "운영 중인 Shop이 없습니다.")
        return shopRepository.findByTenantId(membership.tenantId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "운영 중인 Shop이 없습니다.")
    }

    @Transactional
    fun updateMyShop(
        userId: Long,
        name: String,
        bankName: String?,
        bankAccountNumber: String?,
        bankAccountHolder: String?,
        courierName: String?,
        baseShippingFee: Int?,
    ): Shop {
        val shop = findMyShop(userId)
        shop.name = name
        shop.bankName = bankName
        shop.bankAccountNumber = bankAccountNumber
        shop.bankAccountHolder = bankAccountHolder
        shop.courierName = courierName
        shop.baseShippingFee = baseShippingFee
        shop.updatedAt = OffsetDateTime.now()
        return shop
    }
}
