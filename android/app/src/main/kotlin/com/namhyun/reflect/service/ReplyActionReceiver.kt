package com.namhyun.reflect.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.Toast
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.IngestRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 추천 답변 알림에서 답변 버튼 탭 시 동작:
 *  1) NotificationListenerService 의 active 알림 목록에서 원본 메신저 알림 찾기
 *  2) 그 알림의 RemoteInput action 을 사용해서 자동 답장 발송
 *  3) 백엔드에 ingest (자동 학습)
 *  4) Reflect 의 추천 알림 닫기
 */
class ReplyActionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val notifKey = intent.getStringExtra(EXTRA_NOTIFICATION_KEY) ?: return
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val contact = intent.getStringExtra(EXTRA_CONTACT)
        val replyText = intent.getStringExtra(EXTRA_REPLY_TEXT) ?: return

        val sent = sendReplyToOriginal(context, notifKey, replyText)
        if (sent) {
            Toast.makeText(context, "답장 발송: $replyText".take(60), Toast.LENGTH_SHORT).show()
            // 자기 자신 알림 dismiss
            val id = (pkg + notifKey).hashCode()
            context.getSystemService(NotificationManager::class.java).cancel(id)
            // 백엔드 ingest (자동 학습)
            scope.launch {
                BackendApi.ingest(
                    IngestRequest(
                        app = TargetApps.appKey(pkg),
                        contact = contact,
                        incoming_message = "", // listener 에서 따로 들고와도 됨
                        my_reply = replyText,
                    )
                )
            }
        } else {
            // RemoteInput 못 찾으면 클립보드로 폴백
            CopyToClipboardReceiver.copy(context, replyText)
            Toast.makeText(context, "답장 못 보내서 클립보드 복사함", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * NotificationListener 가 보유한 active 알림 중에서 originalKey 매칭하는 알림을 찾고
     * 그 알림의 RemoteInput action 을 trigger 해서 답장 발송한다.
     */
    private fun sendReplyToOriginal(
        context: Context,
        notifKey: String,
        replyText: String,
    ): Boolean {
        val active: Array<StatusBarNotification> = try {
            ReflectListenerHolder.activeNotifications() ?: return false
        } catch (t: Throwable) {
            Log.w(TAG, "activeNotifications failed", t); return false
        }

        val sbn = active.firstOrNull { it.key == notifKey } ?: return false
        val replyAction = sbn.notification.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return false

        return triggerRemoteInput(context, replyAction, replyText)
    }

    private fun triggerRemoteInput(
        context: Context,
        action: Notification.Action,
        text: CharSequence,
    ): Boolean {
        val remoteInputs = action.remoteInputs ?: return false
        val sendIntent = Intent()
        val bundle = Bundle()
        for (ri in remoteInputs) bundle.putCharSequence(ri.resultKey, text)
        RemoteInput.addResultsToIntent(remoteInputs, sendIntent, bundle)

        return try {
            action.actionIntent.send(context, 0, sendIntent)
            true
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "actionIntent canceled", e)
            false
        }
    }

    companion object {
        private const val TAG = "ReplyAction"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_CONTACT = "contact"
        const val EXTRA_REPLY_TEXT = "reply_text"
    }
}

/**
 * NotificationListenerService 인스턴스에 외부에서 접근하기 위한 holder.
 * onListenerConnected 시점에 set, onListenerDisconnected 시점에 null.
 */
object ReflectListenerHolder {
    @Volatile var instance: ReflectNotificationListener? = null

    fun activeNotifications(): Array<StatusBarNotification>? =
        instance?.activeNotifications
}
