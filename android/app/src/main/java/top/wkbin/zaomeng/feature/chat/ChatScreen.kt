package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto

private data class MessageKindOption(
    val value: String,
    val label: String,
)

private val messageKindOptions = listOf(
    MessageKindOption("dialogue", "对话"),
    MessageKindOption("narration", "场景"),
    MessageKindOption("plot", "推剧情"),
)

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    runId: String,
    sessionId: String,
    onBack: () -> Unit,
    onOpenBranch: (runId: String, sessionId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(runId, sessionId) {
        viewModel.load(runId, sessionId)
    }
    LaunchedEffect(state.error) {
        if (state.error.isNotBlank()) {
            snackbarHostState.showSnackbar(state.error)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.notice) {
        if (state.notice.isNotBlank()) {
            snackbarHostState.showSnackbar(state.notice)
            viewModel.clearNotice()
        }
    }
    LaunchedEffect(state.navigationSession?.sessionId) {
        state.navigationSession?.let { session ->
            toolsOpen = false
            viewModel.consumeNavigationSession()
            onOpenBranch(session.runId, session.sessionId)
        }
    }

    if (toolsOpen && state.session != null) {
        ChatToolsSheet(
            state = state,
            onDismiss = { toolsOpen = false },
            onSuggest = viewModel::suggestReply,
            onAssociations = viewModel::requestAssociations,
            onDirector = viewModel::requestDirectorOptions,
            onCorrectLatest = viewModel::correctLatest,
            onDeepReviewLatest = viewModel::deepReviewLatest,
            onBranchTurn = viewModel::branchFromTurn,
            onBranchScene = viewModel::branchFromScene,
            onUpdateBranchMeta = viewModel::updateBranchMeta,
            onToggleMainlineEvent = viewModel::setMainlineEventLocked,
            onOpenExistingBranch = { branchSessionId ->
                toolsOpen = false
                onOpenBranch(runId, branchSessionId)
            },
            onLoadScenes = viewModel::loadSceneCards,
            onRecommendScene = viewModel::recommendNextScene,
            onSwitchScene = viewModel::switchScene,
            onSaveMemory = viewModel::saveMemory,
            onDeleteMemory = viewModel::deleteMemory,
            onRelationLock = viewModel::setRelationLock,
        )
    }

    if (state.toolOptions.isNotEmpty()) {
        ChatToolOptionsDialog(
            title = state.toolOptionsTitle,
            options = state.toolOptions,
            enabled = state.canUseTools,
            onChoose = viewModel::chooseToolOption,
            onDismiss = viewModel::dismissToolOptions,
        )
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                session = state.session,
                refreshing = state.refreshing,
                refreshEnabled = state.canRefresh,
                toolsEnabled = state.canUseTools,
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onOpenTools = { toolsOpen = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (state.session != null) {
                ChatComposer(
                    state = state,
                    onDraftChange = viewModel::updateDraft,
                    onMessageKindChange = viewModel::selectMessageKind,
                    onSend = viewModel::send,
                    onRecover = viewModel::recoverPending,
                    onReconcile = viewModel::reconcileUnknownSend,
                )
            }
        },
    ) { innerPadding ->
        when {
            state.loading -> ChatLoading(Modifier.padding(innerPadding))
            state.session == null -> MissingChat(
                error = state.error,
                onRetry = viewModel::refresh,
                modifier = Modifier.padding(innerPadding),
            )
            else -> Transcript(
                session = requireNotNull(state.session),
                sending = state.sending,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    session: DialogueSessionDto?,
    refreshing: Boolean,
    refreshEnabled: Boolean,
    toolsEnabled: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTools: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = session?.participants?.joinToString("、")?.ifBlank { "人物会话" } ?: "人物会话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                session?.let {
                    Text(
                        text = "${it.mode.chineseMode()} · ${if (it.status == "ready") "可继续" else "待处理"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
        },
        actions = {
            IconButton(onClick = onOpenTools, enabled = toolsEnabled) {
                Icon(Icons.Default.MoreVert, contentDescription = "会话工具")
            }
            IconButton(onClick = onRefresh, enabled = refreshEnabled) {
                if (refreshing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新聊天")
                }
            }
        },
    )
}

@Composable
private fun ChatToolOptionsDialog(
    title: String,
    options: List<ChatToolOption>,
    enabled: Boolean,
    onChoose: (ChatToolOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title.ifBlank { "选择一个方案" }) },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(options, key = { "${it.label}-${it.value}" }) { option ->
                    OutlinedButton(
                        onClick = { onChoose(option) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = enabled,
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(option.label, fontWeight = FontWeight.SemiBold)
                            if (option.value.isNotBlank() && option.value != option.label) {
                                Text(
                                    option.value,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (option.description.isNotBlank() && option.description != option.value) {
                                Text(
                                    option.description,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ChatLoading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Text(
                "正在打开这段故事…",
                modifier = Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MissingChat(error: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("暂时无法打开会话", style = MaterialTheme.typography.titleMedium)
            if (error.isNotBlank()) {
                Text(
                    error,
                    modifier = Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text("重试") }
        }
    }
}

@Composable
private fun Transcript(
    session: DialogueSessionDto,
    sending: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val transcript = session.transcript

    LaunchedEffect(transcript.size, transcript.lastOrNull()?.message, sending) {
        val target = transcript.lastIndex + if (sending) 1 else 0
        if (target >= 0) listState.animateScrollToItem(target)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (transcript.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Text(
                        "这一幕还没有留下台词。写下第一句话，让故事继续。",
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        itemsIndexed(
            items = transcript,
            key = { index, item -> "$index-${item.speaker}-${item.message.hashCode()}" },
        ) { _, item ->
            TranscriptBubble(item)
        }

        if (sending) {
            item(key = "generating") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "人物正在斟酌回应…",
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TranscriptBubble(item: TranscriptItemDto) {
    val isUser = item.role == "user"
    val isScene = item.role == "scene" || item.role == "director"

    if (isScene) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(16.dp),
            ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Text(
                        text = item.speaker.ifBlank { "场景提示" },
                            style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    ParentheticalMessageText(
                        text = item.message,
                        modifier = Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 18.dp,
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = if (isUser) "你 · ${item.speaker}" else item.speaker.ifBlank { "人物" },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                ParentheticalMessageText(
                    text = item.message,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private val parentheticalPattern = Regex("[（(][^（）()\\n]*[）)]")

@Composable
private fun ParentheticalMessageText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
) {
    val asideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
    val annotated = buildAnnotatedString {
        var cursor = 0
        parentheticalPattern.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            pushStyle(SpanStyle(color = asideColor))
            append(match.value)
            pop()
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
    Text(text = annotated, modifier = modifier, style = style)
}

@Composable
private fun ChatComposer(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onMessageKindChange: (String) -> Unit,
    onSend: () -> Unit,
    onRecover: () -> Unit,
    onReconcile: () -> Unit,
) {
    Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messageKindOptions, key = MessageKindOption::value) { option ->
                    FilterChip(
                        selected = state.messageKind == option.value,
                        onClick = { onMessageKindChange(option.value) },
                        enabled = !state.sending && !state.recovering && !state.sendOutcomeUnknown,
                        label = { Text(option.label) },
                    )
                }
            }

            if (state.messageKind == "plot") {
                Text(
                    "推动剧情的指令只用于导演下一幕，不会显示成你的台词。",
                    modifier = Modifier.padding(bottom = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    enabled = !state.sending && !state.recovering && !state.sendOutcomeUnknown,
                    placeholder = {
                        Text(
                            when (state.messageKind) {
                                "narration" -> "描述一个场景变化…"
                                "plot" -> "告诉人物接下来发生什么…"
                                else -> "写下你想说的话…"
                            },
                        )
                    },
                    minLines = 1,
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
                )
                Spacer(Modifier.size(2.dp))
                IconButton(
                    onClick = onSend,
                    enabled = state.canSend,
                ) {
                    if (state.sending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "发送",
                            tint = if (state.canSend) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                            },
                        )
                    }
                }
            }

            if (state.sendOutcomeUnknown) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (state.session?.status == "ready") {
                            "上一次发送结果尚未核对，确认前不会重复发送。"
                        } else {
                            "上一次发送仍处于待处理状态，可以放弃这轮并恢复会话。"
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = if (state.session?.status == "ready") onReconcile else onRecover,
                        enabled = !state.sending && !state.recovering,
                    ) {
                        if (state.recovering) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (state.session?.status == "ready") "核对结果" else "恢复会话")
                        }
                    }
                }
            } else if (state.session?.status != "ready") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "上一次生成没有正常结束，恢复后可以继续发送。",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = onRecover,
                        enabled = !state.sending && !state.recovering,
                    ) {
                        if (state.recovering) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text("恢复会话")
                        }
                    }
                }
            }
        }
    }
}

private fun String.chineseMode(): String = when (this) {
    "act" -> "扮演人物"
    "insert" -> "自设入场"
    else -> "旁观群聊"
}
