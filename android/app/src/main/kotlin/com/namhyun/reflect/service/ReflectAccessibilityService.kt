package com.namhyun.reflect.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.IngestRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 카톡/인스타 등에서 사용자가 직접 입력+전송한 메시지를 자동 캐치 → /api/ingest.
 *
 * 동작 휴리스틱:
 * 1. TYPE_VIEW_TEXT_CHANGED — 입력창 텍스트 변화. 마지막 비공백 텍스트를 lastInput 에 보관.
 * 2. TYPE_VIEW_CLICKED — content-description 이 "전송" 또는 "send" 포함하면 lastInput 을 발송된 것으로 간주 → ingest.
 * 3. 또는 입력창이 다음 텍스트 변화로 비워졌을 때도 발송 추정 (보조 휴리스틱).
 *
 * 권한 무거우므로 사용자 명시 옵트인 필요. 시스템이 가끔 자동 OFF 시킴.
 */
class ReflectAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w(TAG, "scope", e) }
    )

    @Volatile private var lastInput: String = ""
    @Volatile private var lastInputAt: Long = 0L

    private val targetPackages = setOf(
        "com.kakao.talk",
        "com.instagram.android",
        "com.facebook.orca",
        "com.naver.line",
        "org.telegram.messenger",
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { handle(event) }.onFailure { Log.w(TAG, "event", it) }
    }

    private fun handle(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in targetPackages) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString(" ")?.trim().orEmpty()
                if (text.isNotEmpty() && text.length < 500) {
                    lastInput = text
                    lastInputAt = System.currentTimeMillis()
                } else if (text.isEmpty() && lastInput.isNotBlank()) {
                    // 입력창 비워짐 = 전송 추정 (또는 사용자가 지운 것)
                    val captured = lastInput
                    val age = System.currentTimeMillis() - lastInputAt
                    if (age in 100..30_000) {
                        // 0.1~30초 사이에 비워진 거면 전송으로 간주
                        ingest(pkg, captured)
                    }
                    lastInput = ""
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val desc = event.contentDescription?.toString().orEmpty()
                if ((desc.contains("전송") || desc.contains("send", ignoreCase = true)) &&
                    lastInput.isNotBlank()
                ) {
                    ingest(pkg, lastInput)
                    lastInput = ""
                }
            }
        }
    }

    private fun ingest(pkg: String, text: String) {
        if (text.length < 2 || text.length > 500) return
        scope.launch {
            runCatching {
                BackendApi.ingest(
                    IngestRequest(
                        app = TargetApps.appKey(pkg),
                        contact = null,
                        incoming_message = "(접근성 자동 학습)",
                        my_reply = text,
                    )
                )
                Log.i(TAG, "ingested: ${text.take(40)}...")
            }
        }
    }

    override fun onInterrupt() {}

    companion object { private const val TAG = "ReflectA11y" }
}
