package com.liverepublic.streamer

import android.Manifest
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
 * 방송 화면: 카메라 미리보기 → 실제 IVS 송출 시작·종료,
 * 송출 화면 위 Layer에서 현재 판매 상품 전환과 Option별 재고 확인. (Issue #5)
 */
class BroadcastActivity : AppCompatActivity() {

    private var liveId: Long = 0
    private var session: BroadcastSession? = null
    private var detail: JSONObject? = null
    /** session.start()를 호출했고 stop/release하지 않은 상태 — 자동 재연결 중에도 유지된다. */
    private var sessionStarted = false
    private var connectionState: String? = null
    private var confirmJob: Job? = null
    private var pollJob: Job? = null
    /** 사용자가 종료를 요청함 — 이후에는 어떤 화면 갱신도 자동 재송출하지 않는다. */
    private var endRequested = false
    private var broadcastToken: String? = null
    private var pendingSwitchId: Long? = null
    private var switching = false

    private lateinit var previewContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var productBar: LinearLayout
    private lateinit var skuText: TextView
    private lateinit var actionButton: Button

    private val sessionListener = object : BroadcastSession.Listener() {
        override fun onStateChanged(state: BroadcastSession.State) {
            runOnUiThread {
                connectionState = state.name
                when (state) {
                    BroadcastSession.State.CONNECTED ->
                        // 실제 연결이 확인된 뒤에만 서버가 방송 중으로 확정한다.
                        // (재연결 시에도 호출되어 새 Stream Session이 이력에 기록된다.)
                        confirmLive()
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
                    // 잘못된 자격 등 회복 불가 오류는 RetryState.FAILURE 없이 세션이 죽을 수
                    // 있다 — FAILURE와 동일하게 처리해 수동 재개만 허용한다.
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
                        Toast.makeText(
                            this@BroadcastActivity,
                            "자동 재연결에 실패했습니다. 화면을 다시 열어 송출을 재개하거나 방송을 종료하세요.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateStatusText() {
        val live = detail ?: return
        statusText.text = BroadcastUi.statusLabel(
            live.optString("status"), live.optString("title"), connectionState,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveId = intent.getLongExtra("liveId", 0)
        broadcastToken = BroadcastLease.get(this, liveId)

        // 방송 중 화면이 꺼지지 않게 유지한다 (정책: 방송은 이 화면에서만 송출).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 방송·시작 중에는 실수로 뒤로 가기를 눌러 송출이 끊기지 않도록 확인을 받는다.
        onBackPressedDispatcher.addCallback(this) {
            val status = detail?.optString("status")
            // 서버 종료 없이 나가는 선택지는 제공하지 않는다 — 나가려면 방송을 함께 종료한다.
            if (status == "LIVE" || status == "STARTING") {
                android.app.AlertDialog.Builder(this@BroadcastActivity)
                    .setMessage("방송 중입니다. 나가려면 방송을 종료해야 합니다.")
                    .setPositiveButton("방송 종료 후 나가기") { _, _ -> end() }
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

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            addView(skuText)
            addView(HorizontalScrollView(context).apply { addView(productBar) })
            addView(actionButton)
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
            Toast.makeText(this, "카메라·마이크 권한이 필요합니다.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** 카메라 미리보기를 붙이고 Live 상태를 불러온다. */
    private fun setUp() {
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
        refresh()
        startPolling()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val result = ApiClient.get("/api/broadcast/lives/$liveId", leaseHeaders())
            val live = if (result.status == 200) ApiClient.json(result) else null
            when {
                live != null -> {
                    detail = live
                    applyServerState()
                    render()
                }
                result.status == 401 || result.status == 403 || result.status == 404 -> {
                    Toast.makeText(
                        this@BroadcastActivity,
                        ApiClient.errorMessage(result, "Live를 불러오지 못했습니다."),
                        Toast.LENGTH_LONG,
                    ).show()
                    finish()
                }
                else -> {
                    // 일시적 실패(오프라인·서버 오류·손상 응답) — 화면을 닫으면 종료 재시도
                    // 버튼까지 잃는다. 재시도 버튼을 남긴다.
                    statusText.text = "Live 정보를 불러오지 못했습니다 — 연결을 확인하세요"
                    actionButton.text = "다시 불러오기"
                    actionButton.isEnabled = true
                    actionButton.setOnClickListener { refresh() }
                }
            }
        }
    }

    /**
     * 방송 중 주기 폴링 — 재고 변화를 실시간으로 보여주고(PRD §7),
     * 임대 상실(단말 교체)·Owner 강제 종료 같은 서버 측 변화를 단말이 감지한다.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                if (isFinishing) return@launch
                val status = detail?.optString("status")
                if (status == "ENDED" || status == "CANCELLED") return@launch
                val result = ApiClient.get("/api/broadcast/lives/$liveId", leaseHeaders())
                if (result.status == 200) {
                    ApiClient.json(result)?.let {
                        detail = it
                        applyServerState()
                        render()
                    }
                }
                // 폴링 실패는 조용히 넘긴다 — 다음 주기에 다시 시도한다.
            }
        }
    }

    /** 서버 상태와 단말 송출을 동기화 — 임대 상실·종료를 감지하면 송출을 멈춘다. */
    private fun applyServerState() {
        val live = detail ?: return
        val status = live.optString("status")
        if (status == "ENDED" || status == "CANCELLED") {
            // 끝난 Live의 임대 토큰은 더 이상 쓸 수 없다 — 프리퍼런스 누적을 막는다.
            BroadcastLease.clear(this, liveId)
            broadcastToken = null
        }
        val active = status == "STARTING" || status == "LIVE"
        if (sessionStarted && !endRequested && (!active || !live.optBoolean("canControl"))) {
            confirmJob?.cancel()
            session?.stop()
            sessionStarted = false
            Toast.makeText(
                this,
                if (active) "다른 단말이 방송을 이어받아 이 단말의 송출을 중단합니다."
                else "방송이 종료되어 송출을 중단합니다.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun render() {
        val live = detail ?: return
        val status = live.optString("status")
        updateStatusText()

        // 상품 전환 Layer
        productBar.removeAllViews()
        val products = live.optJSONArray("products") ?: org.json.JSONArray()
        val currentId = if (live.isNull("currentLiveProductId")) null else live.optLong("currentLiveProductId")
        var currentSkuSummary = "현재 판매 상품이 없습니다"
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

        // 시작/종료 버튼 — 서버가 내려준 capability로 이 단말이 할 수 있는 일만 노출한다.
        val canControl = live.optBoolean("canControl")
        val canBroadcast = live.optBoolean("canBroadcast")
        val canForceEnd = live.optBoolean("canForceEnd")
        val (label, enabled) = BroadcastUi.action(status, canControl, canBroadcast, canForceEnd, endRequested)
        actionButton.text = label
        actionButton.isEnabled = enabled
        when {
            status == "SCHEDULED" -> actionButton.setOnClickListener { start() }
            status == "STARTING" || status == "LIVE" -> {
                if (canControl || endRequested || canForceEnd) {
                    actionButton.setOnClickListener { end() }
                } else if (canBroadcast) {
                    // 시작 계정의 다른(또는 재설치된) 단말 — start 재호출로 임대를 갱신해 재개한다.
                    actionButton.setOnClickListener { start() }
                }
                // 임대를 보유한 단말만 송출·확정을 진행한다.
                if (canControl) {
                    startStreamingIfNeeded(live)
                    if (connectionState == "CONNECTED") confirmLive()
                }
            }
        }
        // 임대가 없는 단말에는 상품 전환 버튼을 비활성화한다 (서버도 403으로 차단).
        if (!canControl) {
            for (i in 0 until productBar.childCount) productBar.getChildAt(i).isEnabled = false
        }
    }

    private fun leaseHeaders(): Map<String, String> =
        broadcastToken?.let { mapOf(BroadcastLease.HEADER to it) } ?: emptyMap()

    private fun start() {
        actionButton.isEnabled = false
        lifecycleScope.launch {
            val result = ApiClient.post("/api/broadcast/lives/$liveId/start")
            val live = if (result.status == 200) ApiClient.json(result) else null
            if (live != null) {
                detail = live
                // 송출 임대 토큰 — 이 단말만 Stream Key·조작 권한을 가진다.
                live.optString("broadcastToken", "").takeIf { it.isNotEmpty() }?.let { token ->
                    broadcastToken = token
                    BroadcastLease.save(this@BroadcastActivity, liveId, token)
                }
                // 명시적 재개이므로 이전 실패 상태를 지워 송출 시작을 허용한다.
                if (connectionState == "ERROR") connectionState = null
                render()
            } else {
                actionButton.isEnabled = true
                Toast.makeText(
                    this@BroadcastActivity,
                    ApiClient.errorMessage(result, "방송을 시작할 수 없습니다."),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun startStreamingIfNeeded(live: JSONObject) {
        val ingest = live.optString("ingestEndpoint", "")
        val streamKey = live.optString("streamKey", "")
        // 화면 갱신·폴링 때마다 불리므로 매퍼 규칙으로 재시작 여부를 판단한다
        // (중복 start 방지 + 실패 후 자동 재송출 방지).
        if (!BroadcastUi.shouldStartStreaming(
                live.optString("status"), ingest.isNotEmpty() && streamKey.isNotEmpty(),
                sessionStarted, endRequested, failed = connectionState == "ERROR",
            )
        ) {
            return
        }
        try {
            session?.start(ingest, streamKey)
            sessionStarted = true
        } catch (e: BroadcastException) {
            Toast.makeText(this, "송출 시작 실패: ${e.detail}", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * SDK CONNECTED 이후 서버에 방송 중 확정을 요청한다.
     * IVS 감지 지연·통신 오류에 대비해 제한된 백오프로 재시도하며,
     * 연결 해제·종료·성공·시간 초과 시 중단한다. 최종 실패 시 사용자에게 안내한다.
     */
    private fun confirmLive() {
        val status = detail?.optString("status") ?: return
        val canControl = detail?.optBoolean("canControl") ?: false
        if (!BroadcastUi.shouldConfirm(status, connected = true, canControl = canControl, endRequested = endRequested)) return
        if (confirmJob?.isActive == true) return
        confirmJob = lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
            var delayMs = 2000L
            while (System.currentTimeMillis() < deadline) {
                val result = ApiClient.post("/api/broadcast/lives/$liveId/confirm", headers = leaseHeaders())
                if (result.status == 200) {
                    ApiClient.json(result)?.let {
                        detail = it
                        render()
                    }
                    return@launch
                }
                if (!BroadcastUi.confirmShouldRetry(result.status, connectionState == "CONNECTED")) return@launch
                delay(delayMs)
                delayMs = BroadcastUi.nextConfirmDelay(delayMs)
            }
            if (detail?.optString("status") == "STARTING") {
                Toast.makeText(
                    this@BroadcastActivity,
                    "방송 확정에 실패했습니다. 화면을 다시 열어 재시도하거나 '시작 취소' 후 다시 시작하세요.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun end() {
        actionButton.isEnabled = false
        endRequested = true // 이후 어떤 갱신도 자동 재송출하지 않는다 (종료 의도 보존)
        confirmJob?.cancel()
        session?.stop()
        sessionStarted = false
        lifecycleScope.launch {
            val result = ApiClient.post("/api/broadcast/lives/$liveId/end", headers = leaseHeaders())
            if (result.status == 200) {
                BroadcastLease.clear(this@BroadcastActivity, liveId)
                Toast.makeText(this@BroadcastActivity, "방송이 종료되었습니다.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                actionButton.isEnabled = true
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

    private fun switchProduct(liveProductId: Long) {
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
                    "/api/broadcast/lives/$liveId/current-product",
                    JSONObject().put("liveProductId", liveProductId),
                    headers = leaseHeaders(),
                )
                val live = if (result.status == 200) ApiClient.json(result) else null
                if (live != null) {
                    detail = live
                    render()
                    succeeded = true
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
