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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        ensureChannel()
        ReflectListenerHolder.instance = this
        Log.i(TAG, "NotificationListener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (ReflectListenerHolder.instance == this) {
            ReflectListenerHolder.instance = null
        }
        Log.i(TAG, "NotificationListener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn)
        } catch (t: Throwable) {
            Log.w(TAG, "onNotificationPosted failed", t)
        }
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

        // 답장(RemoteInput) 가능한 액션 찾기 — 없으면 클립보드 복사 폴백만 제공
        val replyAction: Notification.Action? = notification.actions?.firstOrNull { action ->
            action.remoteInputs?.any { it.allowFreeFormInput } == true
        }

        Log.i(TAG, "intercepted from $pkg: $title -> $text  (replyable=${replyAction != null})")

        scope.launch {
            val app = TargetApps.appKey(pkg)
            val req = SuggestRequest(
                app = app,
                contact = title,
                incoming_message = text,
            )
            val result = BackendApi.suggest(req).getOrNull() ?: run {
                Log.w(TAG, "suggest returned null")
                return@launch
            }
            showSuggestionNotification(
                contact = title,
                incoming = text,
                suggestions = result.suggestions,
                originalKey = sbn.key,
                originalPkg = pkg,
                replyAction = replyAction,
            )
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

            // 탭 = 답장 발송 (또는 클립보드 복사 폴백)
            val tapIntent = if (replyAction != null) {
                buildReplyPendingIntent(originalKey, originalPkg, contact, suggestion)
            } else {
                buildCopyPendingIntent(suggestion)
            }
            builder.addAction(
                NotificationCompat.Action.Builder(0, suggestion.take(40), tapIntent).build()
            )
        }

        // 메시지 도착마다 새 알림이 쌓이지 않도록 originalKey 기반 ID
        val id = (originalPkg + originalKey).hashCode()
        nm.notify(id, builder.build())
    }

    private fun buildReplyPendingIntent(
        originalKey: String,
        originalPkg: String,
        contact: String,
        suggestion: String,
    ): PendingIntent {
        val intent = Intent(this, ReplyActionReceiver::class.java).apply {
            putExtra(ReplyActionReceiver.EXTRA_NOTIFICATION_KEY, originalKey)
            putExtra(ReplyActionReceiver.EXTRA_PACKAGE, originalPkg)
            putExtra(ReplyActionReceiver.EXTRA_CONTACT, contact)
            putExtra(ReplyActionReceiver.EXTRA_REPLY_TEXT, suggestion)
        }
        return PendingIntent.getBroadcast(
            this,
            (originalKey + suggestion).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
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
