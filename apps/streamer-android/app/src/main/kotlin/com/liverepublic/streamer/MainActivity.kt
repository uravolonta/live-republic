package com.liverepublic.streamer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Streamer App 골격. 로그인, Live 선택과 IVS 송출은 Issue #5에서 구현한다.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "Live Republic — Streamer App 골격"
                gravity = Gravity.CENTER
            },
        )
    }
}
