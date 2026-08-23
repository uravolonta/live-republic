package com.liverepublic.streamer

import org.junit.Assert.assertEquals
import org.junit.Test

/** CI에서 테스트 실행 경로를 검증하기 위한 최소 테스트. */
class SanityTest {

    @Test
    fun `테스트 인프라가 동작한다`() {
        assertEquals(4, 2 + 2)
    }
}
