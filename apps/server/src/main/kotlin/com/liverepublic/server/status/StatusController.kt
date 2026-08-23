package com.liverepublic.server.status

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Preview에서 Server가 살아있는지 확인하기 위한 상태 응답.
 * 도메인 기능은 이후 Issue에서 추가한다.
 */
@RestController
@RequestMapping("/api/status")
class StatusController {

    @GetMapping
    fun status(): StatusResponse = StatusResponse(service = "live-republic-server", status = "ok")
}

data class StatusResponse(
    val service: String,
    val status: String,
)
