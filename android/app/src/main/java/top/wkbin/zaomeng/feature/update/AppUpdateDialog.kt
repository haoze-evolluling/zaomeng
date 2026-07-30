package top.wkbin.zaomeng.feature.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.wkbin.zaomeng.BuildConfig
import top.wkbin.zaomeng.data.update.AppUpdateDownloadState
import top.wkbin.zaomeng.data.update.AppUpdateDownloadStatus
import top.wkbin.zaomeng.data.update.AppUpdateInfo

@Composable
fun AppUpdateDialog(
    update: AppUpdateInfo,
    downloadState: AppUpdateDownloadState,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val status = downloadState.status.takeIf { downloadState.version == update.version }
        ?: AppUpdateDownloadStatus.Idle
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("发现新版本 ${update.version}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "当前版本 ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ReleaseNotesList(update.releaseNotes)
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDownload,
                enabled = status == AppUpdateDownloadStatus.Idle || status == AppUpdateDownloadStatus.Failed,
            ) {
                Text(
                    when (status) {
                        AppUpdateDownloadStatus.Downloading -> "正在下载"
                        AppUpdateDownloadStatus.Downloaded -> "已下载"
                        AppUpdateDownloadStatus.Failed -> "重新下载"
                        AppUpdateDownloadStatus.Idle -> "下载更新"
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}

@Composable
private fun ReleaseNotesList(releaseNotes: String) {
    val lines = releaseNotes
        .lineSequence()
        .map(String::trim)
        .filterNot { it == "---" }
        .toList()
    LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (lines.none { it.isNotBlank() }) {
            item {
                Text(
                    text = "本次更新暂未提供详细说明。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            itemsIndexed(lines) { index, line ->
                when {
                    line.isBlank() -> Spacer(Modifier.height(3.dp))
                    line.startsWith("#") -> {
                        val heading = line.trimStart('#').trim()
                        if (heading.isNotBlank()) {
                            Text(
                                text = heading,
                                modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        Row(verticalAlignment = Alignment.Top) {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = line.drop(2).trim(),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    else -> Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
