package com.namhyun.reflect.service

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast

class CopyToClipboardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        copy(context, text)
        Toast.makeText(context, "클립보드에 복사됨", Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_TEXT = "text"

        fun copy(context: Context, text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Reflect 답장", text))
        }
    }
}
