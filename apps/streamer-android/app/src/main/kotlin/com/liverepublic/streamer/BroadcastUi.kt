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

    const val ACTION_START = "START"
    const val ACTION_END = "END"
    const val ACTION_NONE = "NONE"

    /**
     * (버튼 라벨, 활성화 여부, 동작 종류). 서버가 내려준 capability로 판단한다:
     * canControl = 이 단말이 송출 임대를 보유, canBroadcast = 임대를 (재)획득할 수 있음,
     * canForceEnd = Owner의 강제 종료 권한.
     * 라벨과 클릭 동작을 여기서 함께 결정한다 — Owner 단말이 임대를 잃으면
     * canBroadcast·canForceEnd가 동시에 참이므로, 별도 분기로 배선하면
     * "송출 재개" 라벨에 종료 동작이 붙는 어긋남이 생긴다 (실기기에서 적발).
     */
    fun action(
        status: String,
        canControl: Boolean,
        canBroadcast: Boolean,
        canForceEnd: Boolean,
        endRequested: Boolean,
    ): Triple<String, Boolean, String> = when {
        status == "SCHEDULED" -> Triple("방송 시작", true, ACTION_START)
        status != "STARTING" && status != "LIVE" -> Triple("종료된 방송입니다", false, ACTION_NONE)
        endRequested -> Triple("방송 종료 (재시도)", true, ACTION_END)
        canControl -> Triple(if (status == "STARTING") "시작 취소" else "방송 종료", true, ACTION_END)
        canBroadcast -> Triple("송출 재개 (이 단말로 이어서 방송)", true, ACTION_START)
        canForceEnd -> Triple("방송 강제 종료 (Owner)", true, ACTION_END)
        else -> Triple("시작한 단말에서 조작할 수 있습니다", false, ACTION_NONE)
    }

    /**
     * 송출을 (재)시작해야 하는가 — 임대 자격이 있고, 세션을 아직 시작하지 않았고,
     * 종료 의도가 없고, 송출이 실패로 끝난 상태(failed: 재연결 포기·치명적 오류)가 아닐 때.
     * 실패 후에는 사용자의 명시적 재개(start 재호출)만 허용한다 — 폴링 갱신이 자동
     * 재송출을 반복하지 않게 한다.
     */
    fun shouldStartStreaming(
        status: String,
        hasCredentials: Boolean,
        sessionStarted: Boolean,
        endRequested: Boolean,
        failed: Boolean = false,
    ): Boolean =
        (status == "STARTING" || status == "LIVE") && hasCredentials &&
            !sessionStarted && !endRequested && !failed

    /** 서버 확정을 호출해야 하는가 — 임대 보유 단말이 SDK 연결됐고 종료 의도가 없을 때. */
    fun shouldConfirm(status: String, connected: Boolean, canControl: Boolean, endRequested: Boolean): Boolean =
        connected && canControl && !endRequested && (status == "STARTING" || status == "LIVE")

    /**
     * confirm 응답별 재시도 여부 — SDK 연결이 유지될 때 IVS 감지 지연(409)·
     * 통신 단절(0)·서버 오류(5xx)만 재시도한다. 그 외(403 임대 상실 등)는 중단.
     */
    fun confirmShouldRetry(httpStatus: Int, connected: Boolean): Boolean =
        connected && (httpStatus == 409 || httpStatus == 0 || httpStatus >= 500)

    /** confirm 재시도 백오프 — 2배씩 늘리되 10초를 넘지 않는다. */
    fun nextConfirmDelay(currentMs: Long): Long = (currentMs * 2).coerceAtMost(10_000L)

    /**
     * 전환 완료 후 이어서 전환할 상품 — 진행 중 사용자가 마지막으로 고른 상품(pendingId)이
     * 다른 상품이거나, 같은 상품이라도 직전 시도가 실패했으면 재시도한다.
     */
    fun nextSwitch(pendingId: Long?, attemptedId: Long, succeeded: Boolean): Long? =
        pendingId?.takeIf { it != attemptedId || !succeeded }
}
