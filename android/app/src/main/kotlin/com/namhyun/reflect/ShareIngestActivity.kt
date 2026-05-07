package com.namhyun.reflect

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.IngestRequest
import kotlinx.coroutines.launch

/**
 * 카톡 등에서 사용자가 본인 답장을 길게 눌러 "공유" → Reflect 선택 시 받음.
 * 자동으로 /api/ingest 호출 → RAG 학습 → Toast 표시 → 즉시 종료.
 */
class ShareIngestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { handleShare(intent) }
        finish()
    }

    private fun handleShare(intent: Intent?) {
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(this, "공유된 텍스트 없음", Toast.LENGTH_SHORT).show()
            return
        }
        // 응답 메시지로 학습 — incoming은 빈 값으로 (자기 발화 단독 학습)
        // contact 가 없어도 my_reply 만 있으면 페르소나 강화에 도움
        Toast.makeText(this, "학습됨: ${text.take(20)}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            runCatching {
                BackendApi.ingest(
                    IngestRequest(
                        app = "share",
                        contact = null,
                        incoming_message = "(공유 학습)",
                        my_reply = text,
                    )
                )
            }
        }
    }
}
