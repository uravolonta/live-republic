package com.liverepublic.streamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastUiTest {

    @Test
    fun `상태·capability별 버튼 - 라벨과 동작이 함께 결정된다`() {
        val start = BroadcastUi.ACTION_START
        val end = BroadcastUi.ACTION_END
        // 예정 상태는 누구든 시작 버튼 (서버가 시작 시 임대를 발급한다)
        assertEquals(Triple("방송 시작", true, start), BroadcastUi.action("SCHEDULED", false, true, false, false))
        // 임대 보유 단말
        assertEquals(Triple("시작 취소", true, end), BroadcastUi.action("STARTING", true, true, false, false))
        assertEquals(Triple("방송 종료", true, end), BroadcastUi.action("LIVE", true, true, false, false))
        // 시작 계정의 다른 단말(임대 없음) → 재개 제공
        assertEquals(
            Triple("송출 재개 (이 단말로 이어서 방송)", true, start),
            BroadcastUi.action("LIVE", false, true, false, false),
        )
        // 회귀(실기기 적발): Owner 단말이 임대를 잃으면 canBroadcast·canForceEnd가 동시에
        // 참이다 — 라벨과 동일하게 재개(START)여야 하고 종료가 배선되면 안 된다.
        assertEquals(
            Triple("송출 재개 (이 단말로 이어서 방송)", true, start),
            BroadcastUi.action("LIVE", false, true, true, false),
        )
        // Owner(비시작, 재개 불가) → 강제 종료만
        assertEquals(
            Triple("방송 강제 종료 (Owner)", true, end),
            BroadcastUi.action("LIVE", false, false, true, false),
        )
        // 권한 없는 Streamer → 조작 불가 안내
        assertEquals(false, BroadcastUi.action("LIVE", false, false, false, false).second)
        // 종료 요청 후에는 재시도만 (자동 재송출 금지와 짝)
        assertEquals(Triple("방송 종료 (재시도)", true, end), BroadcastUi.action("LIVE", true, true, false, true))
        assertEquals(false, BroadcastUi.action("ENDED", true, true, true, false).second)
        assertEquals(BroadcastUi.ACTION_NONE, BroadcastUi.action("ENDED", true, true, true, false).third)
    }

    @Test
    fun `송출 시작 조건 - 자격 보유 + 세션 미시작 + 종료 의도 없음`() {
        assertTrue(BroadcastUi.shouldStartStreaming("STARTING", true, sessionStarted = false, endRequested = false))
        assertTrue(BroadcastUi.shouldStartStreaming("LIVE", true, sessionStarted = false, endRequested = false))
        // 세션을 이미 시작했으면(자동 재연결 중 포함) 다시 start하지 않는다.
        assertFalse(BroadcastUi.shouldStartStreaming("LIVE", true, sessionStarted = true, endRequested = false))
        // 종료를 요청한 뒤에는 자동 재송출하지 않는다.
        assertFalse(BroadcastUi.shouldStartStreaming("LIVE", true, sessionStarted = false, endRequested = true))
        assertFalse(BroadcastUi.shouldStartStreaming("STARTING", false, sessionStarted = false, endRequested = false))
        assertFalse(BroadcastUi.shouldStartStreaming("ENDED", true, sessionStarted = false, endRequested = false))
        // 재연결 포기·치명적 오류 후에는 폴링 갱신이 자동 재송출하지 않는다 — 수동 재개만.
        assertFalse(BroadcastUi.shouldStartStreaming("LIVE", true, sessionStarted = false, endRequested = false, failed = true))
    }

    @Test
    fun `확정 재시도 조건 - 연결 유지 + 감지 지연·통신 단절·서버 오류만`() {
        assertTrue(BroadcastUi.confirmShouldRetry(409, connected = true)) // IVS 감지 지연
        assertTrue(BroadcastUi.confirmShouldRetry(0, connected = true)) // 통신 단절
        assertTrue(BroadcastUi.confirmShouldRetry(500, connected = true))
        assertFalse(BroadcastUi.confirmShouldRetry(403, connected = true)) // 임대 상실 — 재시도 무의미
        assertFalse(BroadcastUi.confirmShouldRetry(404, connected = true))
        assertFalse(BroadcastUi.confirmShouldRetry(409, connected = false)) // 연결이 끊기면 중단
    }

    @Test
    fun `확정 백오프 - 2배 증가, 10초 상한`() {
        assertEquals(4_000L, BroadcastUi.nextConfirmDelay(2_000L))
        assertEquals(8_000L, BroadcastUi.nextConfirmDelay(4_000L))
        assertEquals(10_000L, BroadcastUi.nextConfirmDelay(8_000L))
        assertEquals(10_000L, BroadcastUi.nextConfirmDelay(10_000L))
    }

    @Test
    fun `전환 큐잉 - 마지막 선택 보존, 실패 시 같은 상품도 재시도`() {
        assertEquals(null, BroadcastUi.nextSwitch(null, 1L, succeeded = true))
        assertEquals(2L, BroadcastUi.nextSwitch(2L, 1L, succeeded = true)) // 다른 상품으로 이어서 전환
        assertEquals(null, BroadcastUi.nextSwitch(1L, 1L, succeeded = true)) // 이미 반영됨 — 생략
        assertEquals(1L, BroadcastUi.nextSwitch(1L, 1L, succeeded = false)) // 직전 실패 — 같은 상품 재시도
    }

    @Test
    fun `확정 호출 조건 - 임대 보유 단말 + 연결됨 + 종료 의도 없음`() {
        assertTrue(BroadcastUi.shouldConfirm("STARTING", connected = true, canControl = true, endRequested = false))
        assertTrue(BroadcastUi.shouldConfirm("LIVE", connected = true, canControl = true, endRequested = false))
        assertFalse(BroadcastUi.shouldConfirm("STARTING", connected = false, canControl = true, endRequested = false))
        // 임대가 없는 단말은 확정할 수 없다 (사용량 이력 보호).
        assertFalse(BroadcastUi.shouldConfirm("STARTING", connected = true, canControl = false, endRequested = false))
        assertFalse(BroadcastUi.shouldConfirm("LIVE", connected = true, canControl = true, endRequested = true))
        assertFalse(BroadcastUi.shouldConfirm("SCHEDULED", connected = true, canControl = true, endRequested = false))
    }

    @Test
    fun `뒤로가기 차단 - 이 단말이 실제 송출에 관여할 때만`() {
        // 임대 보유 또는 송출 세션 진행 중 → 종료 확인 다이얼로그
        assertTrue(BroadcastUi.shouldBlockExit("LIVE", canControl = true, sessionStarted = true, endRequested = false))
        assertTrue(BroadcastUi.shouldBlockExit("STARTING", canControl = true, sessionStarted = false, endRequested = false))
        // 종료 요청 후에는 결과를 확인할 때까지 화면을 유지한다
        assertTrue(BroadcastUi.shouldBlockExit("LIVE", canControl = false, sessionStarted = false, endRequested = true))
        // 열람만 하는 Owner 단말 — 나가기가 방송을 끊으면 안 된다
        assertFalse(BroadcastUi.shouldBlockExit("LIVE", canControl = false, sessionStarted = false, endRequested = false))
        // 인수당해 송출이 멈춘 구 단말 — 종료가 403이라 다이얼로그를 띄우면 화면에 갇힌다
        assertFalse(BroadcastUi.shouldBlockExit("LIVE", canControl = false, sessionStarted = false, endRequested = false))
        assertFalse(BroadcastUi.shouldBlockExit("SCHEDULED", canControl = true, sessionStarted = false, endRequested = false))
        assertFalse(BroadcastUi.shouldBlockExit("ENDED", canControl = true, sessionStarted = true, endRequested = false))
    }

    @Test
    fun `연결 상태별 표시 문구`() {
        assertTrue(BroadcastUi.statusLabel("LIVE", "t", "CONNECTED").contains("방송중"))
        assertTrue(BroadcastUi.statusLabel("LIVE", "t", "CONNECTING").contains("재연결"))
        assertTrue(BroadcastUi.statusLabel("LIVE", "t", "DISCONNECTED").contains("연결 끊김"))
        assertTrue(BroadcastUi.statusLabel("STARTING", "t", null).contains("송출 연결 중"))
        assertTrue(BroadcastUi.statusLabel("STARTING", "t", "CONNECTED").contains("확정 중"))
        assertTrue(BroadcastUi.statusLabel("SCHEDULED", "t", null).contains("방송 준비"))
    }
}
