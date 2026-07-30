package top.wkbin.zaomeng.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import top.wkbin.zaomeng.data.preferences.ChatFontSize

@Composable
fun ChatDisplaySettingsCard(
    modifier: Modifier = Modifier,
    preferencesRepository: AppPreferencesRepository = koinInject(),
) {
    val preferences by preferencesRepository.chatDisplayPreferences.collectAsStateWithLifecycle(
        initialValue = ChatDisplayPreferences(),
    )
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun persist(change: suspend () -> Unit) {
        if (saving) return
        scope.launch {
            saving = true
            error = ""
            try {
                change()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "聊天显示设置保存失败。"
            } finally {
                saving = false
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("消息字号", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "调整对话中文字的阅读尺寸。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChatFontSize.values().forEach { fontSize ->
                        FilterChip(
                            selected = preferences.fontSize == fontSize,
                            onClick = { persist { preferencesRepository.setChatFontSize(fontSize) } },
                            enabled = !saving,
                            label = { Text(fontSize.displayLabel) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("紧凑显示", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "缩小消息间距，在一屏内查看更多内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = preferences.compactMode,
                    onCheckedChange = { enabled ->
                        persist { preferencesRepository.setCompactChatMode(enabled) }
                    },
                    enabled = !saving,
                )
            }
            if (error.isNotBlank()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }
}

private val ChatFontSize.displayLabel: String
    get() = when (this) {
        ChatFontSize.SMALL -> "小"
        ChatFontSize.STANDARD -> "标准"
        ChatFontSize.LARGE -> "大"
    }
