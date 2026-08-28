package com.liverepublic.streamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastUiTest {

    private val start = BroadcastUi.ACTION_START
    private val end = BroadcastUi.ACTION_END

    @Test
    fun `상태별 버튼 - 라벨과 동작이 함께 결정된다`() {
        // 진행 중 방송 없음 → 즉시 시작
        assertEquals(
            listOf("방송 시작" to start),
            BroadcastUi.actions(null, streaming = false, streamFailed = false, endRequested = false),
        )
        // 송출 중 → 종료만
        assertEquals(
            listOf("방송 종료" to end),
            BroadcastUi.actions("LIVE", streaming = true, streamFailed = false, endRequested = false),
        )
        assertEquals(
            listOf("시작 취소" to end),
            BroadcastUi.actions("STARTING", streaming = true, streamFailed = false, endRequested = false),
        )
        // 송출 실패(재연결 포기·치명적 오류) → 재개 + 종료 두 버튼
        assertEquals(
            listOf("송출 재개" to start, "방송 종료" to end),
            BroadcastUi.actions("LIVE", streaming = false, streamFailed = true, endRequested = false),
        )
        // 진행 중 방송 + 미송출(앱 재시작·재로그인 복구) → 재개 + 종료
        assertEquals(
            listOf("송출 재개 (이어서 방송)" to start, "방송 종료" to end),
            BroadcastUi.actions("LIVE", streaming = false, streamFailed = false, endRequested = false),
        )
        // 종료 요청 후에는 재시도만 (자동 재송출 금지와 짝)
        assertEquals(
            listOf("방송 종료 (재시도)" to end),
            BroadcastUi.actions("LIVE", streaming = true, streamFailed = false, endRequested = true),
        )
        assertEquals(
            emptyList<Pair<String, String>>(),
            BroadcastUi.actions("ENDED", streaming = false, streamFailed = false, endRequested = false),
        )
    }

    @Test
    fun `송출 시작 조건 - 서버 start 자격 + 세션 미시작 + 종료 의도·실패 없음`() {
        assertTrue(BroadcastUi.shouldStartStreaming("STARTING", true, authorized = true, sessionStarted = false, endRequested = false))
        assertTrue(BroadcastUi.shouldStartStreaming("LIVE", true, authorized = true, sessionStarted = false, endRequested = false))
        // 조회(current)만으로는 자동 송출하지 않는다 — 재개는 서버 start를 거쳐야 한다
        // (이전 단말 송출 여부 검증·Key 정합 보장).
        assertFalse(BroadcastUi.shouldStartStreaming("LIVE", true, authorized = false, sessionStarted = false, endRequested = false))
        // 세션을 이미 시작했으면(자동 재연결 중 포함) 다시 start하지 않는다.
        assertFalse(BroadcastUi.shouldStartStreaming("LIVE", true, authorized = true, sessionStarted = true, endRequested = false))
        // 종료를 요청한 뒤에는 자동 재송출하지 않는다.
        assertFalse(BroadcastUi.shouldStartStreaming("LIVE", true, authorized = true, sessionStarted = false, endRequested = true))
        // 실패(재연결 포기·치명적 오류) 후에는 폴링 갱신이 자동 재송출하지 않는다 — 수동 재개만.
        assertFalse(
            BroadcastUi.shouldStartStreaming("LIVE", true, authorized = true, sessionStarted = false, endRequested = false, failed = true),
        )
        assertFalse(BroadcastUi.shouldStartStreaming("STARTING", false, authorized = true, sessionStarted = false, endRequested = false))
        assertFalse(BroadcastUi.shouldStartStreaming("ENDED", true, authorized = true, sessionStarted = false, endRequested = false))
        assertFalse(BroadcastUi.shouldStartStreaming(null, true, authorized = true, sessionStarted = false, endRequested = false))
    }

    @Test
    fun `확정 호출 조건 - 연결됨 + 종료 의도 없음`() {
        assertTrue(BroadcastUi.shouldConfirm("STARTING", connected = true, endRequested = false))
        assertTrue(BroadcastUi.shouldConfirm("LIVE", connected = true, endRequested = false))
        assertFalse(BroadcastUi.shouldConfirm("STARTING", connected = false, endRequested = false))
        assertFalse(BroadcastUi.shouldConfirm("LIVE", connected = true, endRequested = true))
        assertFalse(BroadcastUi.shouldConfirm(null, connected = true, endRequested = false))
    }

    @Test
    fun `확정 재시도 조건 - 연결 유지 + 감지 지연·통신 단절·서버 오류만`() {
        assertTrue(BroadcastUi.confirmShouldRetry(409, connected = true)) // IVS 감지 지연
        assertTrue(BroadcastUi.confirmShouldRetry(0, connected = true)) // 통신 단절
        assertTrue(BroadcastUi.confirmShouldRetry(500, connected = true))
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
    fun `뒤로가기 차단 - 실제 송출 중이거나 종료 확인 대기일 때만`() {
        assertTrue(BroadcastUi.shouldBlockExit("LIVE", sessionStarted = true, endRequested = false))
        assertTrue(BroadcastUi.shouldBlockExit("STARTING", sessionStarted = false, endRequested = true))
        // 미송출 상태(재개 대기)는 그냥 나갈 수 있다 — 재진입 시 재개 가능.
        assertFalse(BroadcastUi.shouldBlockExit("LIVE", sessionStarted = false, endRequested = false))
        assertFalse(BroadcastUi.shouldBlockExit(null, sessionStarted = false, endRequested = false))
        assertFalse(BroadcastUi.shouldBlockExit("ENDED", sessionStarted = true, endRequested = false))
    }

    @Test
    fun `연결 상태별 표시 문구`() {
        assertTrue(BroadcastUi.statusLabel(null, "", null).contains("방송 준비"))
        assertTrue(BroadcastUi.statusLabel("LIVE", "t", "CONNECTED").contains("방송중"))
        assertTrue(BroadcastUi.statusLabel("LIVE", "t", "CONNECTING").contains("재연결"))
        assertTrue(BroadcastUi.statusLabel("LIVE", "t", "DISCONNECTED").contains("자동 재연결 대기"))
        // ERROR는 재연결 포기 상태 — '자동 재연결 대기'가 아니라 수동 재개 안내여야 한다.
        assertTrue(BroadcastUi.statusLabel("LIVE", "t", "ERROR").contains("송출 중단"))
        assertTrue(BroadcastUi.statusLabel("STARTING", "t", "ERROR").contains("송출 중단"))
        assertTrue(BroadcastUi.statusLabel("STARTING", "t", null).contains("송출 연결 중"))
        assertTrue(BroadcastUi.statusLabel("STARTING", "t", "CONNECTED").contains("확정 중"))
    }
}
