package com.liverepublic.server.shop

import com.liverepublic.server.TestcontainersConfiguration
import com.liverepublic.server.tenant.MembershipRepository
import com.liverepublic.server.tenant.MembershipRole
import com.liverepublic.server.user.UserAccount
import com.liverepublic.server.user.UserAccountRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class ShopCreationConcurrencyTest {

    @Autowired
    lateinit var shopService: ShopService

    @Autowired
    lateinit var userAccountRepository: UserAccountRepository

    @Autowired
    lateinit var membershipRepository: MembershipRepository

    @Test
    fun `동시에 Shop을 생성해도 계정 하나에 Owner Shop은 하나만 생긴다`() {
        val user = userAccountRepository.save(
            UserAccount(email = "concurrent@test.local", passwordHash = "not-used", name = "동시성"),
        )
        val userId = user.id!!

        val threads = 4
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val successes = AtomicInteger()
        val conflicts = AtomicInteger()

        val executor = Executors.newFixedThreadPool(threads)
        repeat(threads) { i ->
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    shopService.createShop(userId, "동시 상점 $i")
                    successes.incrementAndGet()
                } catch (e: ResponseStatusException) {
                    if (e.statusCode == HttpStatus.CONFLICT) conflicts.incrementAndGet() else throw e
                }
            }
        }
        ready.await()
        start.countDown()
        executor.shutdown()
        check(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(1, successes.get(), "성공은 정확히 한 번이어야 한다")
        assertEquals(threads - 1, conflicts.get(), "나머지는 모두 409여야 한다")
        assertEquals(
            1,
            membershipRepository.findAll().count { it.userId == userId && it.role == MembershipRole.OWNER },
            "Owner Membership은 하나만 남아야 한다",
        )
    }
}
