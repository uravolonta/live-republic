package com.liverepublic.streamer

import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 표시용 변환 — Activity에서 분리해 단위 테스트한다. */
object Formats {

    private val pattern = DateTimeFormatter.ofPattern("M/d HH:mm")

    /**
     * 서버의 ISO-8601(Offset 포함) 시각을 단말 로컬 시각으로 변환한다.
     * 서버 타임존 문자열을 그대로 자르면 단말 시각과 어긋난다. 파싱 실패 시 원문 앞부분.
     */
    fun localDateTime(iso: String, zone: ZoneId = ZoneId.systemDefault()): String = try {
        OffsetDateTime.parse(iso).atZoneSameInstant(zone).format(pattern)
    } catch (e: Exception) {
        iso.take(16)
    }
}
