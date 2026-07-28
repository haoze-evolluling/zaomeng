package top.wkbin.zaomeng.feature.bookshelf

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    viewModel: BookshelfViewModel,
    onImport: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // NavHost retains this destination while import/detail is displayed. Refresh the
    // list when it returns to the foreground so a new book appears immediately.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshWhenResumed()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "造梦书架",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "故事只保存在这台手机上",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenCards) {
                        Icon(Icons.Outlined.CollectionsBookmark, contentDescription = "创作资料库")
                    }
                    IconButton(onClick = onOpenSessions) {
                        Icon(Icons.Outlined.Forum, contentDescription = "查看会话")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "模型设置")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.backendState is BackendState.Ready) {
                ExtendedFloatingActionButton(
                    onClick = onImport,
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("导入小说") },
                )
            }
        },
    ) { innerPadding ->
        when (val backendState = state.backendState) {
            BackendState.Idle -> BackendLoading(
                message = "正在准备本地故事工坊…",
                modifier = Modifier.padding(innerPadding),
            )

            is BackendState.Starting -> BackendLoading(
                message = backendState.message,
                modifier = Modifier.padding(innerPadding),
            )

            is BackendState.Failed -> BackendFailure(
                message = backendState.message,
                onRetry = viewModel::retryBackend,
                modifier = Modifier.padding(innerPadding),
            )

            is BackendState.Ready -> ReadyBookshelf(
                state = state,
                onRefresh = viewModel::refresh,
                onDismissError = viewModel::dismissError,
                onImport = onImport,
                onOpenRun = onOpenRun,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@Composable
private fun ReadyBookshelf(
    state: BookshelfUiState,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onImport: () -> Unit,
    onOpenRun: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "我的书卷",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${state.runs.size} 个本地项目",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh, enabled = !state.refreshing && !state.loadingRuns) {
                    if (state.refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新书架")
                    }
                }
            }
        }

        if (state.error.isNotBlank()) {
            item {
                ErrorCard(
                    message = state.error,
                    onRetry = onRefresh,
                    onDismiss = onDismissError,
                )
            }
        }

        if (state.loadingRuns) {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在整理书架…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else if (state.runs.isEmpty()) {
            item { EmptyBookshelf(onImport) }
        } else {
            items(state.runs, key = RunManifestDto::runId) { run ->
                RunCard(run = run, onClick = { onOpenRun(run.runId) })
            }
        }
    }
}

@Composable
private fun RunCard(run: RunManifestDto, onClick: () -> Unit) {
    val totalCharacters = maxOf(run.progress.totalCharacters, run.lockedCharacters.size)
    val completedCharacters = maxOf(run.progress.completedCount, run.availableCharacters.size)
    val progress = if (totalCharacters > 0) {
        (completedCharacters.toFloat() / totalCharacters).coerceIn(0f, 1f)
    } else {
        null
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (run.updatedAt.isNotBlank()) {
                        Text(
                            text = "更新于 ${run.updatedAt.toLocalDateTimeDisplay("时间未记录")}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                StatusPill(run.status)
            }

            if (run.status == "running") {
                if (progress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }

            Text(
                text = run.progress.message.ifBlank { statusDescription(run.status) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = if (totalCharacters > 0) "$completedCharacters / $totalCharacters 位人物" else "等待人物资料",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                run.novelSources.firstOrNull()?.charCount?.takeIf { it > 0 }?.let { count ->
                    Text(
                        text = "${count.formatCount()} 字",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val ready = status == "ready"
    val failed = status == "failed"
    val container = when {
        ready -> MaterialTheme.colorScheme.primaryContainer
        failed -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        ready -> MaterialTheme.colorScheme.onPrimaryContainer
        failed -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(999.dp)) {
        Text(
            text = statusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EmptyBookshelf(onImport: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("书架还是空的", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "放入 TXT 正文开始蒸馏人物，或者导入以前导出的书卷包。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImport) { Text("导入第一本书") }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onRetry) { Text("重试") }
                OutlinedButton(onClick = onDismiss) { Text("忽略") }
            }
        }
    }
}

@Composable
private fun BackendLoading(message: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(14.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                "首次启动需要准备内置 Python 环境",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BackendFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "本地服务没有启动成功",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                Button(onClick = onRetry) { Text("重新启动") }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "ready" -> "可使用"
    "running" -> "蒸馏中"
    "draft" -> "待蒸馏"
    "failed" -> "失败"
    "stopped" -> "已停止"
    else -> status.ifBlank { "未知" }
}

private fun statusDescription(status: String): String = when (status) {
    "ready" -> "人物资料已经可以校对，也可以开始新的会话。"
    "running" -> "人物和关系正在这台手机上逐步整理。"
    "draft" -> "正文已经导入，可以打开书卷开始蒸馏。"
    "failed" -> "这次处理没有完成，可以打开书卷查看详情。"
    "stopped" -> "蒸馏已经停止，已有结果仍会保留。"
    else -> "打开书卷查看当前状态。"
}

private fun Int.formatCount(): String = when {
    this >= 10_000 -> "%.1f万".format(this / 10_000f)
    else -> toString()
}
