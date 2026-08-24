package com.liverepublic.streamer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastUiTest {

    @Test
    fun `상태별 버튼 라벨과 활성화`() {
        assertEquals("방송 시작" to true, BroadcastUi.action("SCHEDULED"))
        assertEquals("시작 취소" to true, BroadcastUi.action("STARTING"))
        assertEquals("방송 종료" to true, BroadcastUi.action("LIVE"))
        assertEquals(false, BroadcastUi.action("ENDED").second)
        assertEquals(false, BroadcastUi.action("CANCELLED").second)
    }

    @Test
    fun `송출 재시작 조건 - 자격이 있고 연결 중이 아닐 때만`() {
        assertTrue(BroadcastUi.shouldStartStreaming("STARTING", hasCredentials = true, streaming = false))
        assertTrue(BroadcastUi.shouldStartStreaming("LIVE", hasCredentials = true, streaming = false))
        // 이미 연결(시도) 중이면 재시작하지 않는다 — 중복 start 방지.
        assertFalse(BroadcastUi.shouldStartStreaming("LIVE", hasCredentials = true, streaming = true))
        // 자격이 없으면 시작할 수 없다.
        assertFalse(BroadcastUi.shouldStartStreaming("STARTING", hasCredentials = false, streaming = false))
        // 예정·종료 상태에서는 송출하지 않는다.
        assertFalse(BroadcastUi.shouldStartStreaming("SCHEDULED", hasCredentials = true, streaming = false))
        assertFalse(BroadcastUi.shouldStartStreaming("ENDED", hasCredentials = true, streaming = false))
    }

    @Test
    fun `확정 호출 조건 - 연결됨 + 시작 중 또는 방송 중(재연결 세션 갱신)`() {
        assertTrue(BroadcastUi.shouldConfirm("STARTING", connected = true))
        assertTrue(BroadcastUi.shouldConfirm("LIVE", connected = true))
        assertFalse(BroadcastUi.shouldConfirm("STARTING", connected = false))
        assertFalse(BroadcastUi.shouldConfirm("SCHEDULED", connected = true))
        assertFalse(BroadcastUi.shouldConfirm("ENDED", connected = true))
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
