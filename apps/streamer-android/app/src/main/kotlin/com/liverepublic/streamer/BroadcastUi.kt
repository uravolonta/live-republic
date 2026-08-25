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

    /**
     * (버튼 라벨, 활성화 여부). 서버가 내려준 capability로 판단한다:
     * canControl = 이 단말이 송출 임대를 보유, canBroadcast = 임대를 (재)획득할 수 있음,
     * canForceEnd = Owner의 강제 종료 권한.
     */
    fun action(
        status: String,
        canControl: Boolean,
        canBroadcast: Boolean,
        canForceEnd: Boolean,
        endRequested: Boolean,
    ): Pair<String, Boolean> = when {
        status == "SCHEDULED" -> "방송 시작" to true
        status != "STARTING" && status != "LIVE" -> "종료된 방송입니다" to false
        endRequested -> "방송 종료 (재시도)" to true
        canControl -> (if (status == "STARTING") "시작 취소" else "방송 종료") to true
        canBroadcast -> "송출 재개 (이 단말로 이어서 방송)" to true
        canForceEnd -> "방송 강제 종료 (Owner)" to true
        else -> "시작한 단말에서 조작할 수 있습니다" to false
    }

    /** 송출을 (재)시작해야 하는가 — 임대 자격이 있고, 세션을 아직 시작하지 않았고, 종료 의도가 없을 때. */
    fun shouldStartStreaming(
        status: String,
        hasCredentials: Boolean,
        sessionStarted: Boolean,
        endRequested: Boolean,
    ): Boolean =
        (status == "STARTING" || status == "LIVE") && hasCredentials && !sessionStarted && !endRequested

    /** 서버 확정을 호출해야 하는가 — 임대 보유 단말이 SDK 연결됐고 종료 의도가 없을 때. */
    fun shouldConfirm(status: String, connected: Boolean, canControl: Boolean, endRequested: Boolean): Boolean =
        connected && canControl && !endRequested && (status == "STARTING" || status == "LIVE")
}
