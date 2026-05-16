package com.namhyun.reflect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.IngestRequest
import com.namhyun.reflect.data.InboxEntity
import com.namhyun.reflect.data.InboxGroup
import com.namhyun.reflect.data.InboxRepository
import com.namhyun.reflect.data.toGroups
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** 스와이프로 무시/처리됨 한 그룹의 일시적 실행취소 레코드. */
private data class DismissedRec(
    val key: String,
    val ids: List<Long>,
    val contact: String,
    val label: String,
)

@Composable
fun InboxList(modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    var query by rememberSaveable { mutableStateOf("") }

    val items by produceState<List<InboxEntity>>(initialValue = emptyList()) {
        try {
            InboxRepository.observePending(context)
                .catch { value = emptyList() }
                .collect { value = it }
        } catch (_: Throwable) { value = emptyList() }
    }

    val groups = remember(items, query) {
        runCatching { items.toGroups(query) }.getOrDefault(emptyList())
    }

    val hasAnyMessages = remember(items) { items.any { !it.handled } }

    val scope = rememberCoroutineScope()
    // 스와이프로 막 무시/처리한 그룹들 — 일정 시간 실행취소 가능.
    val dismissed = remember { mutableStateListOf<DismissedRec>() }
    val dismissedIds = remember(dismissed.toList()) { dismissed.flatMap { it.ids }.toSet() }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (hasAnyMessages || dismissed.isNotEmpty()) {
            SearchBar(query = query, onChange = { query = it })
        }

        // 방금 무시/처리한 항목은 실행취소 바로 같은 자리에 잠깐 남는다.
        dismissed.forEach { rec ->
            UndoBar(
                rec = rec,
                onUndo = {
                    dismissed.remove(rec)
                    scope.launch {
                        runCatching { InboxRepository.unmarkGroupHandled(context, rec.ids) }
                    }
                },
                onExpire = { dismissed.remove(rec) },
            )
        }

        if (groups.isEmpty() && dismissed.isEmpty()) {
            EmptyInbox(searching = query.isNotBlank())
        } else {
            // 이미 실행취소 바로 표시 중인 그룹은 카드에서 제외(중복 방지).
            groups.filterNot { g -> g.ids.any { it in dismissedIds } }
                .forEach { group ->
                    SwipeableCard(group) { label ->
                        // 같은 그룹 중복 등록 방어 (실행취소 바 2개 방지).
                        if (dismissed.none { it.ids == group.ids }) {
                            scope.launch { runCatching { InboxRepository.markGroupHandled(context, group.ids) } }
                            dismissed.add(
                                DismissedRec(
                                    key = group.ids.joinToString(",") + ":" + System.currentTimeMillis(),
                                    ids = group.ids,
                                    contact = group.contact,
                                    label = label,
                                )
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun UndoBar(
    rec: DismissedRec,
    onUndo: () -> Unit,
    onExpire: () -> Unit,
) {
    LaunchedEffect(rec.key) {
        delay(5_000)
        onExpire()
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${rec.contact} · ${rec.label}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUndo) {
                Text(
                    "실행취소",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableCard(group: InboxGroup, onDismiss: (label: String) -> Unit) {
    // confirmValueChange 는 스와이프 한 번에도 여러 번 호출될 수 있어
    // 여기서 onDismiss 를 직접 부르면 실행취소 바가 중복 생성된다.
    // → confirmValueChange 는 "허용 여부"만 반환(순수)하고, 실제 dismiss
    //   side-effect 는 currentValue 가 확정될 때 정확히 1회만 실행.
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value -> value != SwipeToDismissBoxValue.Settled },
        positionalThreshold = { it * 0.4f },
    )
    val onDismissState = rememberUpdatedState(onDismiss)
    var fired by remember(group.ids) { mutableStateOf(false) }
    LaunchedEffect(state) {
        snapshotFlow { state.currentValue }.collect { value ->
            if (fired) return@collect
            val label = when (value) {
                SwipeToDismissBoxValue.StartToEnd -> "처리됨"
                SwipeToDismissBoxValue.EndToStart -> "무시됨"
                else -> null
            }
            if (label != null) {
                fired = true
                onDismissState.value(label)
            }
        }
    }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            val color = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primary
                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.error
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            Surface(
                color = color.copy(alpha = 0.18f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    contentAlignment = when (state.dismissDirection) {
                        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                        else -> Alignment.Center
                    },
                ) {
                    Text(
                        when (state.dismissDirection) {
                            SwipeToDismissBoxValue.StartToEnd -> "처리됨"
                            SwipeToDismissBoxValue.EndToStart -> "무시"
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart)
                            MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        content = { ConversationCard(group) },
    )
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("발신자 또는 메시지 검색", color = MaterialTheme.colorScheme.outline) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

@Composable
private fun EmptyInbox(searching: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 36.dp, horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .padding(bottom = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(64.dp),
                ) {}
                Icon(
                    Icons.Rounded.Inbox,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                if (searching) "검색 결과 없음" else "받은 메시지 없음",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (searching) "다른 키워드로 시도해봐"
                else "메시지 오면 사람별로 묶어서 여기에 나타나",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationCard(group: InboxGroup) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item = group.latest
    val suggestions = remember(item.id) {
        runCatching { InboxRepository.parseSuggestions(item.suggestionsJson) }.getOrDefault(emptyList())
    }

    var editing by remember { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppDot(group.app)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.contact,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(timeAgo(item.arrivedAt), style = MaterialTheme.typography.bodySmall)
                }
                if (group.count > 1) {
                    CountBadge(count = group.count)
                    Spacer(Modifier.width(6.dp))
                }
                IconButton(
                    onClick = {
                        scope.launch { runCatching { InboxRepository.markGroupHandled(context, group.ids) } }
                    },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "처리됨",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                item.incoming,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
            )
            if (suggestions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEachIndexed { idx, s ->
                        if (s.isNotBlank()) {
                            SuggestionChip(
                                text = s,
                                lengthLabel = lengthLabelFor(idx),
                                onClick = {
                                    copyToClipboard(context, s)
                                    scope.launch {
                                        runCatching {
                                            BackendApi.ingest(
                                                IngestRequest(
                                                    app = group.app, contact = group.contact,
                                                    incoming_message = item.incoming, my_reply = s,
                                                )
                                            )
                                        }
                                        runCatching { InboxRepository.markGroupHandled(context, group.ids) }
                                    }
                                },
                                onLongClick = { editing = s },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { initialText ->
        EditAndCopyDialog(
            initial = initialText,
            onDismiss = { editing = null },
            onConfirm = { final ->
                editing = null
                copyToClipboard(context, final)
                scope.launch {
                    runCatching {
                        BackendApi.ingest(
                            IngestRequest(
                                app = group.app, contact = group.contact,
                                incoming_message = item.incoming, my_reply = final,
                            )
                        )
                    }
                    runCatching { InboxRepository.markGroupHandled(context, group.ids) }
                }
            },
        )
    }
}

@Composable
private fun EditAndCopyDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, androidx.compose.ui.text.TextRange(initial.length))) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("답변 다듬어 복사", style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text.text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("복사")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        },
    )
}

@Composable
private fun CountBadge(count: Int) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
        Text(
            "+$count",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SuggestionChip(
    text: String,
    lengthLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                lengthLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(14.dp).padding(start = 6.dp, top = 2.dp),
            )
        }
    }
}

private fun lengthLabelFor(index: Int): String = when (index) {
    0 -> "짧"
    1 -> "중"
    else -> "긴"
}

@Composable
private fun AppDot(app: String) {
    val color = when (app) {
        "kakao" -> MaterialTheme.colorScheme.primary
        "instagram" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Surface(shape = CircleShape, color = color.copy(alpha = 0.15f), modifier = Modifier.size(34.dp)) {}
        Text(
            app.take(1).uppercase(),
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Reflect", text))
        Toast.makeText(context, "복사됨", Toast.LENGTH_SHORT).show()
    }
}

private fun timeAgo(ts: Long): String = runCatching {
    val diff = System.currentTimeMillis() - ts
    when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "방금"
        diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}분 전"
        diff < TimeUnit.DAYS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toHours(diff)}시간 전"
        else -> "${TimeUnit.MILLISECONDS.toDays(diff)}일 전"
    }
}.getOrDefault("")
