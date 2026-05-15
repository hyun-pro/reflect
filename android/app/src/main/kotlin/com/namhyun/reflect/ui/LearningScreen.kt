package com.namhyun.reflect.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.TrainingStatusResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Composable
fun LearningScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TrainingStatusResponse?>(null) }
    var loading by remember { mutableStateOf(true) }
    var triggering by remember { mutableStateOf(false) }
    var triggerMsg by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = true
        val r = withTimeoutOrNull(15_000) { BackendApi.trainingStatus() }
        status = r?.getOrNull()
        loading = false
    }

    LaunchedEffect(Unit) {
        refresh()
        while (true) {
            delay(30_000)
            runCatching {
                val r = withTimeoutOrNull(10_000) { BackendApi.trainingStatus() }
                r?.getOrNull()?.let { status = it }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 32.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "뒤로",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "딥러닝 진행도",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.height(4.dp))
            }

            status?.let { s ->
                item { ProgressGauge(s) }
                item { ModelStateCard(s) }
                item { CountsRow(s) }
                s.in_flight?.let { run ->
                    item { InFlightCard(run) }
                }
                s.latest?.let { latest ->
                    if (latest.id != s.in_flight?.id) {
                        item { LastRunCard(latest) }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (triggering) return@Button
                            triggering = true
                            triggerMsg = null
                            scope.launch {
                                val r = BackendApi.triggerTraining(force = false)
                                triggering = false
                                triggerMsg = r.fold(
                                    onSuccess = { res ->
                                        if (res.ok) "학습 큐 등록됨 (run #${res.run_id})"
                                        else "트리거 실패: ${res.reason}"
                                    },
                                    onFailure = { "요청 실패: ${it.message}" },
                                )
                                refresh()
                            }
                        },
                        enabled = !triggering && s.ready_to_train && s.in_flight == null,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text(
                            if (s.in_flight != null) "학습 진행 중"
                            else if (s.ready_to_train) "지금 학습 시작"
                            else "데이터 더 모이면 자동 학습",
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                    triggerMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!s.ready_to_train && s.in_flight == null) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = {
                                if (triggering) return@TextButton
                                triggering = true
                                triggerMsg = null
                                scope.launch {
                                    val r = BackendApi.triggerTraining(force = true)
                                    triggering = false
                                    triggerMsg = r.fold(
                                        onSuccess = { res ->
                                            if (res.ok) "강제 트리거됨 (run #${res.run_id})"
                                            else "실패: ${res.reason}"
                                        },
                                        onFailure = { "요청 실패: ${it.message}" },
                                    )
                                    refresh()
                                }
                            }
                        ) {
                            Text(
                                "데이터 부족해도 강제 학습 (테스트용)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            if (status == null && !loading) {
                item {
                    Text(
                        "상태를 불러올 수 없음 — 백엔드 연결 확인",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (loading && status == null) {
                item {
                    Text(
                        "불러오는 중…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProgressGauge(s: TrainingStatusResponse) {
    val progress = (s.pair_count.toFloat() / s.next_threshold.coerceAtLeast(1)).coerceIn(0f, 1f)
    val animated by animateFloatAsState(progress, animationSpec = tween(800), label = "gauge")
    val remaining = (s.next_threshold - s.pair_count).coerceAtLeast(0)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "다음 학습까지",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (remaining == 0) "지금 가능"
                    else "$remaining 페어 남음",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "(${s.pair_count} / ${s.next_threshold})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelStateCard(s: TrainingStatusResponse) {
    val (label, desc, color) = when {
        s.active_adapter != null -> Triple(
            "본인 전용 모델 가동 중",
            "어댑터: ${s.active_adapter.substringAfterLast('/')}",
            MaterialTheme.colorScheme.primary,
        )
        s.last_run_at != null -> Triple(
            "RAG + 스타일 프로파일",
            "이전 학습 실패. 데이터 더 모이면 재시도.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        s.pair_count >= 30 -> Triple(
            "RAG + 스타일 프로파일",
            "본인 답장을 RAG 로 흉내내는 중. 1000페어 모이면 자동 파인튜닝.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> Triple(
            "콜드 스타트",
            "부트스트랩 답변만으로 추천 중. 답장이 쌓일수록 정확해짐.",
            MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountsRow(s: TrainingStatusResponse) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BigStat("답장 페어", s.pair_count)
            BigStat("DPO 페어", s.dpo_count)
            BigStat("마지막 학습", s.last_run_pair_count?.toString() ?: "-", subtitle = formatTime(s.last_run_at))
        }
    }
}

@Composable
private fun BigStat(label: String, value: Int) = BigStat(label, value.toString(), null)

@Composable
private fun BigStat(label: String, value: String, subtitle: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun InFlightCard(run: com.namhyun.reflect.api.TrainingRun) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                "학습 중 — run #${run.id}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${run.base_model} | 페어 ${run.pair_count_at_start}, DPO ${run.dpo_count_at_start}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "시작: ${formatTime(run.started_at) ?: "?"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LastRunCard(run: com.namhyun.reflect.api.TrainingRun) {
    val (statusColor, statusLabel) = when (run.status) {
        "succeeded" -> MaterialTheme.colorScheme.primary to "성공"
        "failed" -> MaterialTheme.colorScheme.error to "실패"
        "cancelled" -> MaterialTheme.colorScheme.outline to "취소됨"
        else -> MaterialTheme.colorScheme.onSurfaceVariant to run.status
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "최근 학습 run #${run.id}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    statusLabel,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = statusColor,
                )
            }
            Spacer(Modifier.height(2.dp))
            run.eval_score?.let {
                Text(
                    "스타일 일치도: ${"%.1f".format(it * 100)} / 100",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${formatTime(run.finished_at) ?: "?"} | ${run.base_model}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            run.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatTime(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        val parsers = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )
        for (p in parsers) {
            try {
                val fmt = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val date = fmt.parse(iso) ?: continue
                val out = SimpleDateFormat("M/d HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getDefault() }
                return@runCatching out.format(date)
            } catch (_: Throwable) {}
        }
        iso
    }.getOrNull()
}
