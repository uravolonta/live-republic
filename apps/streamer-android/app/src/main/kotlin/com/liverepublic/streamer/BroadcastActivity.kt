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

    private lateinit var previewContainer: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var productBar: LinearLayout
    private lateinit var skuText: TextView
    private lateinit var actionButton: Button

    private val sessionListener = object : BroadcastSession.Listener() {
        override fun onStateChanged(state: BroadcastSession.State) {
            runOnUiThread {
                if (state == BroadcastSession.State.CONNECTED) {
                    statusText.text = "● 방송중 (송출 연결됨)"
                }
            }
        }

        override fun onError(exception: BroadcastException) {
            runOnUiThread {
                Toast.makeText(this@BroadcastActivity, "송출 오류: ${exception.detail}", Toast.LENGTH_LONG).show()
            }
        }
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
        // 720p/30fps 세로 송출 (2026-08-24 결정: BASIC + 720p)
        val broadcastSession = BroadcastSession(
            this, sessionListener,
            Presets.Configuration.STANDARD_PORTRAIT,
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
        statusText.text = when (status) {
            "LIVE" -> "● 방송중 — ${live.getString("title")}"
            "SCHEDULED" -> "방송 준비 — ${live.getString("title")}"
            else -> "${live.getString("title")} ($status)"
        }

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
        when (status) {
            "SCHEDULED" -> {
                actionButton.text = "방송 시작"
                actionButton.setOnClickListener { start() }
                actionButton.isEnabled = true
            }
            "LIVE" -> {
                actionButton.text = "방송 종료"
                actionButton.setOnClickListener { end() }
                actionButton.isEnabled = true
                // 앱 재시작 등으로 송출이 끊겼다면 재개한다.
                startStreamingIfNeeded(live)
            }
            else -> {
                actionButton.text = "종료된 방송입니다"
                actionButton.isEnabled = false
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
        if (ingest.isEmpty() || streamKey.isEmpty()) return
        try {
            session?.start(ingest, streamKey)
        } catch (e: BroadcastException) {
            Toast.makeText(this, "송출 시작 실패: ${e.detail}", Toast.LENGTH_LONG).show()
        }
    }

    private fun end() {
        actionButton.isEnabled = false
        session?.stop()
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
        session?.release()
        session = null
    }

    companion object {
        private val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
