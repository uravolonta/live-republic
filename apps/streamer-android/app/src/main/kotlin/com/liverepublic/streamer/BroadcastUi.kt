package com.liverepublic.streamer

/**
 * 방송 화면의 상태 전이 표시 규칙 — Activity에서 분리해 단위 테스트한다.
 * connectionState: SDK 연결 상태(CONNECTING/CONNECTED/DISCONNECTED/ERROR) 또는 null(미시작).
 */
object BroadcastUi {

    fun statusLabel(status: String, title: String, connectionState: String?): String = when {
        status == "LIVE" && connectionState == "CONNECTED" -> "● 방송중 (송출 연결됨) — $title"
        status == "LIVE" && connectionState == "CONNECTING" -> "재연결 시도 중… — $title"
        status == "LIVE" && (connectionState == "DISCONNECTED" || connectionState == "ERROR") ->
            "연결 끊김 — 자동 재연결 대기 — $title"
        status == "LIVE" -> "● 방송중 — $title"
        status == "STARTING" && connectionState == "CONNECTED" -> "방송 확정 중… — $title"
        status == "STARTING" -> "송출 연결 중… — $title"
        status == "SCHEDULED" -> "방송 준비 — $title"
        else -> "$title ($status)"
    }

    /** (버튼 라벨, 활성화 여부). */
    fun action(status: String): Pair<String, Boolean> = when (status) {
        "SCHEDULED" -> "방송 시작" to true
        "STARTING" -> "시작 취소" to true
        "LIVE" -> "방송 종료" to true
        else -> "종료된 방송입니다" to false
    }

    /** 송출을 (재)시작해야 하는가 — 자격이 있고, 아직 연결·연결 시도 중이 아닐 때. */
    fun shouldStartStreaming(status: String, hasCredentials: Boolean, streaming: Boolean): Boolean =
        (status == "STARTING" || status == "LIVE") && hasCredentials && !streaming

    /** 서버 확정을 호출해야 하는가 — SDK 연결됨 + 시작 중(최초) 또는 방송 중(재연결 세션 갱신). */
    fun shouldConfirm(status: String, connected: Boolean): Boolean =
        connected && (status == "STARTING" || status == "LIVE")
}
