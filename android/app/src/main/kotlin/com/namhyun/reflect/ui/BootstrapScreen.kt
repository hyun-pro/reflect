package com.namhyun.reflect.ui

import android.content.Context
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.namhyun.reflect.api.BackendApi
import com.namhyun.reflect.api.BootstrapAnswers
import kotlinx.coroutines.launch

object BootstrapPrefs {
    private const val PREFS = "reflect_bootstrap"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_DONE, false)
    }.getOrDefault(false)

    fun markDone(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DONE, true).apply()
        }
    }
}

@Composable
fun BootstrapScreen(onDone: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var avgLength by rememberSaveable { mutableStateOf("") }
    var emojiFreq by rememberSaveable { mutableStateOf("") }
    var laughter by rememberSaveable { mutableStateOf("") }
    var banmal by rememberSaveable { mutableStateOf("") }
    var endings by rememberSaveable { mutableStateOf("") }
    var catchphrases by rememberSaveable { mutableStateOf("") }
    var familyTone by rememberSaveable { mutableStateOf("") }
    var friendTone by rememberSaveable { mutableStateOf("") }
    var workTone by rememberSaveable { mutableStateOf("") }
    var freeNote by rememberSaveable { mutableStateOf("") }

    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    "1분만에 톤 알려주기",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "메시지가 충분히 쌓일 때까지 추천 품질을 지탱해주는 단서. 나중에 자동으로 본인 답장에서 학습해서 정확해지지만, 0개일 때 도움이 됨. 다 빈 칸이어도 OK.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
            }

            item {
                ChoiceQuestion(
                    title = "답장 길이는 보통",
                    options = listOf("짧게", "보통", "길게"),
                    selected = avgLength,
                    onSelect = { avgLength = it },
                )
            }
            item {
                ChoiceQuestion(
                    title = "이모지 (😀✨🥹 같은 것) 빈도",
                    options = listOf("거의 안 씀", "가끔", "자주"),
                    selected = emojiFreq,
                    onSelect = { emojiFreq = it },
                )
            }
            item {
                ChoiceQuestion(
                    title = "웃음 표현",
                    options = listOf("ㅋㅋ", "ㅎㅎ", "안 씀", "기타"),
                    selected = laughter,
                    onSelect = { laughter = it },
                )
            }
            item {
                ChoiceQuestion(
                    title = "반말/존댓말",
                    options = listOf("거의 반말", "반반", "거의 존댓말"),
                    selected = banmal,
                    onSelect = { banmal = it },
                )
            }
            item {
                TextQuestion(
                    title = "자주 쓰는 끝말 (선택)",
                    placeholder = "예) ~지, ~네, ~ㅇㅇ, ~음, ~ㅋ",
                    value = endings,
                    onChange = { endings = it },
                )
            }
            item {
                TextQuestion(
                    title = "자주 쓰는 표현 (선택)",
                    placeholder = "예) 그치, 근데, ㅇㅋ, 아 진짜",
                    value = catchphrases,
                    onChange = { catchphrases = it },
                )
            }
            item {
                TextQuestion(
                    title = "친구한테 답할 때 (선택)",
                    placeholder = "예) 짧고 ㅋㅋ 많이 씀",
                    value = friendTone,
                    onChange = { friendTone = it },
                )
            }
            item {
                TextQuestion(
                    title = "가족한테 답할 때 (선택)",
                    placeholder = "예) 편하지만 이모지 거의 없음",
                    value = familyTone,
                    onChange = { familyTone = it },
                )
            }
            item {
                TextQuestion(
                    title = "직장/윗사람한테 답할 때 (선택)",
                    placeholder = "예) 존댓말, 줄임말 안 씀",
                    value = workTone,
                    onChange = { workTone = it },
                )
            }
            item {
                TextQuestion(
                    title = "내 말투 한 줄로 (선택)",
                    placeholder = "예) 짧고 무뚝뚝, 가끔 이모지",
                    value = freeNote,
                    onChange = { freeNote = it },
                    maxLines = 3,
                )
            }

            error?.let { msg ->
                item {
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            BootstrapPrefs.markDone(context)
                            onSkip()
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("나중에", color = MaterialTheme.colorScheme.onSurfaceVariant) }

                    Button(
                        onClick = {
                            if (saving) return@Button
                            saving = true
                            error = null
                            scope.launch {
                                val answers = BootstrapAnswers(
                                    avg_length = avgLength.ifBlank { null },
                                    emoji_freq = emojiFreq.ifBlank { null },
                                    laughter = laughter.ifBlank { null },
                                    banmal_jondaemal = banmal.ifBlank { null },
                                    endings = endings.ifBlank { null },
                                    catchphrases = catchphrases.ifBlank { null },
                                    family_tone = familyTone.ifBlank { null },
                                    friend_tone = friendTone.ifBlank { null },
                                    work_tone = workTone.ifBlank { null },
                                    free_note = freeNote.ifBlank { null },
                                )
                                val result = BackendApi.postBootstrap(answers)
                                saving = false
                                result.fold(
                                    onSuccess = {
                                        BootstrapPrefs.markDone(context)
                                        onDone()
                                    },
                                    onFailure = { error = "저장 실패: ${it.message}" },
                                )
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.weight(2f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        if (saving) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Text("저장 중…")
                        } else {
                            Text("저장")
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun ChoiceQuestion(
    title: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { opt ->
                val isSelected = opt == selected
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    onClick = { onSelect(opt) },
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            opt,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TextQuestion(
    title: String,
    placeholder: String,
    value: String,
    onChange: (String) -> Unit,
    maxLines: Int = 2,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.outline) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            maxLines = maxLines,
        )
    }
}
