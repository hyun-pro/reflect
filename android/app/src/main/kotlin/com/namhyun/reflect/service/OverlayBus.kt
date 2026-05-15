package com.namhyun.reflect.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AccessibilityService 와 OverlayService 사이 양방향 시그널.
 *
 * 흐름:
 *  1. Accessibility 가 답장창 열림 감지 → setActive(input) → OverlayService 가 collect 해서 버블 표시
 *  2. 사용자가 추천 탭 → requestCommit(text) → AccessibilityService 가 focused EditText 에 채움
 *  3. 답장창 닫힘 → setActive(null) → OverlayService 가 버블 숨김
 *
 * SharedFlow(replay=0) 라 늦게 collect 한 쪽이 과거 commit 을 다시 받는 일은 없음.
 */
object OverlayBus {

    data class ActiveInput(
        val packageName: String,
        val appKey: String,
        val contact: String?,
        val incoming: String?,
        val suggestions: List<String>,
        val originSourceId: Long, // inbox entity id, 매칭 안 됐으면 0
    )

    private val _active = MutableStateFlow<ActiveInput?>(null)
    val active: StateFlow<ActiveInput?> = _active.asStateFlow()

    private val _commitRequests = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 8)
    val commitRequests: SharedFlow<String> = _commitRequests.asSharedFlow()

    private val _commitResults = MutableSharedFlow<Boolean>(replay = 0, extraBufferCapacity = 8)
    val commitResults: SharedFlow<Boolean> = _commitResults.asSharedFlow()

    fun setActive(input: ActiveInput?) {
        _active.value = input
    }

    fun clearActive() {
        _active.value = null
    }

    suspend fun requestCommit(text: String) {
        _commitRequests.emit(text)
    }

    suspend fun reportCommitResult(success: Boolean) {
        _commitResults.emit(success)
    }
}
