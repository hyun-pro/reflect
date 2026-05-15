package com.namhyun.reflect.service

import android.content.Context

/**
 * 사용자가 명시적으로 끈 inbox 추적. 같은 inbox id 면 버블 안 띄움.
 * 새 메시지(새 inbox id)가 오면 자동 활성화.
 *
 * 추가로 "오늘 그만" 같은 시간 기반 dismiss 도 지원 (분 단위).
 */
object OverlayDismissPrefs {
    private const val PREFS = "reflect_overlay_dismiss"
    private const val KEY_INBOX_ID = "dismissed_inbox_id"
    private const val KEY_UNTIL_MS = "dismissed_until_ms"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dismissForInbox(context: Context, inboxId: Long, durationMs: Long = 0L) {
        prefs(context).edit()
            .putLong(KEY_INBOX_ID, inboxId)
            .putLong(
                KEY_UNTIL_MS,
                if (durationMs > 0) System.currentTimeMillis() + durationMs else 0L,
            )
            .apply()
    }

    fun isDismissed(context: Context, inboxId: Long): Boolean {
        val p = prefs(context)
        val savedId = p.getLong(KEY_INBOX_ID, -1L)
        val until = p.getLong(KEY_UNTIL_MS, 0L)
        val now = System.currentTimeMillis()
        // 시간 기반 dismiss 가 살아있으면 inbox 와 무관하게 막음
        if (until > 0 && now < until) return true
        // 같은 inbox 면 막음
        return savedId == inboxId && inboxId != 0L
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
