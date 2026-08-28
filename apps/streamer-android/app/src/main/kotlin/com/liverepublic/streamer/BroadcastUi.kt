package com.liverepublic.streamer

/**
 * 방송 화면의 상태 전이 규칙 — Activity에서 분리해 단위 테스트한다.
 * 정책(2026-08-28): 앱은 테넌트당 1개 세션만 로그인되므로 이 단말이 곧 방송 단말이다.
 * status: null(진행 중 방송 없음) 또는 서버 Live 상태.
 * connectionState: SDK 연결 상태(CONNECTING/CONNECTED/DISCONNECTED/ERROR) 또는 null(미시작).
 */
object BroadcastUi {

    const val ACTION_START = "START" // 서버 start 호출 — 새 방송 시작 또는 진행 중 방송 재개
    const val ACTION_END = "END"

    fun statusLabel(status: String?, title: String, connectionState: String?): String = when {
        status == null -> "방송 준비 — 시작을 누르면 즉시 라이브가 시작됩니다"
        // ERROR는 재연결을 포기했거나 회복 불가 오류가 난 상태 — '자동 재연결 대기'로
        // 표시하면 사용자가 기다리기만 하게 된다. 수동 재개가 필요함을 알린다.
        (status == "LIVE" || status == "STARTING") && connectionState == "ERROR" ->
            "송출 중단 — '송출 재개'로 다시 시작하세요 — $title"
        status == "LIVE" && connectionState == "CONNECTED" -> "● 방송중 (송출 연결됨) — $title"
        status == "LIVE" && connectionState == "CONNECTING" -> "재연결 시도 중… — $title"
        status == "LIVE" && connectionState == "DISCONNECTED" -> "연결 끊김 — 자동 재연결 대기 — $title"
        status == "LIVE" -> "● 방송중 — $title"
        status == "STARTING" && connectionState == "CONNECTED" -> "방송 확정 중… — $title"
        status == "STARTING" -> "송출 연결 중… — $title"
        else -> "$title ($status)"
    }

    /**
     * 표시할 버튼 목록 (라벨, 동작) — 첫 항목이 주 버튼이다.
     * 라벨과 클릭 동작을 함께 결정한다 (분리 배선의 어긋남 방지 — 실기기에서 적발된 규칙).
     */
    fun actions(
        status: String?,
        streaming: Boolean,
        streamFailed: Boolean,
        endRequested: Boolean,
    ): List<Pair<String, String>> = when {
        status == null -> listOf("방송 시작" to ACTION_START)
        status != "STARTING" && status != "LIVE" -> emptyList()
        endRequested -> listOf("방송 종료 (재시도)" to ACTION_END)
        streamFailed -> listOf(
            "송출 재개" to ACTION_START,
            (if (status == "STARTING") "시작 취소" else "방송 종료") to ACTION_END,
        )
        streaming -> listOf((if (status == "STARTING") "시작 취소" else "방송 종료") to ACTION_END)
        // 진행 중 방송 + 미송출 = 앱 재시작·재로그인 복구 화면 — 재개와 종료를 함께 준다.
        else -> listOf(
            "송출 재개 (이어서 방송)" to ACTION_START,
            (if (status == "STARTING") "시작 취소" else "방송 종료") to ACTION_END,
        )
    }

    /**
     * 송출을 (재)시작해야 하는가 — 이번 앱 실행에서 서버 start로 자격을 받았고(authorized),
     * 세션을 아직 시작하지 않았고, 종료 의도가 없고, 실패 상태가 아닐 때.
     * authorized 조건: 화면 조회(current)만으로 자동 송출하지 않는다 — 재개는 서버 start를
     * 거쳐야 이전 단말 송출 여부 검증·Key 정합이 보장된다.
     */
    fun shouldStartStreaming(
        status: String?,
        hasCredentials: Boolean,
        authorized: Boolean,
        sessionStarted: Boolean,
        endRequested: Boolean,
        failed: Boolean = false,
    ): Boolean =
        (status == "STARTING" || status == "LIVE") && hasCredentials && authorized &&
            !sessionStarted && !endRequested && !failed

    /** 서버 확정을 호출해야 하는가 — SDK가 연결됐고 종료 의도가 없을 때. */
    fun shouldConfirm(status: String?, connected: Boolean, endRequested: Boolean): Boolean =
        connected && !endRequested && (status == "STARTING" || status == "LIVE")

    /**
     * confirm 응답별 재시도 여부 — SDK 연결이 유지될 때 IVS 감지 지연(409)·
     * 통신 단절(0)·서버 오류(5xx)만 재시도한다.
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

    /**
     * 뒤로가기를 종료 확인 다이얼로그로 막아야 하는가 — 이 단말이 실제 송출 중이거나
     * 종료 결과를 확인해야 할 때만. 송출하지 않는 상태에서는 그냥 나갈 수 있다
     * (방송 슬롯은 유지되며 재진입 시 재개할 수 있다).
     */
    fun shouldBlockExit(status: String?, sessionStarted: Boolean, endRequested: Boolean): Boolean =
        (status == "STARTING" || status == "LIVE") && (sessionStarted || endRequested)
}
