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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.amazonaws.ivs.broadcast.BroadcastException
import com.amazonaws.ivs.broadcast.BroadcastSession
import com.amazonaws.ivs.broadcast.Presets
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
    private var streaming = false
    private var connectionState: String? = null
    private var confirmJob: kotlinx.coroutines.Job? = null

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
                    BroadcastSession.State.DISCONNECTED,
                    BroadcastSession.State.ERROR,
                    -> streaming = false // 실제 연결 해제 후에만 재시작을 허용한다.
                    else -> Unit
                }
                updateStatusText()
            }
        }

        override fun onError(exception: BroadcastException) {
            runOnUiThread {
                Toast.makeText(this@BroadcastActivity, "송출 오류: ${exception.detail}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateStatusText() {
        val live = detail ?: return
        statusText.text = BroadcastUi.statusLabel(live.getString("status"), live.getString("title"), connectionState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        liveId = intent.getLongExtra("liveId", 0)

        previewContainer = FrameLayout(this)
        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(24, 12, 24, 12)
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
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(overlay) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
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
        // 720p/30fps 세로 송출 (2026-08-24 결정: BASIC + 720p) + 일시적 네트워크 단절 자동 재연결
        val config = Presets.Configuration.STANDARD_PORTRAIT
        config.autoReconnect.setEnabled(true)
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
    }

    private fun refresh() {
        lifecycleScope.launch {
            val result = ApiClient.get("/api/broadcast/lives/$liveId")
            if (result.status != 200) {
                Toast.makeText(
                    this@BroadcastActivity,
                    ApiClient.errorMessage(result, "Live를 불러오지 못했습니다."),
                    Toast.LENGTH_LONG,
                ).show()
                finish()
                return@launch
            }
            detail = ApiClient.json(result)
            render()
        }
    }

    private fun render() {
        val live = detail ?: return
        val status = live.getString("status")
        updateStatusText()

        // 상품 전환 Layer
        productBar.removeAllViews()
        val products = live.getJSONArray("products")
        val currentId = if (live.isNull("currentLiveProductId")) null else live.getLong("currentLiveProductId")
        var currentSkuSummary = "현재 판매 상품이 없습니다"
        for (i in 0 until products.length()) {
            val product = products.getJSONObject(i)
            val liveProductId = product.getLong("liveProductId")
            val isCurrent = liveProductId == currentId
            if (isCurrent) {
                val skus = product.getJSONArray("skus")
                currentSkuSummary = buildString {
                    append("재고: ")
                    for (s in 0 until skus.length()) {
                        val sku = skus.getJSONObject(s)
                        if (s > 0) append(" · ")
                        append("${sku.getString("optionLabel")} ${sku.getInt("available")}")
                    }
                }
            }
            productBar.addView(Button(this).apply {
                isAllCaps = false
                text = (if (isCurrent) "● " else "") + product.getString("name")
                setOnClickListener { switchProduct(liveProductId) }
            })
        }
        skuText.text = currentSkuSummary

        // 시작/종료 버튼
        val (label, enabled) = BroadcastUi.action(status)
        actionButton.text = label
        actionButton.isEnabled = enabled
        when (status) {
            "SCHEDULED" -> actionButton.setOnClickListener { start() }
            "STARTING", "LIVE" -> {
                actionButton.setOnClickListener { end() }
                // 앱 재실행·목록 재진입 시 송출을 재개하고, 연결돼 있으면 확정을 재시도한다.
                startStreamingIfNeeded(live)
                if (connectionState == "CONNECTED") confirmLive()
            }
        }
    }

    private fun start() {
        actionButton.isEnabled = false
        lifecycleScope.launch {
            val result = ApiClient.post("/api/broadcast/lives/$liveId/start")
            if (result.status == 200) {
                detail = ApiClient.json(result)
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
        // 화면 갱신 때마다 불리므로 매퍼 규칙으로 재시작 여부를 판단한다 (중복 start 방지).
        if (!BroadcastUi.shouldStartStreaming(live.getString("status"), ingest.isNotEmpty() && streamKey.isNotEmpty(), streaming)) {
            return
        }
        try {
            session?.start(ingest, streamKey)
            streaming = true
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
        if (!BroadcastUi.shouldConfirm(status, connected = true)) return
        if (confirmJob?.isActive == true) return
        confirmJob = lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
            var delayMs = 2000L
            while (System.currentTimeMillis() < deadline) {
                val result = ApiClient.post("/api/broadcast/lives/$liveId/confirm")
                if (result.status == 200) {
                    detail = ApiClient.json(result)
                    render()
                    return@launch
                }
                // SDK 연결이 끊겼거나 화면 상태가 바뀌면 중단한다.
                if (connectionState != "CONNECTED") return@launch
                if (result.status != 409 && result.status != 0 && result.status < 500) return@launch
                kotlinx.coroutines.delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(10_000L)
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
        confirmJob?.cancel()
        session?.stop()
        streaming = false
        lifecycleScope.launch {
            val result = ApiClient.post("/api/broadcast/lives/$liveId/end")
            if (result.status == 200) {
                Toast.makeText(this@BroadcastActivity, "방송이 종료되었습니다.", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                actionButton.isEnabled = true
                Toast.makeText(
                    this@BroadcastActivity,
                    ApiClient.errorMessage(result, "종료에 실패했습니다."),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun switchProduct(liveProductId: Long) {
        lifecycleScope.launch {
            val result = ApiClient.put(
                "/api/broadcast/lives/$liveId/current-product",
                JSONObject().put("liveProductId", liveProductId),
            )
            if (result.status == 200) {
                detail = ApiClient.json(result)
                render()
            } else {
                Toast.makeText(
                    this@BroadcastActivity,
                    ApiClient.errorMessage(result, "상품을 전환하지 못했습니다."),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        confirmJob?.cancel()
        session?.release()
        session = null
    }

    companion object {
        private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        private const val CONFIRM_TIMEOUT_MS = 60_000L
    }
}
