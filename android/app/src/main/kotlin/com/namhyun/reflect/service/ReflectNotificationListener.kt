package com.namhyun.reflect.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.SuggestRequest
import com.namhyun.reflect.data.InboxRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 메신저 앱의 알림을 가로채서:
 *  1) 받은 메시지 텍스트 추출
 *  2) 백엔드에 추천 답변 요청
 *  3) 우리 앱 알림으로 추천 답변 3개를 액션 버튼으로 표시
 *  4) 사용자가 버튼 탭 → 원본 알림의 RemoteInput 으로 자동 답장 발송
 */
class ReflectNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                android.util.Log.w("ReflectListener", "coroutine failed", e)
            }
    )

    // 같은 메시지 디바운스: 5분 내 동일 (pkg+title+text) 무시
    private val recentHashes = LinkedHashMap<Int, Long>()
    private val DEBOUNCE_MS = 5 * 60 * 1000L

    // 다중 메시지 병합 큐: 같은 (pkg|title) 키의 메시지를 3초간 모아서 한 번에 처리
    private val mergeJobs = mutableMapOf<String, kotlinx.coroutines.Job>()
    private val mergeBuffer = mutableMapOf<String, MutableList<String>>()
    private val mergeLatest = mutableMapOf<String, Notification.Action?>()
    private val MERGE_WINDOW_MS = 3000L
    private val mergeLock = Any()

    private fun shouldProcess(pkg: String, title: String, text: String): Boolean {
        val hash = (pkg + title + text).hashCode()
        val now = System.currentTimeMillis()
        synchronized(recentHashes) {
            val it = recentHashes.entries.iterator()
            while (it.hasNext()) if (now - it.next().value > DEBOUNCE_MS) it.remove()
            if (recentHashes[hash]?.let { now - it < DEBOUNCE_MS } == true) return false
            recentHashes[hash] = now
            while (recentHashes.size > 200) {
                val first = recentHashes.entries.iterator().next()
                recentHashes.remove(first.key)
            }
        }
        return true
    }

    override fun onListenerConnected() {
        runCatching { super.onListenerConnected() }
        runCatching { ensureChannel() }
        ReflectListenerHolder.instance = this
        runCatching { KeepAliveService.start(applicationContext) }
    }

    override fun onListenerDisconnected() {
        runCatching { super.onListenerDisconnected() }
        if (ReflectListenerHolder.instance == this) ReflectListenerHolder.instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (t: Throwable) { Log.w(TAG, "onNotificationPosted", t) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg !in TargetApps.PACKAGES) return

        // 자기 자신은 무시 (Reflect 알림에 또 답장하지 않도록)
        if (pkg == applicationContext.packageName) return

        val notification = sbn.notification ?: return
        val extras = notification.extras

        val title = extras.getString(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: return

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: return

        if (text.isBlank()) return

        // 디바운스 — 같은 메시지 5분 내 중복 무시
        if (!shouldProcess(pkg, title, text)) {
            Log.d(TAG, "deduped: $pkg $title")
            return
        }

        // 답장(RemoteInput) 가능한 액션 찾기
        val replyAction: Notification.Action? = notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

        // 좋아요 / 팔로우 / 스토리 본 거 / 광고 같은 답장 불가 알림은 skip (RemoteInput 없음)
        if (replyAction == null) {
            Log.d(TAG, "skip non-replyable notification from $pkg: $title")
            return
        }

        // 추가 필터: 시스템성 카테고리 거르기 (PROMO 등)
        val category = notification.category
        if (category == Notification.CATEGORY_PROMO ||
            category == Notification.CATEGORY_RECOMMENDATION ||
            category == Notification.CATEGORY_EVENT ||
            category == Notification.CATEGORY_ALARM ||
            category == Notification.CATEGORY_REMINDER) {
            Log.d(TAG, "skip non-message category=$category from $pkg")
            return
        }

        Log.i(TAG, "intercepted from $pkg: $title -> $text")

        // 다중 메시지 병합: 같은 (pkg|title)의 메시지를 3초 동안 모음
        val mergeKey = "$pkg|$title"
        val originalKey = sbn.key
        synchronized(mergeLock) {
            mergeBuffer.getOrPut(mergeKey) { mutableListOf() }.add(text)
            mergeLatest[mergeKey] = replyAction
            mergeJobs[mergeKey]?.cancel()
            mergeJobs[mergeKey] = scope.launch {
                try {
                    kotlinx.coroutines.delay(MERGE_WINDOW_MS)
                    val messages: List<String>
                    val action: Notification.Action?
                    synchronized(mergeLock) {
                        messages = mergeBuffer.remove(mergeKey)?.toList().orEmpty()
                        action = mergeLatest.remove(mergeKey)
                        mergeJobs.remove(mergeKey)
                    }
                    if (messages.isEmpty()) return@launch
                    val combined = messages.joinToString("\n")
                    handleMerged(pkg, title, combined, originalKey, action)
                } catch (e: Throwable) {
                    Log.w(TAG, "merge job failed", e)
                }
            }
        }
    }

    private suspend fun handleMerged(
        pkg: String,
        title: String,
        combined: String,
        originalKey: String,
        replyAction: Notification.Action?,
    ) {
        try {
            val app = TargetApps.appKey(pkg)
            val req = SuggestRequest(
                app = app,
                contact = title,
                incoming_message = combined,
            )
            val result = runCatching { BackendApi.suggest(req).getOrNull() }
                .getOrNull() ?: run {
                    Log.w(TAG, "suggest returned null for: $title")
                    return
                }
            runCatching {
                InboxRepository.add(
                    context = applicationContext,
                    app = app,
                    contact = title,
                    incoming = combined,
                    suggestions = result.suggestions,
                    originalKey = originalKey,
                    originalPkg = pkg,
                )
            }.onFailure { Log.w(TAG, "inbox save failed", it) }
        } catch (e: Throwable) {
            Log.w(TAG, "handleMerged failed for $title", e)
        }
    }

    private fun showSuggestionNotification(
        contact: String,
        incoming: String,
        suggestions: List<String>,
        originalKey: String,
        originalPkg: String,
        replyAction: Notification.Action?,
    ) {
        val nm = getSystemService(NotificationManager::class.java)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("✦ $contact")
            .setContentText(incoming)
            .setStyle(NotificationCompat.BigTextStyle().bigText(incoming))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

        suggestions.take(3).forEachIndexed { idx, suggestion ->
            if (suggestion.isBlank()) return@forEachIndexed

            // 탭 = RemoteInput 답장 (사용자가 수정 가능, 보낸 후 자동 ingest)
            // 폴백 = 클립보드 복사
            val action = if (replyAction != null) {
                buildEditableReplyAction(originalKey, originalPkg, contact, incoming, suggestion)
            } else {
                NotificationCompat.Action.Builder(0, suggestion.take(40), buildCopyPendingIntent(suggestion)).build()
            }
            builder.addAction(action)
        }

        // 메시지 도착마다 새 알림이 쌓이지 않도록 originalKey 기반 ID
        val id = (originalPkg + originalKey).hashCode()
        nm.notify(id, builder.build())
    }

    /**
     * 액션 자체에 RemoteInput 을 달아서, 사용자가 추천 답변을 그 자리에서 수정 가능.
     * 수정한 최종 텍스트가 발송되고, 그 텍스트가 자동으로 RAG 에 ingest 됨 (자가 학습).
     */
    private fun buildEditableReplyAction(
        originalKey: String,
        originalPkg: String,
        contact: String,
        incoming: String,
        suggestion: String,
    ): NotificationCompat.Action {
        val intent = Intent(this, ReplyActionReceiver::class.java).apply {
            putExtra(ReplyActionReceiver.EXTRA_NOTIFICATION_KEY, originalKey)
            putExtra(ReplyActionReceiver.EXTRA_PACKAGE, originalPkg)
            putExtra(ReplyActionReceiver.EXTRA_CONTACT, contact)
            putExtra(ReplyActionReceiver.EXTRA_INCOMING, incoming)
            putExtra(ReplyActionReceiver.EXTRA_REPLY_TEXT, suggestion)
        }
        val pi = PendingIntent.getBroadcast(
            this,
            (originalKey + suggestion).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = androidx.core.app.RemoteInput.Builder(ReplyActionReceiver.KEY_USER_REPLY)
            .setLabel("수정해서 보내기")
            .build()
        return NotificationCompat.Action.Builder(0, suggestion.take(40), pi)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
    }

    private fun buildCopyPendingIntent(suggestion: String): PendingIntent {
        val intent = Intent(this, CopyToClipboardReceiver::class.java).apply {
            putExtra(CopyToClipboardReceiver.EXTRA_TEXT, suggestion)
        }
        return PendingIntent.getBroadcast(
            this,
            suggestion.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "답장 추천",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "메시지 도착 시 추천 답변을 알림으로 표시"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "ReflectListener"
        const val CHANNEL_ID = "reflect_suggestions"
    }
}
