package com.liverepublic.streamer

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 게릴라 Live: 제목과 판매 상품을 골라 즉시 만들고 방송 화면으로 이동한다. */
class GuerrillaActivity : AppCompatActivity() {

    private val checks = mutableListOf<Pair<Long, CheckBox>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val titleInput = EditText(this).apply { hint = "방송 제목" }
        val productContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val createButton = Button(this).apply { text = "만들고 방송 준비" }

        createButton.setOnClickListener {
            val title = titleInput.text.toString().trim()
            val selected = checks.filter { it.second.isChecked }.map { it.first }
            if (title.isEmpty()) return@setOnClickListener toast("제목을 입력하세요.")
            if (selected.isEmpty()) return@setOnClickListener toast("판매 상품을 1개 이상 선택하세요.")
            createButton.isEnabled = false
            lifecycleScope.launch {
                val result = ApiClient.post(
                    "/api/broadcast/lives",
                    JSONObject().put("title", title).put("productIds", JSONArray(selected)),
                )
                createButton.isEnabled = true
                if (result.status == 201) {
                    val live = ApiClient.json(result)
                    startActivity(
                        Intent(this@GuerrillaActivity, BroadcastActivity::class.java)
                            .putExtra("liveId", live.getLong("id")),
                    )
                    finish()
                } else {
                    toast(ApiClient.errorMessage(result, "생성에 실패했습니다."))
                }
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(context).apply {
                    text = "게릴라 Live"
                    textSize = 20f
                })
                addView(titleInput)
                addView(TextView(context).apply { text = "판매 상품 선택" })
                addView(ScrollView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
                    )
                    addView(productContainer)
                })
                addView(createButton)
            },
        )

        lifecycleScope.launch {
            val result = ApiClient.get("/api/broadcast/products")
            if (result.status != 200) {
                toast(ApiClient.errorMessage(result, "상품을 불러오지 못했습니다."))
                return@launch
            }
            val products = ApiClient.jsonArray(result)
            for (i in 0 until products.length()) {
                val product = products.getJSONObject(i)
                val check = CheckBox(this@GuerrillaActivity).apply {
                    text = "${product.getString("name")} · ${product.getInt("price")}원"
                }
                checks += product.getLong("productId") to check
                productContainer.addView(check)
            }
            if (products.length() == 0) {
                productContainer.addView(TextView(this@GuerrillaActivity).apply {
                    text = "판매 중인 상품이 없습니다. Owner Web에서 먼저 등록하세요."
                })
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
