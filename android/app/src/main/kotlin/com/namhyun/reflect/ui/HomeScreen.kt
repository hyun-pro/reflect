package com.namhyun.reflect.ui

import android.Manifest
import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import com.namhyun.reflect.BuildConfig
import com.namhyun.reflect.Permissions
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // 부트스트랩 미완료 시 BootstrapScreen 보여주기
    var showBootstrap by remember { mutableStateOf(!BootstrapPrefs.isDone(context)) }
    var showLearning by remember { mutableStateOf(false) }

    if (showBootstrap) {
        BootstrapScreen(
            onDone = { showBootstrap = false },
            onSkip = { showBootstrap = false },
        )
        return
    }
    if (showLearning) {
        LearningScreen(onBack = { showLearning = false })
        return
    }

    var notif by remember { mutableStateOf(Permissions.isNotificationListenerEnabled(context)) }
    var post by remember { mutableStateOf(Permissions.isPostNotificationsGranted(context)) }
    var battery by remember { mutableStateOf(Permissions.isIgnoringBatteryOptimizations(context)) }
    var accessibility by remember { mutableStateOf(Permissions.isAccessibilityEnabled(context)) }
    var overlay by remember { mutableStateOf(Permissions.isOverlayGranted(context)) }

    var showAddLearn by remember { mutableStateOf(false) }
    var lastBack by remember { mutableLongStateOf(0L) }
    val stats by androidx.compose.runtime.produceState<com.namhyun.reflect.api.StatsResponse?>(initialValue = null) {
        while (true) {
            runCatching {
                val s = com.namhyun.reflect.api.BackendApi.stats().getOrNull()
                if (s != null) value = s
            }
            kotlinx.coroutines.delay(15_000)
        }
    }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - lastBack < 2000) activity?.finish()
        else {
            lastBack = now
            Toast.makeText(context, "한 번 더 누르면 종료", Toast.LENGTH_SHORT).show()
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, ev ->
            if (ev == Lifecycle.Event.ON_RESUME) {
                runCatching { notif = Permissions.isNotificationListenerEnabled(context) }
                runCatching { post = Permissions.isPostNotificationsGranted(context) }
                runCatching { battery = Permissions.isIgnoringBatteryOptimizations(context) }
                runCatching { accessibility = Permissions.isAccessibilityEnabled(context) }
                runCatching { overlay = Permissions.isOverlayGranted(context) }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val postLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> post = granted }

    val ready = notif && post

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Reflect",
                    style = MaterialTheme.typography.displayMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (ready) "준비 완료 — 메시지가 오면 아래에 쌓여" else "권한 세 개만 켜면 끝",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
            }

            item {
                PermRow(
                    title = "알림 읽기",
                    desc = "메신저 알림을 읽을 수 있어야 답장을 추천할 수 있어",
                    granted = notif,
                    onGrant = { Permissions.openNotificationListenerSettings(context) },
                )
            }
            item {
                PermRow(
                    title = "알림 표시",
                    desc = "추천 답변을 알림으로 보여주는 데 필요",
                    granted = post,
                    onGrant = { runCatching { postLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) } },
                )
            }
            item {
                PermRow(
                    title = "배터리 최적화 제외",
                    desc = "백그라운드에서 끊기지 않도록 권장",
                    granted = battery,
                    onGrant = { Permissions.openBatteryOptimizationSettings(context) },
                )
            }
            item {
                PermRow(
                    title = "답장창 위 추천 (선택)",
                    desc = "카톡/인스타 답장창 위 ✦ 버튼 — 탭=펼침, 길게=5분끄기, X=1시간끄기. 새 메시지 오면 자동 다시.",
                    granted = overlay,
                    onGrant = { Permissions.openOverlaySettings(context) },
                )
            }
            item {
                PermRow(
                    title = "자동 학습 + 오버레이 트리거 (선택)",
                    desc = "답장창 열림 감지 + 본인이 보낸 답장 자동 학습. 위 \"답장창 위 추천\"과 함께 켜야 효과 최대",
                    granted = accessibility,
                    onGrant = { Permissions.openAccessibilitySettings(context) },
                )
            }

            // 학습 통계 카드 — 탭하면 LearningScreen 으로
            stats?.let { s ->
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showLearning = true },
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                StatItem("총", s.total)
                                StatItem("이번주", s.thisWeek)
                                StatItem("오늘", s.today)
                                Icon(
                                    Icons.Rounded.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                "딥러닝 진행도 보기 →",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 18.dp, bottom = 12.dp),
                            )
                        }
                    }
                }
            }

            // Inbox + 학습 추가 버튼
            item { Spacer(Modifier.height(8.dp)) }
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "받은 메시지",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showAddLearn = true }) {
                        Text("+ 학습", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { InboxList() }

            item { Spacer(Modifier.height(24.dp)) }
            if (showAddLearn) {
                item {
                    AddLearnDialog(
                        onDismiss = { showAddLearn = false },
                        onConfirm = { incoming, reply ->
                            showAddLearn = false
                            scope.launch {
                                runCatching {
                                    com.namhyun.reflect.api.BackendApi.ingest(
                                        com.namhyun.reflect.api.IngestRequest(
                                            app = "manual",
                                            incoming_message = incoming,
                                            my_reply = reply,
                                        )
                                    )
                                }
                                android.widget.Toast.makeText(context, "학습됨", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "Reflect v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "백그라운드 1시간마다 새 버전 자동 체크",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(stats: com.namhyun.reflect.api.StatsResponse) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatItem("총", stats.total)
            StatItem("이번주", stats.thisWeek)
            StatItem("오늘", stats.today)
        }
    }
}

@Composable
private fun StatItem(label: String, value: Int) {
    val animated by androidx.compose.animation.core.animateIntAsState(
        targetValue = value,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 800),
        label = "stat_count",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            animated.toString(),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddLearnDialog(
    onDismiss: () -> Unit,
    onConfirm: (incoming: String, reply: String) -> Unit,
) {
    var incoming by remember { mutableStateOf("") }
    var reply by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("답장 학습", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("받은 메시지와 본인의 좋은 답장을 추가하면 다음에 비슷한 메시지에서 더 정확하게 모방해.", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(
                    value = incoming,
                    onValueChange = { incoming = it },
                    placeholder = { Text("받은 메시지", color = MaterialTheme.colorScheme.outline) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 3,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it },
                    placeholder = { Text("내 답장", color = MaterialTheme.colorScheme.outline) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = { if (reply.isNotBlank()) onConfirm(incoming, reply) },
                enabled = reply.isNotBlank(),
            ) { Text("학습") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun PermRow(
    title: String,
    desc: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(granted)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(desc, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            if (granted) {
                Text("허용됨", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextButton(onClick = onGrant, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text("허용", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(2.dp))
                    Icon(
                        Icons.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(granted: Boolean) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .background(
                if (granted) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (granted) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(LocalContentColor.current.copy(alpha = 0.3f), CircleShape),
            )
        }
    }
}
