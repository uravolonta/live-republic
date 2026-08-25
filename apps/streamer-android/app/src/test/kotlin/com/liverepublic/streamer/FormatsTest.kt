package com.liverepublic.streamer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class FormatsTest {

    @Test
    fun `서버 Offset 시각을 단말 로컬 시각으로 변환한다`() {
        val seoul = ZoneId.of("Asia/Seoul")
        // UTC 표기(서버가 어떤 Offset으로 보내든)를 단말 타임존으로 환산한다.
        assertEquals("6/1 20:00", Formats.localDateTime("2027-06-01T11:00:00Z", seoul))
        assertEquals("6/1 20:00", Formats.localDateTime("2027-06-01T20:00:00+09:00", seoul))
        // 다른 타임존 단말에서는 그 지역 시각으로 보인다.
        assertEquals("6/1 11:00", Formats.localDateTime("2027-06-01T20:00:00+09:00", ZoneId.of("UTC")))
    }

    @Test
    fun `파싱할 수 없는 값은 원문 앞부분으로 표시한다`() {
        assertEquals("not-a-date", Formats.localDateTime("not-a-date", ZoneId.of("UTC")))
    }
}
