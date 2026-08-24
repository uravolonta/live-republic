package com.liverepublic.streamer

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** 방송 가능한 Live 목록 (방송 중 우선, 그다음 예정) + 게릴라 Live 생성. */
class LiveListActivity : AppCompatActivity() {

    private lateinit var listContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val guerrillaButton = Button(this).apply {
            text = "+ 게릴라 Live 바로 시작"
            setOnClickListener {
                startActivity(Intent(this@LiveListActivity, GuerrillaActivity::class.java))
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(context).apply {
                    text = "방송할 Live 선택"
                    textSize = 20f
                })
                addView(guerrillaButton)
                addView(ScrollView(context).apply { addView(listContainer) })
            },
        )
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val result = ApiClient.get("/api/broadcast/lives")
            when (result.status) {
                200 -> render(result)
                401 -> {
                    startActivity(Intent(this@LiveListActivity, LoginActivity::class.java))
                    finish()
                }
                403 -> {
                    startActivity(Intent(this@LiveListActivity, ChangePasswordActivity::class.java))
                }
                else -> Toast.makeText(
                    this@LiveListActivity,
                    ApiClient.errorMessage(result, "Live 목록을 불러오지 못했습니다."),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun render(result: ApiResult) {
        listContainer.removeAllViews()
        val lives = ApiClient.jsonArray(result)
        if (lives.length() == 0) {
            listContainer.addView(TextView(this).apply {
                text = "예정된 Live가 없습니다. 게릴라 Live로 바로 시작할 수 있습니다."
                gravity = Gravity.CENTER
                setPadding(0, 48, 0, 0)
            })
            return
        }
        for (i in 0 until lives.length()) {
            val live = lives.getJSONObject(i)
            val status = live.getString("status")
            val label = buildString {
                append(live.getString("title"))
                if (status == "LIVE") append("  ● 방송중")
                append("\n상품 ${live.getInt("productCount")}개 · ${live.getString("scheduledStartAt").take(16)}")
            }
            listContainer.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener {
                    startActivity(
                        Intent(this@LiveListActivity, BroadcastActivity::class.java)
                            .putExtra("liveId", live.getLong("id")),
                    )
                }
            })
        }
    }
}
