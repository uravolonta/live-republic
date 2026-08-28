package com.liverepublic.streamer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.amazonaws.ivs.broadcast.BroadcastException
import com.amazonaws.ivs.broadcast.BroadcastSession
import com.amazonaws.ivs.broadcast.Presets
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 방송 화면 — 로그인 후의 유일한 화면이다 (2026-08-28 정책: 앱 세션은 테넌트당 1개,
 * 방송은 예약 선택 없이 즉시 시작, 판매 상품은 Owner Web 사전 구성).
 * 카메라 미리보기 → 실제 IVS 송출 시작·종료, 오버레이에서 현재 판매 상품 전환·재고 확인.
 */
class BroadcastActivity : AppCompatActivity() {

    private var session: BroadcastSession? = null
    /** 진행 중 방송 상세 — null이면 방송 없음(시작 대기 화면). */
    private var detail: JSONObject? = null
    /** session.start()를 호출했고 stop/release하지 않은 상태 — 자동 재연결 중에도 유지된다. */
    private var sessionStarted = false
    private var connectionState: String? = null
    /** 이번 앱 실행에서 서버 start로 송출 자격을 받았는가 — 조회만으로 자동 송출하지 않는다. */
    private var streamAuthorized = false
    private var confirmJob: Job? = null
    private var pollJob: Job? = null
    /** 사용자가 종료를 요청함 — 이후에는 어떤 화면 갱신도 자동 재송출하지 않는다. */
    private var endRequested = false
    private var pendingSwitchId: Long? = null
    private var switching = false
    /**
     * 조작(start/confirm/switch/end) 결과가 detail에 반영된 세대. 그 이전에 발사된
     * 폴/조회 응답은 버린다 — 낡은 응답이 방금 시작한 송출을 덮어써 끊는 경합 방지.
     */
    private var mutationGeneration = 0
    /** CONNECTED 이후 서버 확정이 아직 필요한가 — 폴마다 confirm을 반복하지 않기 위한 플래그. */
    private var needsConfirm = false
    /** 상품 바 재구성 판단용 — 매 폴마다 재구성하면 스크롤 위치가 리셋되고 터치가 유실된다. */
    private var productBarSignature: String? = null

    private lateinit var previewContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var productBar: LinearLayout
    private lateinit var skuText: TextView
    private lateinit var actionButton: Button
    private lateinit var secondaryButton: Button

    private fun liveId(): Long? = detail?.optLong("id")?.takeIf { it != 0L }

