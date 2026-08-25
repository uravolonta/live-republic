package com.liverepublic.streamer

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 임시 비밀번호로 로그인한 Streamer의 최초 비밀번호 변경 (변경 전에는 다른 기능 사용 불가). */
class ChangePasswordActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val currentInput = EditText(this).apply {
            hint = "현재(임시) 비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val newInput = EditText(this).apply {
            hint = "새 비밀번호 (영문·숫자·특수문자 8자 이상)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val submit = Button(this).apply { text = "비밀번호 변경" }

        submit.setOnClickListener {
            submit.isEnabled = false
            lifecycleScope.launch {
                val result = ApiClient.post(
                    "/api/auth/password",
                    JSONObject()
                        .put("currentPassword", currentInput.text.toString())
                        .put("newPassword", newInput.text.toString()),
                )
                submit.isEnabled = true
                when (result.status) {
                    200 -> {
                        Toast.makeText(this@ChangePasswordActivity, "변경되었습니다.", Toast.LENGTH_SHORT).show()
                        // 백스택의 기존 LiveList 위에 새 인스턴스가 쌓이지 않게 정리한다.
                        startActivity(
                            Intent(this@ChangePasswordActivity, LiveListActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                        )
                        finish()
                    }
                    0 -> toast("서버에 연결할 수 없습니다.")
                    else -> toast(ApiClient.errorMessage(result, "변경에 실패했습니다. 입력값을 확인하세요."))
                }
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
                addView(TextView(context).apply {
                    text = "비밀번호를 변경해야 방송을 시작할 수 있습니다"
                    gravity = Gravity.CENTER
                })
                addView(currentInput)
                addView(newInput)
                addView(submit)
            },
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
