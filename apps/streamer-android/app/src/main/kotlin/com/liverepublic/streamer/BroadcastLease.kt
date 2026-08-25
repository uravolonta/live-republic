package com.liverepublic.streamer

import android.content.Context

/**
 * 송출 임대 토큰 보관 — 앱 전용 저장소(샌드박스·기기 암호화 대상)에 Live별로 저장한다.
 * 서버는 해시만 보관하므로 이 토큰이 곧 "방송 단말"의 증명이다.
 */
object BroadcastLease {
    private const val PREFS = "broadcast_lease"
    const val HEADER = "X-Broadcast-Token"

    fun save(context: Context, liveId: Long, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(liveId), token).apply()
    }

    fun get(context: Context, liveId: Long): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(liveId), null)

    fun clear(context: Context, liveId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(key(liveId)).apply()
    }

    fun headers(context: Context, liveId: Long): Map<String, String> =
        get(context, liveId)?.let { mapOf(HEADER to it) } ?: emptyMap()

    private fun key(liveId: Long) = "token_$liveId"
}