    private val sessionListener = object : BroadcastSession.Listener() {
        override fun onStateChanged(state: BroadcastSession.State) {
            runOnUiThread {
                connectionState = state.name
                when (state) {
                    BroadcastSession.State.CONNECTED -> {
                        // 실제 연결이 확인된 뒤에만 서버가 방송 중으로 확정한다.
                        // (재연결 시에도 호출되어 새 Stream Session이 이력에 기록된다.)
                        needsConfirm = true
                        confirmLive()
                    }
                    // DISCONNECTED/ERROR에서도 sessionStarted는 유지한다 —
                    // autoReconnect가 같은 세션 안에서 재시도하므로 start를 다시 부르면 안 된다.
                    else -> Unit
                }
                updateStatusText()
            }
        }

        override fun onError(exception: BroadcastException) {
            runOnUiThread {
                if (exception.isFatal) {
                    // 회복 불가 오류는 RetryState.FAILURE 없이 세션이 죽을 수 있다 —
                    // FAILURE와 동일하게 처리해 수동 재개만 허용한다.
                    sessionStarted = false
                    connectionState = "ERROR"
                    updateStatusText()
                    Toast.makeText(
                        this@BroadcastActivity,
                        "송출이 중단되었습니다: ${exception.detail} — '송출 재개'로 다시 시작하거나 방송을 종료하세요.",
                        Toast.LENGTH_LONG,
                    ).show()
                    refresh()
                } else {
                    Toast.makeText(this@BroadcastActivity, "송출 오류: ${exception.detail}", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** 자동 재연결의 대기·재시도·실패는 이 콜백으로 전달된다. */
        override fun onRetryStateChanged(state: BroadcastSession.RetryState) {
            runOnUiThread {
                when (state) {
                    BroadcastSession.RetryState.WAITING_FOR_INTERNET,
                    BroadcastSession.RetryState.WAITING_FOR_BACKOFF_TIMER,
                    BroadcastSession.RetryState.RETRYING,
                    -> {
                        connectionState = "CONNECTING"
                        updateStatusText()
                    }
                    BroadcastSession.RetryState.FAILURE -> {
                        sessionStarted = false // SDK가 재연결을 포기했다 — 수동 재개만 허용
                        connectionState = "ERROR"
                        updateStatusText()
                        render()
                        Toast.makeText(
                            this@BroadcastActivity,
                            "자동 재연결에 실패했습니다. '송출 재개'로 다시 시작하거나 방송을 종료하세요.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateStatusText() {
        statusText.text = BroadcastUi.statusLabel(
            detail?.optString("status"), detail?.optString("title") ?: "", connectionState,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 방송 중 화면이 꺼지지 않게 유지한다 (정책: 방송은 이 화면에서만 송출).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 실제 송출 중일 때만 뒤로 가기에 종료 확인을 받는다. 미송출 상태에서는 그냥
        // 나갈 수 있다 (방송 슬롯은 유지되며 재진입 시 재개할 수 있다).
        onBackPressedDispatcher.addCallback(this) {
            if (BroadcastUi.shouldBlockExit(detail?.optString("status"), sessionStarted, endRequested)) {
                android.app.AlertDialog.Builder(this@BroadcastActivity)
                    .setMessage("방송 중입니다. 나가려면 방송을 종료해야 합니다.")
                    .setPositiveButton("방송 종료 후 나가기") { _, _ -> end(exitAfter = true) }
                    .setNegativeButton("계속 방송", null)
                    .show()
            } else {
                finish()
            }
        }

        previewContainer = FrameLayout(this)
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 12, 24, 12)
        }
        // 상단 상태바·카메라 컷아웃(edge-to-edge)에 가려지지 않게 인셋만큼 띄운다.
        ViewCompat.setOnApplyWindowInsetsListener(statusText) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(24, bars.top + 12, 24, 12)
            insets
        }
        productBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        skuText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 12, 24, 12)
        }
        actionButton = Button(this)
        secondaryButton = Button(this)

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            addView(skuText)
            addView(HorizontalScrollView(context).apply { addView(productBar) })
            addView(actionButton)
            addView(secondaryButton)
        }
        // 하단 시스템 내비게이션 바에 버튼이 가려지지 않게 인셋만큼 띄운다.
        ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        setContentView(
            FrameLayout(this).apply {
                addView(previewContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                addView(statusText)
                addView(
                    overlay,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
                    ),
                )
            },
        )

        if (hasPermissions()) setUp() else ActivityCompat.requestPermissions(this, PERMISSIONS, 1)
    }

    private fun hasPermissions(): Boolean = PERMISSIONS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermissions()) {
            setUp()
        } else {
            Toast.makeText(this, "방송에는 카메라·마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** 카메라 미리보기를 붙이고 현재 방송 상태를 불러온다. */
    private fun setUp() {
        createSession()
        refresh()
        startPolling()
    }

    private fun createSession() {
        // BASIC Channel 한도(480p 초과 시 최대 3.5Mbps)에 맞춘 커스텀 설정 — 공유 프리셋을
        // 변경하지 않는 독립 객체다. 720p/30fps (2026-08-24 결정) + 자동 재연결.
        val config = com.amazonaws.ivs.broadcast.BroadcastConfiguration().apply {
            video.setSize(720, 1280)
            video.setTargetFramerate(30)
            video.setInitialBitrate(1_800_000)
            video.setMaxBitrate(3_000_000) // BASIC 한도 3.5Mbps 이내 여유
            video.setMinBitrate(500_000)
            autoReconnect.setEnabled(true)
        }
        val broadcastSession = BroadcastSession(
            this, sessionListener,
            config,
            Presets.Devices.BACK_CAMERA(this),
        )
        session = broadcastSession
        broadcastSession.awaitDeviceChanges {
            try {
                val preview = broadcastSession.previewView
                previewContainer.addView(
                    preview,
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            } catch (e: Exception) {
                Toast.makeText(this, "카메라 미리보기를 열 수 없습니다.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 실패한 세션 인스턴스의 start 재호출을 SDK가 보증하지 않으므로,
     * 재개 시 세션을 새로 만든다.
     */
    private fun recreateSession() {
        session?.release()
        session = null
        previewContainer.removeAllViews()
        createSession()
    }

    /** 세션 만료(강제 로그아웃·재로그인 대체) — 로그인 화면으로 돌려보낸다. */
    private fun handleSessionExpired() {
        Toast.makeText(this, "앱 세션이 종료되었습니다. (다른 로그인 또는 관리자 로그아웃)", Toast.LENGTH_LONG).show()
        session?.stop()
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val generation = mutationGeneration
            val result = ApiClient.get("/api/broadcast/current")
            if (generation != mutationGeneration) return@launch
            val body = if (result.status == 200) ApiClient.json(result) else null
            when {
                body != null -> {
                    detail = if (body.isNull("live")) null else body.optJSONObject("live")
                    applyServerState()
                    render()
                }
                result.status == 401 -> handleSessionExpired()
                detail == null -> {
                    // 최초 진입 실패 — 재시도 버튼을 준다.
                    statusText.text = "상태를 불러오지 못했습니다 — 연결을 확인하세요"
                    actionButton.text = "다시 불러오기"
                    actionButton.isEnabled = true
                    actionButton.setOnClickListener { refresh() }
                    secondaryButton.visibility = android.view.View.GONE
                }
                else ->
                    // 방송 중의 일시적 조회 실패 — 기존 버튼(종료 등)을 유지하고 안내만 한다.
                    Toast.makeText(
                        this@BroadcastActivity, "상태를 갱신하지 못했습니다. 연결을 확인하세요.", Toast.LENGTH_SHORT,
                    ).show()
            }
        }
    }

    /**
     * 방송 중 주기 폴링 — 재고 변화를 실시간으로 보여주고(PRD §7),
     * 대시보드 강제 종료·강제 로그아웃 같은 서버 측 변화를 감지한다.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (isFinishing) return@launch
                val generation = mutationGeneration
                val result = ApiClient.get("/api/broadcast/current")
                // 폴 도중 조작(start 등)이 완료됐다면 이 응답은 낡았다 — 버린다.
                if (generation != mutationGeneration) continue
                if (result.status == 401) {
                    handleSessionExpired()
                    return@launch
                }
                if (result.status == 200) {
                    ApiClient.json(result)?.let { body ->
                        detail = if (body.isNull("live")) null else body.optJSONObject("live")
                        applyServerState()
                        render()
                    }
                }
                // 그 외 폴링 실패는 조용히 넘긴다 — 다음 주기에 다시 시도한다.
            }
        }
    }

    /** 서버 상태와 단말 송출을 동기화 — 대시보드에서 방송이 종료됐으면 송출을 멈춘다. */
    private fun applyServerState() {
        val status = detail?.optString("status")
        val active = status == "STARTING" || status == "LIVE"
        if (!active) {
            streamAuthorized = false
            if (sessionStarted && !endRequested) {
                confirmJob?.cancel()
                session?.stop()
                sessionStarted = false
                Toast.makeText(this, "대시보드에서 방송이 종료되어 송출을 중단합니다.", Toast.LENGTH_LONG).show()
            }
            if (!active) endRequested = false // 방송이 끝났으면 다음 방송을 위해 초기화
        }
    }

    private fun render() {
        val live = detail
        val status = live?.optString("status")
        updateStatusText()
        renderProductBar(live)

        val streamFailed = connectionState == "ERROR"
        val actions = BroadcastUi.actions(status, sessionStarted, streamFailed, endRequested)
        bindButton(actionButton, actions.getOrNull(0))
        bindButton(secondaryButton, actions.getOrNull(1))

        if (status == "STARTING" || status == "LIVE") {
            startStreamingIfNeeded(live!!)
            // 확정은 연결 이벤트당 1회면 충분하다 — 폴마다 반복하면 서버가 매번
            // IVS GetStream을 호출해 읽기 한도를 잠식한다.
            if (connectionState == "CONNECTED" && needsConfirm) confirmLive()
        }
    }

    private fun bindButton(button: Button, action: Pair<String, String>?) {
        if (action == null) {
            button.visibility = android.view.View.GONE
            return
        }
        button.visibility = android.view.View.VISIBLE
        button.text = action.first
        button.isEnabled = true
        when (action.second) {
            BroadcastUi.ACTION_START -> button.setOnClickListener { start() }
            BroadcastUi.ACTION_END -> button.setOnClickListener { end() }
            else -> button.setOnClickListener(null)
        }
    }

    /** 상품 바·재고 표시 — 내용이 실제로 바뀐 경우만 재구성해 스크롤 위치·터치를 보존한다. */
    private fun renderProductBar(live: JSONObject?) {
        val products = live?.optJSONArray("products") ?: org.json.JSONArray()
        val currentId = if (live == null || live.isNull("currentLiveProductId")) null else live.optLong("currentLiveProductId")
        val signature = "$products|$currentId"
        if (signature == productBarSignature) return
        productBarSignature = signature

        productBar.removeAllViews()
        var currentSkuSummary =
            if (live == null) "시작하면 Owner Web에서 구성한 상품이 연결됩니다" else "현재 판매 상품이 없습니다"
        for (i in 0 until products.length()) {
            val product = products.optJSONObject(i) ?: continue
            val liveProductId = product.optLong("liveProductId")
            val isCurrent = liveProductId == currentId
            if (isCurrent) {
                val skus = product.optJSONArray("skus") ?: org.json.JSONArray()
                currentSkuSummary = buildString {
                    append("재고: ")
                    for (s in 0 until skus.length()) {
                        val sku = skus.optJSONObject(s) ?: continue
                        if (s > 0) append(" · ")
                        append("${sku.optString("optionLabel")} ${sku.optInt("available")}")
                    }
                }
            }
            productBar.addView(Button(this).apply {
                isAllCaps = false
                text = (if (isCurrent) "● " else "") + product.optString("name")
                setOnClickListener { switchProduct(liveProductId) }
            })
        }
        skuText.text = currentSkuSummary
    }

    /** 방송 시작 또는 재개 — 서버가 새 Live 생성·재개 자격 검증(이전 송출 확인)을 한다. */
    private fun start() {
        actionButton.isEnabled = false
        secondaryButton.isEnabled = false
        lifecycleScope.launch {
            val result = ApiClient.post("/api/broadcast/start")
            val live = if (result.status == 200) ApiClient.json(result) else null
            when {
                live != null -> {
                    mutationGeneration++ // 이전에 발사된 폴 응답이 이 결과를 덮어쓰지 못하게
                    detail = live
                    streamAuthorized = true
                    endRequested = false
                    if (connectionState == "ERROR") {
                        // 명시적 재개 — 실패한 세션은 재사용을 보증할 수 없으므로 새로 만든다.
                        connectionState = null
                        recreateSession()
                    }
                    render()
                    startPolling()
                }
                result.status == 401 -> handleSessionExpired()
                else -> {
                    actionButton.isEnabled = true
                    secondaryButton.isEnabled = true
                    Toast.makeText(
                        this@BroadcastActivity,
                        ApiClient.errorMessage(result, "방송을 시작할 수 없습니다."),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun startStreamingIfNeeded(live: JSONObject) {
        val ingest = live.optString("ingestEndpoint", "")
        val streamKey = live.optString("streamKey", "")
        // 화면 갱신·폴링 때마다 불리므로 매퍼 규칙으로 재시작 여부를 판단한다
        // (중복 start 방지 + 조회만으로 자동 송출 방지 + 실패 후 자동 재송출 방지).
        if (!BroadcastUi.shouldStartStreaming(
                live.optString("status"), ingest.isNotEmpty() && streamKey.isNotEmpty(),
                streamAuthorized, sessionStarted, endRequested, failed = connectionState == "ERROR",
            )
        ) {
            return
        }
        try {
            session?.start(ingest, streamKey)
            sessionStarted = true
        } catch (e: BroadcastException) {
            // 동기 실패도 실패 상태로 기록해 폴링이 자동으로 재시도하지 않게 한다.
            sessionStarted = false
            connectionState = "ERROR"
            updateStatusText()
            Toast.makeText(this, "송출 시작 실패: ${e.detail} — '송출 재개'로 다시 시도하세요.", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * SDK CONNECTED 이후 서버에 방송 중 확정을 요청한다.
     * IVS 감지 지연·통신 오류에 대비해 제한된 백오프로 재시도하며,
     * 연결 해제·종료·성공·시간 초과 시 중단한다. 최종 실패 시 사용자에게 안내한다.
     */
    private fun confirmLive() {
        val id = liveId() ?: return
        if (!BroadcastUi.shouldConfirm(detail?.optString("status"), connected = true, endRequested = endRequested)) return
        if (confirmJob?.isActive == true) return
        confirmJob = lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
            var delayMs = 2000L
            while (System.currentTimeMillis() < deadline) {
                val result = ApiClient.post("/api/broadcast/lives/$id/confirm")
                if (result.status == 200) {
                    needsConfirm = false
                    ApiClient.json(result)?.let {
                        mutationGeneration++
                        detail = it
                        render()
                    }
                    return@launch
                }
                if (result.status == 401) {
                    handleSessionExpired()
                    return@launch
                }
                if (!BroadcastUi.confirmShouldRetry(result.status, connectionState == "CONNECTED")) return@launch
                delay(delayMs)
                delayMs = BroadcastUi.nextConfirmDelay(delayMs)
            }
            if (detail?.optString("status") == "STARTING") {
                Toast.makeText(
                    this@BroadcastActivity,
                    "방송 확정에 실패했습니다. '시작 취소' 후 다시 시작하세요.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun end(exitAfter: Boolean = false) {
        val id = liveId() ?: return
        actionButton.isEnabled = false
        secondaryButton.isEnabled = false
        endRequested = true // 이후 어떤 갱신도 자동 재송출하지 않는다 (종료 의도 보존)
        confirmJob?.cancel()
        session?.stop()
        sessionStarted = false
        lifecycleScope.launch {
            val result = ApiClient.post("/api/broadcast/lives/$id/end")
            when {
                result.status == 200 -> {
                    Toast.makeText(this@BroadcastActivity, "방송이 종료되었습니다.", Toast.LENGTH_SHORT).show()
                    if (exitAfter) {
                        finish()
                        return@launch
                    }
                    // 이 화면이 곧 홈이다 — 다음 방송을 시작할 수 있는 대기 상태로 돌아간다.
                    mutationGeneration++
                    detail = null
                    endRequested = false
                    streamAuthorized = false
                    connectionState = null
                    render()
                }
                result.status == 401 -> handleSessionExpired()
                else -> {
                    actionButton.isEnabled = true
                    secondaryButton.isEnabled = true
                    Toast.makeText(
                        this@BroadcastActivity,
                        ApiClient.errorMessage(result, "종료에 실패했습니다. 잠시 후 다시 시도하세요."),
                        Toast.LENGTH_LONG,
                    ).show()
                    // 응답 유실 등으로 이미 종료됐을 수 있다 — 실제 상태를 다시 조회해 화면을 맞춘다.
                    refresh()
                }
            }
        }
    }

    private fun switchProduct(liveProductId: Long) {
        val id = liveId() ?: return
        // 진행 중이면 마지막 선택을 보존했다가 완료 후 이어서 전환한다 (순서 역전 방지 + 의도 보존).
        if (switching) {
            pendingSwitchId = liveProductId
            return
        }
        switching = true
        lifecycleScope.launch {
            var succeeded = false
            try {
                val result = ApiClient.put(
                    "/api/broadcast/lives/$id/current-product",
                    JSONObject().put("liveProductId", liveProductId),
                )
                val live = if (result.status == 200) ApiClient.json(result) else null
                if (live != null) {
                    mutationGeneration++
                    detail = live
                    render()
                    succeeded = true
                } else if (result.status == 401) {
                    handleSessionExpired()
                    return@launch
                } else {
                    Toast.makeText(
                        this@BroadcastActivity,
                        ApiClient.errorMessage(result, "상품을 전환하지 못했습니다."),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                switching = false
                // 진행 중 사용자가 마지막으로 고른 상품을 이어서 전환한다 (실패 시 같은 상품도 재시도).
                val next = BroadcastUi.nextSwitch(pendingSwitchId, liveProductId, succeeded)
                pendingSwitchId = null
                if (next != null) switchProduct(next)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        confirmJob?.cancel()
        pollJob?.cancel()
        session?.release()
        session = null
    }

    companion object {
        private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private const val CONFIRM_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 10_000L
    }
}
