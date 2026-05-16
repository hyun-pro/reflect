package com.namhyun.reflect.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.FeedbackRejectRequest
import com.namhyun.reflect.api.IngestRequest
import com.namhyun.reflect.data.InboxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 두 가지 역할:
 *
 *  1. **자동 학습 캐치** (기존 동작): 사용자가 메신저에서 직접 친 답장을 ingest/DPO 로 학습.
 *     - TYPE_VIEW_TEXT_CHANGED: lastInput 갱신
 *     - TYPE_VIEW_CLICKED: 전송 버튼 추정 시 lastInput 발송된 것으로 간주
 *     - 입력창이 비워지면 발송 추정
 *
 *  2. **Floating overlay 트리거** (신규): 답장창 열림/닫힘 감지 + 추천 commit.
 *     - TYPE_WINDOW_STATE_CHANGED: 어느 메신저 어느 화면에 있는지 추적
 *     - TYPE_VIEW_FOCUSED on EditText: 답장창 활성 → OverlayBus.setActive
 *     - 메신저 떠나면 OverlayBus.clearActive + OverlayService 정리
 *     - OverlayBus.commitRequests collect → focused EditText 에 ACTION_SET_TEXT
 */
class ReflectAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e -> Log.w(TAG, "scope", e) }
    )

    // ── 학습 캐치용 상태 (기존)
    @Volatile private var lastInput: String = ""
    @Volatile private var lastInputAt: Long = 0L

    // ── 오버레이용 상태
    @Volatile private var currentPkg: String? = null
    @Volatile private var currentContact: String? = null
    @Volatile private var lastActivePostedAt: Long = 0L
    @Volatile private var inputFocused: Boolean = false
    private var commitJob: Job? = null

    private val targetPackages: Set<String> get() = TargetApps.PACKAGES

    override fun onServiceConnected() {
        super.onServiceConnected()
        commitJob?.cancel()
        commitJob = scope.launch {
            OverlayBus.commitRequests.collect { text ->
                val ok = commitToFocusedEditText(text)
                OverlayBus.reportCommitResult(ok)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching { handle(event) }.onFailure { Log.w(TAG, "event", it) }
    }

    private fun handle(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return

        // 메신저 떠나면 오버레이 정리. system_ui / keyboard / launcher 의 일시적
        // 이벤트(키보드 열림 등)는 false-positive 이므로 WINDOW_STATE_CHANGED 만 신뢰.
        if (pkg !in targetPackages) {
            val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            val isOwnApp = pkg == applicationContext.packageName
            val isTransient = pkg == "android" ||
                pkg.startsWith("com.android.systemui") ||
                pkg.contains("inputmethod") ||
                pkg.contains("ime")
            if (isStateChange && currentPkg != null && !isOwnApp && !isTransient) {
                onLeaveMessenger()
            }
            return
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                currentPkg = pkg
                // 같은 메신저라도 현재 화면이 채팅방인지(=입력창 EditText 있나) 검사.
                // 채팅방 목록·검색·설정 화면이면 EditText 없거나 검색창 1개뿐 → 오버레이 숨김.
                if (!screenHasReplyInput()) {
                    currentContact = null
                    OverlayBus.clearActive()
                    runCatching { ReflectOverlayService.stop(applicationContext) }
                    return
                }
                // 채팅방 진입 — contact 다시 추출
                val contact = extractContactName()
                currentContact = contact?.takeIf { it.isNotBlank() }
                refreshOverlayActive(pkg, currentContact)
            }
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                currentPkg = pkg
                val src = event.source
                val isEditable = src?.isEditable == true ||
                    src?.className?.toString()?.contains("EditText", ignoreCase = true) == true
                src?.recycle()
                if (isEditable) {
                    inputFocused = true
                    val contact = currentContact ?: extractContactName()
                    if (!contact.isNullOrBlank()) currentContact = contact
                    refreshOverlayActive(pkg, currentContact)
                } else if (inputFocused) {
                    // 입력창 포커스 잃음 — 오버레이 유지 (사용자가 추천 누를 수 있도록)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // contact 가 아직 없으면 한 번 더 시도
                if (currentContact.isNullOrBlank()) {
                    val contact = extractContactName()
                    if (!contact.isNullOrBlank()) {
                        currentContact = contact
                        refreshOverlayActive(pkg, currentContact)
                    }
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString(" ")?.trim().orEmpty()
                if (text.isNotEmpty() && text.length < 500) {
                    lastInput = text
                    lastInputAt = System.currentTimeMillis()
                } else if (text.isEmpty() && lastInput.isNotBlank()) {
                    val captured = lastInput
                    val age = System.currentTimeMillis() - lastInputAt
                    if (age in 100..30_000) ingest(pkg, captured)
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

    private fun onLeaveMessenger() {
        currentPkg = null
        currentContact = null
        inputFocused = false
        OverlayBus.clearActive()
        runCatching { ReflectOverlayService.stop(applicationContext) }
    }

    /**
     * 현재 화면이 답장 가능한 채팅방인지 휴리스틱:
     *  - 화면 어딘가에 EditText (또는 isEditable 노드)가 있어야 채팅방으로 간주
     *  - 단 hint 가 "검색", "search" 같은 거면 검색창이므로 제외
     *  - DFS 노드 200개 제한 (배터리·CPU)
     */
    private fun screenHasReplyInput(): Boolean {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        try {
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            var n = 0
            while (stack.isNotEmpty() && n < 300) {
                val node = stack.removeFirst()
                n++
                val cls = node.className?.toString().orEmpty()
                val editable = node.isEditable ||
                    cls.contains("EditText", ignoreCase = true)
                if (editable) {
                    val hint = (node.hintText?.toString() ?: "") + " " +
                        (node.contentDescription?.toString() ?: "")
                    val isSearch = hint.contains("검색") ||
                        hint.contains("search", ignoreCase = true)
                    if (!isSearch) return true
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            }
            return false
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun refreshOverlayActive(pkg: String, contact: String?) {
        val now = System.currentTimeMillis()
        // 너무 자주 갱신되지 않도록 600ms throttle (단, 매칭된 inbox 가 바뀌면 통과)
        if (now - lastActivePostedAt < 600) return
        lastActivePostedAt = now

        val appKey = TargetApps.appKey(pkg)
        scope.launch {
            // 1) contact 매칭 우선
            val byContact = if (!contact.isNullOrBlank()) {
                runCatching {
                    InboxRepository.findRecentByContact(
                        applicationContext, appKey, contact, 60 * 60 * 1000L
                    )
                }.getOrNull()
            } else null

            // 2) fallback — 같은 앱의 최근 미처리
            val fallback = byContact ?: runCatching {
                InboxRepository.findMostRecentPending(applicationContext, appKey, 30 * 60 * 1000L)
            }.getOrNull()

            val sourceId = fallback?.id ?: 0L

            // 사용자가 명시 dismiss 한 inbox 면 안 띄움. 다음 새 메시지(다른 id)는 자동 활성.
            if (OverlayDismissPrefs.isDismissed(applicationContext, sourceId)) {
                Log.d(TAG, "dismissed: skip overlay for inbox=$sourceId")
                OverlayBus.clearActive()
                return@launch
            }

            val suggestions = fallback?.let { InboxRepository.parseSuggestions(it.suggestionsJson) }
                .orEmpty()

            val active = OverlayBus.ActiveInput(
                packageName = pkg,
                appKey = appKey,
                contact = fallback?.contact ?: contact,
                incoming = fallback?.incoming,
                suggestions = suggestions,
                originSourceId = sourceId,
            )
            OverlayBus.setActive(active)
            withContext(Dispatchers.Main) {
                ReflectOverlayService.start(applicationContext)
            }
        }
    }

    /**
     * 현재 활성 윈도우에서 채팅방 제목 추출.
     * 휴리스틱: rootInActiveWindow 의 트리에서, "Toolbar" / "ActionBar" / "title" 비슷한 노드의 첫 TextView text.
     * 실패해도 null 반환 (호출자가 알아서 폴백).
     */
    private fun extractContactName(): String? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        try {
            // 후보 노드 ID 들 (각 메신저별 흔한 형태)
            val candidateIds = listOf(
                "title", "toolbar_title", "action_bar_title",
                "name", "contact_name", "chatroom_name", "title_text",
            )
            for (id in candidateIds) {
                val nodes = runCatching {
                    root.findAccessibilityNodeInfosByViewId(root.packageName.toString() + ":id/" + id)
                }.getOrNull().orEmpty()
                for (n in nodes) {
                    val t = n.text?.toString()?.trim()
                    if (!t.isNullOrBlank() && t.length in 1..40) return t
                }
            }
            // 폴백: Toolbar/ActionBar 부모 안의 첫 TextView
            return findFirstToolbarTitle(root)
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun findFirstToolbarTitle(root: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var depth = 0
        while (stack.isNotEmpty() && depth < 2000) {
            val node = stack.removeFirst()
            depth++
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("Toolbar") || cls.contains("ActionBar") || cls.contains("AppBar")) {
                // 이 노드 자손에서 TextView 찾기
                val found = findFirstNonEmptyTextView(node)
                if (!found.isNullOrBlank()) return found
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun findFirstNonEmptyTextView(root: AccessibilityNodeInfo): String? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var n = 0
        while (stack.isNotEmpty() && n < 200) {
            val node = stack.removeFirst()
            n++
            val cls = node.className?.toString().orEmpty()
            if (cls.contains("TextView") && !cls.contains("EditText")) {
                val t = node.text?.toString()?.trim()
                if (!t.isNullOrBlank() && t.length in 1..40) return t
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    /**
     * 현재 활성 윈도우의 focused EditText 에 ACTION_SET_TEXT.
     * 실패하면 false. 자동 전송은 안 함 — 사용자가 확인 후 직접 전송.
     */
    private suspend fun commitToFocusedEditText(text: String): Boolean = withContext(Dispatchers.Main) {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@withContext false
        try {
            val edit = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: findFirstEditText(root)
                ?: return@withContext false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val args = Bundle().apply {
                        putCharSequence(
                            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                            text,
                        )
                    }
                    val ok = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    return@withContext ok
                } else {
                    return@withContext false
                }
            } finally {
                runCatching { edit.recycle() }
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun findFirstEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var n = 0
        while (stack.isNotEmpty() && n < 2000) {
            val node = stack.removeFirst()
            n++
            if (node.isEditable ||
                node.className?.toString()?.contains("EditText", true) == true
            ) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { stack.addLast(it) }
            }
        }
        return null
    }

    private fun ingest(pkg: String, text: String) {
        if (text.length < 2 || text.length > 500) return
        val app = TargetApps.appKey(pkg)
        scope.launch {
            val recent = runCatching {
                InboxRepository.findMostRecentPending(applicationContext, app, 5 * 60 * 1000L)
            }.getOrNull()

            if (recent != null) {
                val suggestions = runCatching {
                    InboxRepository.parseSuggestions(recent.suggestionsJson)
                }.getOrDefault(emptyList())
                try {
                    BackendApi.feedbackReject(
                        FeedbackRejectRequest(
                            app = app,
                            contact = recent.contact,
                            incoming_message = recent.incoming,
                            rejected_suggestions = suggestions,
                            chosen_reply = text,
                        )
                    )
                    runCatching { InboxRepository.markHandled(applicationContext, recent.id) }
                    Log.i(TAG, "DPO captured for ${recent.contact}: ${text.take(40)}...")
                    return@launch
                } catch (e: Throwable) {
                    Log.w(TAG, "feedback failed, falling back to plain ingest", e)
                }
            }

            runCatching {
                BackendApi.ingest(
                    IngestRequest(
                        app = app,
                        contact = currentContact,
                        incoming_message = "(접근성 자동 학습)",
                        my_reply = text,
                    )
                )
                Log.i(TAG, "ingested: ${text.take(40)}...")
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        commitJob?.cancel()
        super.onDestroy()
    }

    companion object { private const val TAG = "ReflectA11y" }
}
