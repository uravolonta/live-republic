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

/** Shop 계정(Owner 이메일 또는 Streamer 로그인 ID)으로 로그인한다. */
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val idInput = EditText(this).apply { hint = "이메일 또는 로그인 ID" }
        val passwordInput = EditText(this).apply {
            hint = "비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val loginButton = Button(this).apply { text = "로그인" }

        loginButton.setOnClickListener {
            val loginId = idInput.text.toString().trim()
            val password = passwordInput.text.toString()
            if (loginId.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "로그인 정보를 입력하세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loginButton.isEnabled = false
            lifecycleScope.launch {
                val result = ApiClient.post(
                    "/api/auth/login",
                    JSONObject().put("email", loginId).put("password", password),
                )
                loginButton.isEnabled = true
                when {
                    result.status == 200 -> {
                        val me = ApiClient.json(result) ?: JSONObject()
                        if (me.optBoolean("mustChangePassword")) {
                            startActivity(Intent(this@LoginActivity, ChangePasswordActivity::class.java))
                        } else {
                            startActivity(Intent(this@LoginActivity, LiveListActivity::class.java))
                        }
                    }
                    result.status == 401 -> toast("로그인 정보가 올바르지 않습니다.")
                    result.status == 0 -> toast("서버에 연결할 수 없습니다.")
                    else -> toast(ApiClient.errorMessage(result, "로그인에 실패했습니다."))
                }
            }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(64, 64, 64, 64)
                addView(TextView(context).apply {
                    text = "Live Republic Streamer"
                    textSize = 22f
                    gravity = Gravity.CENTER
                })
                addView(idInput)
                addView(passwordInput)
                addView(loginButton)
            },
        )
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
