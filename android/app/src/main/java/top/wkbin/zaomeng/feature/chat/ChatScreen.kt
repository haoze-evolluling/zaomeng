package top.wkbin.zaomeng.feature.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private data class MessageKindOption(
    val value: String,
    val label: String,
)

private val messageKindOptions = listOf(
    MessageKindOption("dialogue", "对话"),
    MessageKindOption("narration", "旁白"),
    MessageKindOption("plot", "导演"),
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var toolsOpen by rememberSaveable { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(runId, sessionId) {
        viewModel.load(runId, sessionId)
    }
    DisposableEffect(lifecycleOwner, runId, sessionId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.pauseContinuousObserve()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.pauseContinuousObserve()
        }
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
                searchOpen = searchOpen,
                onBack = onBack,
                onRefresh = viewModel::refresh,
                onOpenTools = { toolsOpen = true },
                onToggleSearch = {
                    searchOpen = !searchOpen
                    if (!searchOpen) viewModel.updateSearchQuery("")
                },
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
                    onToggleContinuousObserve = viewModel::toggleContinuousObserve,
                    onRecover = viewModel::recoverPending,
                    onReconcile = viewModel::reconcileUnknownSend,
                    onRetry = viewModel::retryLastSend,
                    onDiscardRetry = viewModel::discardFailedSend,
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
            else -> Column(Modifier.padding(innerPadding).fillMaxSize()) {
                if (searchOpen) {
                    ChatSearchBar(
                        query = state.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        onClose = {
                            searchOpen = false
                            viewModel.updateSearchQuery("")
                        },
                    )
                }
                if (searchOpen && state.searchQuery.isNotBlank()) {
                    ChatSearchResults(
                        query = state.searchQuery,
                        searching = state.searching,
                        results = state.searchResults,
                        actionsEnabled = state.canUseTools,
                        onBranch = viewModel::branchFromTurn,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Transcript(
                        session = requireNotNull(state.session),
                        avatarBytes = state.avatarBytes,
                        sending = state.sending,
                        streamStatus = state.streamStatus,
                        streamingReplies = state.streamingReplies,
                        pendingUserMessage = state.pendingUserMessage,
                        directorReceipts = state.directorReceipts,
                        displayPreferences = state.chatDisplay,
                        actionsEnabled = state.canUseTools,
                        onRegenerate = viewModel::correctLatest,
                        onBranch = viewModel::branchFromTurn,
                        onPendingRetry = viewModel::retryLastSend,
                        onPendingEdit = viewModel::discardFailedSend,
                        onPendingReconcile = viewModel::reconcileUnknownSend,
                        onPendingRecover = viewModel::recoverPending,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
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
    searchOpen: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTools: () -> Unit,
    onToggleSearch: () -> Unit,
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
            IconButton(onClick = onToggleSearch) {
                Icon(
                    if (searchOpen) Icons.Default.Close else Icons.Default.Search,
                    contentDescription = if (searchOpen) "关闭聊天搜索" else "搜索聊天记录",
                )
            }
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
private fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "清空搜索",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            placeholder = { Text("搜索台词、动作或人物") },
            shape = RoundedCornerShape(20.dp),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Default.Close, contentDescription = "关闭搜索")
        }
    }
}

@Composable
private fun ChatSearchResults(
    query: String,
    searching: Boolean,
    results: List<ChatSearchResultDto>,
    actionsEnabled: Boolean,
    onBranch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        searching -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        results.isEmpty() -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "没有找到相关聊天记录",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> LazyColumn(
            modifier = modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "找到 ${results.size} 条结果",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(
                items = results,
                key = { index, item ->
                    "${item.turnId}-${item.timestamp}-${item.message.hashCode()}-$index"
                },
            ) { _, result ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                result.speaker.ifBlank { "人物" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (result.archived) {
                                Text(
                                    "较早记录",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HighlightedSearchText(result.message, query)
                        if (result.turnId.isNotBlank()) {
                            TextButton(
                                onClick = { onBranch(result.turnId) },
                                enabled = actionsEnabled,
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Icon(
                                    Icons.Default.CallSplit,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                )
                                Text("从此处分支", modifier = Modifier.padding(start = 5.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightedSearchText(text: String, query: String) {
    val highlightColor = MaterialTheme.colorScheme.tertiaryContainer
    val annotated = remember(text, query, highlightColor) {
        buildAnnotatedString {
            var cursor = 0
            Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
                append(text.substring(cursor, match.range.first))
                pushStyle(SpanStyle(background = highlightColor, fontWeight = FontWeight.SemiBold))
                append(match.value)
                pop()
                cursor = match.range.last + 1
            }
            append(text.substring(cursor))
        }
    }
    Text(annotated, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun Transcript(
    session: DialogueSessionDto,
    avatarBytes: Map<String, ByteArray>,
    sending: Boolean,
    streamStatus: String,
    streamingReplies: List<StreamingReplyPart>,
    pendingUserMessage: PendingUserMessage?,
    directorReceipts: List<DirectorReceipt>,
    displayPreferences: ChatDisplayPreferences,
    actionsEnabled: Boolean,
    onRegenerate: () -> Unit,
    onBranch: (String) -> Unit,
    onPendingRetry: () -> Unit,
    onPendingEdit: () -> Unit,
    onPendingReconcile: () -> Unit,
    onPendingRecover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrollScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val bottomThresholdPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val transcript = session.transcript
    val latestAssistantIndex = transcript.indexOfLast { item ->
        item.role != "user" && item.role != "scene" && item.role != "director"
    }
    var followNewMessages by remember(session.sessionId) { mutableStateOf(true) }
    var unseenMessages by remember(session.sessionId) { mutableIntStateOf(0) }
    var previousVisibleCount by remember(session.sessionId) {
        mutableIntStateOf(
            transcript.size + streamingReplies.size + directorReceipts.size +
                if (pendingUserMessage == null) 0 else 1,
        )
    }
    val isAtBottom by remember(listState, bottomThresholdPx) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val lastVisible = layout.visibleItemsInfo.lastOrNull()
            layout.totalItemsCount == 0 || (
                lastVisible?.index == layout.totalItemsCount - 1 &&
                    lastVisible.offset + lastVisible.size <=
                    layout.viewportEndOffset + bottomThresholdPx
                )
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .distinctUntilChanged()
            .collect { (scrolling, atBottom) ->
                if (scrolling) followNewMessages = atBottom
                if (atBottom) unseenMessages = 0
            }
    }

    val visibleCount = transcript.size + streamingReplies.size + directorReceipts.size +
        if (pendingUserMessage == null) 0 else 1
    val lastContentIndex = (visibleCount - 1).coerceAtLeast(0)
    val contentSignature = transcript.lastOrNull()?.message.orEmpty() +
        streamingReplies.joinToString(separator = "|") { it.text } +
        pendingUserMessage?.let { "${it.operationId}|${it.status}|${it.statusText}" }.orEmpty() +
        directorReceipts.joinToString(separator = "|") { it.operationId }
    LaunchedEffect(visibleCount, contentSignature, sending) {
        val added = (visibleCount - previousVisibleCount).coerceAtLeast(0)
        previousVisibleCount = visibleCount
        if (followNewMessages) {
            listState.scrollToBottom(lastContentIndex, animated = false)
            unseenMessages = 0
        } else if (added > 0) {
            unseenMessages = (unseenMessages + added).coerceAtMost(99)
        }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = 16.dp,
                vertical = if (displayPreferences.compactMode) 6.dp else 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(
                if (displayPreferences.compactMode) 3.dp else 8.dp,
            ),
        ) {
            if (transcript.isEmpty() && streamingReplies.isEmpty() && pendingUserMessage == null &&
                directorReceipts.isEmpty()
            ) {
                item {
                    Text(
                        "这一幕还没有留下台词。写下第一句话，让故事继续。",
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            itemsIndexed(
                items = transcript,
                key = { index, item ->
                    item.turnId.ifBlank { "$index-${item.speaker}-${item.message.hashCode()}" } +
                        "-$index"
                },
            ) { index, item ->
                TranscriptBubble(
                    item = item,
                    avatarBytes = avatarBytes,
                    displayPreferences = displayPreferences,
                    actionsEnabled = actionsEnabled,
                    canRegenerate = index == latestAssistantIndex && !sending,
                    onCopy = { clipboard.setText(AnnotatedString(item.message)) },
                    onRegenerate = onRegenerate,
                    onBranch = { onBranch(item.turnId) },
                )
            }

            pendingUserMessage?.let { pending ->
                item(key = "pending-${pending.operationId}") {
                    PendingUserMessageBubble(
                        pending = pending,
                        onRetry = onPendingRetry,
                        onEdit = onPendingEdit,
                        onReconcile = onPendingReconcile,
                        onRecover = onPendingRecover,
                        requiresRecovery = session.status != "ready",
                    )
                }
            }

            items(directorReceipts, key = { "director-${it.operationId}" }) { receipt ->
                DirectorReceiptCard(receipt = receipt)
            }

            items(
                items = streamingReplies,
                key = { "stream-${it.index}" },
            ) { item ->
                TranscriptBubble(
                    item = TranscriptItemDto(
                        speaker = item.speaker.ifBlank { "生成中" },
                        message = item.text,
                        role = item.role,
                    ),
                    avatarBytes = avatarBytes,
                    displayPreferences = displayPreferences,
                    actionsEnabled = false,
                    streaming = true,
                    onCopy = {},
                    onRegenerate = {},
                    onBranch = {},
                )
            }

        }

        if (!isAtBottom || unseenMessages > 0) {
            ExtendedFloatingActionButton(
                onClick = {
                    followNewMessages = true
                    unseenMessages = 0
                    scrollScope.launch {
                        listState.scrollToBottom(lastContentIndex, animated = true)
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                icon = {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                },
                text = {
                    Text(
                        if (unseenMessages > 0) "$unseenMessages 条新消息" else "回到底部",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
    }
}

@Composable
private fun PendingUserMessageBubble(
    pending: PendingUserMessage,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onReconcile: () -> Unit,
    onRecover: () -> Unit,
    requiresRecovery: Boolean,
) {
    if (pending.messageKind == "plot") {
        PendingDirectorInstructionCard(
            pending = pending,
            onRetry = onRetry,
            onEdit = onEdit,
            onReconcile = onReconcile,
            onRecover = onRecover,
            requiresRecovery = requiresRecovery,
        )
        return
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 340.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 18.dp,
                bottomEnd = 4.dp,
            ),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(
                    "你",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    fontWeight = FontWeight.SemiBold,
                )
                ParentheticalMessageText(
                    text = pending.message,
                    modifier = Modifier.padding(top = 3.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    baseColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                when (pending.status) {
                    PendingUserMessageStatus.Sending -> Row(
                        modifier = Modifier.padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            pending.statusText.ifBlank { "正在发送" },
                            modifier = Modifier.padding(start = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                        )
                    }

                    PendingUserMessageStatus.Failed -> {
                        Text(
                            pending.statusText.ifBlank { "发送失败" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (requiresRecovery) {
                            TextButton(
                                onClick = onRecover,
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("恢复会话") }
                        } else {
                            Row(
                                modifier = Modifier.align(Alignment.End),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = onEdit) { Text("编辑") }
                                if (pending.retryable) {
                                    TextButton(onClick = onRetry) { Text("重试") }
                                }
                            }
                        }
                    }

                    PendingUserMessageStatus.OutcomeUnknown -> {
                        Text(
                            pending.statusText.ifBlank { "连接中断，正在核对结果" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = if (requiresRecovery) onRecover else onReconcile,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(if (requiresRecovery) "恢复会话" else "核对结果") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DirectorReceiptCard(receipt: DirectorReceipt) {
    var expanded by remember(receipt.operationId) { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .clickable { expanded = !expanded },
            color = androidx.compose.ui.graphics.Color.Transparent,
            shape = RoundedCornerShape(0.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "导演指令已应用",
                        modifier = Modifier.padding(start = 5.dp).weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起指令" else "展开指令",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (expanded) {
                    ParentheticalMessageText(
                        text = receipt.message,
                        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        baseColor = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                    Text(
                        "作为下一拍的引导，不写入角色台词",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingDirectorInstructionCard(
    pending: PendingUserMessage,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onReconcile: () -> Unit,
    onRecover: () -> Unit,
    requiresRecovery: Boolean,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.widthIn(max = 520.dp),
            color = androidx.compose.ui.graphics.Color.Transparent,
            shape = RoundedCornerShape(0.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.material3.HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "导演指令",
                        modifier = Modifier.padding(start = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                ParentheticalMessageText(
                    text = pending.message,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    baseColor = MaterialTheme.colorScheme.onSurface,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                when (pending.status) {
                    PendingUserMessageStatus.Sending -> Row(
                        modifier = Modifier.padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            pending.statusText.ifBlank { "正在安排下一拍" },
                            modifier = Modifier.padding(start = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    PendingUserMessageStatus.Failed -> {
                        Text(
                            pending.statusText.ifBlank { "指令未生效" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (requiresRecovery) {
                            TextButton(onClick = onRecover, modifier = Modifier.align(Alignment.End)) {
                                Text("恢复会话")
                            }
                        } else {
                            Row(modifier = Modifier.align(Alignment.End)) {
                                TextButton(onClick = onEdit) { Text("编辑") }
                                if (pending.retryable) TextButton(onClick = onRetry) { Text("重试") }
                            }
                        }
                    }

                    PendingUserMessageStatus.OutcomeUnknown -> {
                        Text(
                            pending.statusText.ifBlank { "连接中断，正在核对结果" },
                            modifier = Modifier.padding(top = 7.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(
                            onClick = if (requiresRecovery) onRecover else onReconcile,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text(if (requiresRecovery) "恢复会话" else "核对结果") }
                    }
                }
            }
        }
    }
}

private suspend fun LazyListState.scrollToBottom(itemIndex: Int, animated: Boolean) {
    val targetIndex = itemIndex.coerceAtLeast(0)
    var target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (target == null) {
        if (animated) {
            animateScrollToItem(targetIndex)
        } else {
            scrollToItem(targetIndex)
        }
        target = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    }
    val overflow = target?.let {
        it.offset + it.size - layoutInfo.viewportEndOffset
    } ?: return
    if (overflow > 0) {
        if (animated) {
            animateScrollBy(overflow.toFloat())
        } else {
            scrollBy(overflow.toFloat())
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun TranscriptBubble(
    item: TranscriptItemDto,
    avatarBytes: Map<String, ByteArray>,
    displayPreferences: ChatDisplayPreferences,
    actionsEnabled: Boolean,
    streaming: Boolean = false,
    canRegenerate: Boolean = false,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
) {
    val isUser = item.role == "user"
    val isScene = item.role == "scene"
    val isDirector = item.role == "director"
    val canBranch = item.turnId.isNotBlank()
    var menuExpanded by remember(item.turnId, item.message) { mutableStateOf(false) }
    val verticalPadding = if (displayPreferences.compactMode) 6.dp else 9.dp
    val messageStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = MaterialTheme.typography.bodyMedium.fontSize * displayPreferences.fontSize.scale,
    )

    if (isScene) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!streaming) menuExpanded = true },
                        ),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = verticalPadding)) {
                        Text(
                            text = "旁白${item.speaker.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier.padding(top = 3.dp),
                            style = messageStyle.copy(fontStyle = FontStyle.Italic),
                            baseColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                MessageContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actionsEnabled = actionsEnabled,
                    canRegenerate = false,
                    canBranch = canBranch,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onBranch = onBranch,
                )
            }
        }
        return
    }

    if (isDirector) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box {
                Surface(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { if (!streaming) menuExpanded = true },
                        ),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(0.dp),
                ) {
                    Column(
                        Modifier.padding(horizontal = 14.dp, vertical = verticalPadding),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        androidx.compose.material3.HorizontalDivider()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "导演${item.speaker.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}",
                                modifier = Modifier.padding(start = 5.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        ParentheticalMessageText(
                            text = item.message,
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
                            style = messageStyle,
                            baseColor = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
                MessageContextMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actionsEnabled = actionsEnabled,
                    canRegenerate = false,
                    canBranch = canBranch,
                    onCopy = onCopy,
                    onRegenerate = onRegenerate,
                    onBranch = onBranch,
                )
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            ChatPersonaAvatar(
                bytes = avatarBytes[item.speaker],
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Column {
            if (!isUser) {
                Text(
                    text = item.speaker.ifBlank { "人物" },
                    modifier = Modifier.padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        Box {
            Surface(
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (!streaming) menuExpanded = true },
                    ),
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
                Column(Modifier.padding(horizontal = 12.dp, vertical = verticalPadding)) {
                    if (isUser) Text(
                        text = "你 · ${item.speaker}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                        fontWeight = FontWeight.SemiBold,
                    )
                    ParentheticalMessageText(
                        text = item.message,
                        modifier = Modifier.padding(top = 3.dp),
                        style = messageStyle,
                        baseColor = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (streaming) {
                        Text(
                            "正在生成",
                            modifier = Modifier.padding(top = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            MessageContextMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                actionsEnabled = actionsEnabled,
                canRegenerate = canRegenerate,
                canBranch = canBranch,
                onCopy = onCopy,
                onRegenerate = onRegenerate,
                onBranch = onBranch,
            )
        }
        }
    }
}

@Composable
private fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    actionsEnabled: Boolean,
    canRegenerate: Boolean,
    canBranch: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    onBranch: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.inverseSurface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageContextAction(
                label = "复制",
                enabled = true,
                onClick = {
                    onDismiss()
                    onCopy()
                },
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (canRegenerate) {
                MessageContextAction(
                    label = "重新生成",
                    enabled = actionsEnabled,
                    onClick = {
                        onDismiss()
                        onRegenerate()
                    },
                ) {
                    Icon(
                        Icons.Default.Replay,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (canBranch) {
                MessageContextAction(
                    label = "从此处分支",
                    enabled = actionsEnabled,
                    onClick = {
                        onDismiss()
                        onBranch()
                    },
                ) {
                    Icon(
                        Icons.Default.CallSplit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageContextAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.inverseOnSurface
    Column(
        modifier = Modifier
            .widthIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides contentColor,
        ) {
            icon()
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = contentColor.copy(alpha = if (enabled) 1f else 0.38f),
            maxLines = 1,
        )
    }
}

private val parentheticalPattern = Regex("[（(][^（）()\\n]*[）)]")

@Composable
private fun ParentheticalMessageText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle,
    baseColor: androidx.compose.ui.graphics.Color,
    textAlign: androidx.compose.ui.text.style.TextAlign = androidx.compose.ui.text.style.TextAlign.Start,
) {
    val asideColor = MaterialTheme.colorScheme.secondary
    val annotated = buildAnnotatedString {
        var cursor = 0
        parentheticalPattern.findAll(text).forEach { match ->
            append(text.substring(cursor, match.range.first))
            pushStyle(SpanStyle(color = asideColor, fontStyle = FontStyle.Italic))
            append(match.value)
            pop()
            cursor = match.range.last + 1
        }
        append(text.substring(cursor))
    }
    Text(text = annotated, modifier = modifier, style = style, color = baseColor, textAlign = textAlign)
}

@Composable
private fun ChatComposer(
    state: ChatUiState,
    onDraftChange: (String) -> Unit,
    onMessageKindChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleContinuousObserve: () -> Unit,
    onRecover: () -> Unit,
    onReconcile: () -> Unit,
    onRetry: () -> Unit,
    onDiscardRetry: () -> Unit,
) {
    var mentionsOpen by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    var draftValue by rememberSaveable(state.sessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(state.draft))
    }
    val participants = remember(state.session) {
        val session = state.session ?: return@remember emptyList()
        val present = session.sceneProgress["present_participants"]
            ?.let { value ->
                runCatching {
                    value.jsonArray.mapNotNull { item ->
                        item.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
                    }
                }.getOrNull()
            }
            .orEmpty()
        (present.ifEmpty { session.participants })
            .filter { it.isNotBlank() && it != session.controlledCharacter }
            .distinct()
    }
    val inputEnabled = !state.sending && !state.recovering &&
        !state.sendOutcomeUnknown && state.failedOperationId.isBlank()
    val mentionColor = MaterialTheme.colorScheme.primary
    val mentionTransformation = remember(participants, mentionColor) {
        MentionVisualTransformation(participants, mentionColor)
    }
    LaunchedEffect(state.draft) {
        if (state.draft != draftValue.text) {
            draftValue = TextFieldValue(state.draft, TextRange(state.draft.length))
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                horizontal = 12.dp,
                vertical = if (state.chatDisplay.compactMode) 6.dp else 10.dp,
            ),
    ) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messageKindOptions, key = MessageKindOption::value) { option ->
                    FilterChip(
                        selected = state.messageKind == option.value,
                        onClick = { onMessageKindChange(option.value) },
                        enabled = inputEnabled,
                        label = { Text(option.label) },
                    )
                }
                if (participants.isNotEmpty()) {
                    item {
                        AssistChip(
                            onClick = { mentionsOpen = !mentionsOpen },
                            enabled = inputEnabled,
                            label = { Text("@") },
                        )
                    }
                }
                if (state.session?.mode == "observe") {
                    item {
                        FilterChip(
                            selected = state.continuousObserveEnabled,
                            onClick = onToggleContinuousObserve,
                            enabled = state.canToggleContinuousObserve,
                            leadingIcon = {
                                Icon(
                                    if (state.continuousObserveEnabled) {
                                        Icons.Default.Pause
                                    } else {
                                        Icons.Default.PlayArrow
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            label = { Text("旁观") },
                        )
                    }
                }
            }

            if (state.messageKind == "plot") {
                Text(
                    "导演指令会引导下一拍，不会写成你的角色台词。",
                    modifier = Modifier.padding(bottom = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (participants.isNotEmpty()) {
                if (mentionsOpen) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(bottom = 5.dp),
                    ) {
                        items(participants, key = { it }) { participant ->
                            AssistChip(
                                onClick = {
                                    val next = draftValue.insertMention(participant)
                                    draftValue = next
                                    onDraftChange(next.text)
                                    mentionsOpen = false
                                },
                                enabled = inputEnabled,
                                label = { Text(participant) },
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draftValue,
                    onValueChange = { next ->
                        val resolved = normalizeMentionDeletion(
                            previous = draftValue,
                            next = next,
                            participants = participants,
                        )
                        draftValue = resolved
                        onDraftChange(resolved.text)
                    },
                    modifier = Modifier.weight(1f),
                    enabled = inputEnabled,
                    visualTransformation = mentionTransformation,
                    placeholder = {
                        Text(
                            when (state.messageKind) {
                                "narration" -> "描述一个场景变化…"
                                "plot" -> "交代希望下一拍怎样发展…"
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

            if (state.pendingUserMessage == null && state.failedOperationId.isNotBlank() && !state.sending) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        when {
                            state.sendOutcomeUnknown ->
                                "连接中断，先核对本地结果；重试会沿用本次发送标识，不会重复生成。"
                            state.session?.status != "ready" ->
                                "回复在本机仍处于待处理状态，可重试同一次生成或恢复会话。"
                            else ->
                                "本次生成失败。重试会沿用原发送标识，也可以保留输入后修改。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        if (state.sendOutcomeUnknown || state.session?.status != "ready") {
                            TextButton(
                                onClick = if (state.session?.status == "ready") onReconcile else onRecover,
                                enabled = !state.recovering,
                            ) {
                                Text(if (state.session?.status == "ready") "核对状态" else "恢复会话")
                            }
                        } else {
                            TextButton(onClick = onDiscardRetry, enabled = !state.recovering) {
                                Text("编辑消息")
                            }
                        }
                        Button(onClick = onRetry, enabled = !state.recovering) {
                            Icon(
                                Icons.Default.Replay,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp),
                            )
                            Text("重试", modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                }
            } else if (state.pendingUserMessage == null && state.sendOutcomeUnknown) {
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

@Composable
private fun ChatPersonaAvatar(bytes: ByteArray?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clip(androidx.compose.foundation.shape.CircleShape),
        shape = androidx.compose.foundation.shape.CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        val bitmap = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bitmap == null) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Person, contentDescription = "人物头像")
            }
        } else {
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "人物头像",
                modifier = Modifier.fillMaxSize().clip(androidx.compose.foundation.shape.CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        }
        }
    }

private class MentionVisualTransformation(
    private val participants: List<String>,
    private val mentionColor: androidx.compose.ui.graphics.Color,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val annotated = buildAnnotatedString {
            append(text)
            text.text.mentionRanges(participants).forEach { range ->
                addStyle(
                    SpanStyle(color = mentionColor, fontWeight = FontWeight.SemiBold),
                    range.first,
                    range.last + 1,
                )
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

internal fun TextFieldValue.insertMention(participant: String): TextFieldValue {
    val name = participant.trim()
    if (name.isBlank() || text.mentionRanges(listOf(name)).isNotEmpty()) return this

    val start = selection.min
    val end = selection.max
    val before = text.substring(0, start)
    val after = text.substring(end)
    val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
    val suffix = if (after.isEmpty() || !after.first().isWhitespace()) " " else ""
    val inserted = "$prefix@$name$suffix"
    val nextText = before + inserted + after
    val cursor = before.length + inserted.length
    return TextFieldValue(nextText, TextRange(cursor))
}

internal fun normalizeMentionDeletion(
    previous: TextFieldValue,
    next: TextFieldValue,
    participants: List<String>,
): TextFieldValue {
    if (next.text.length >= previous.text.length) return next

    val change = textDeletionRange(previous.text, next.text) ?: return next
    val affected = previous.text.mentionRanges(participants).filter { mention ->
        (mention.first < change.end && mention.last + 1 > change.start) ||
            (change.start == mention.last + 1 &&
                change.end == mention.last + 2 &&
                previous.text.getOrNull(mention.last + 1)?.isWhitespace() == true)
    }
    if (affected.isEmpty()) return next

    val start = minOf(change.start, affected.minOf { it.first })
    val end = maxOf(
        change.end,
        affected.maxOf { mention ->
            val afterMention = mention.last + 1
            if (previous.text.getOrNull(afterMention)?.isWhitespace() == true) {
                afterMention + 1
            } else {
                afterMention
            }
        },
    )
    return TextFieldValue(
        text = previous.text.removeRange(start, end),
        selection = TextRange(start),
    )
}

internal fun String.mentionRanges(participants: List<String>): List<IntRange> = participants
    .asSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinct()
    .flatMap { name ->
        Regex("(?<!\\S)@${Regex.escape(name)}(?=\\s|$)")
            .findAll(this)
            .map { it.range }
    }
    .distinct()
    .sortedBy { it.first }
    .toList()

private fun textDeletionRange(previous: String, next: String): TextRange? {
    var start = 0
    val sharedLength = minOf(previous.length, next.length)
    while (start < sharedLength && previous[start] == next[start]) start += 1

    var suffix = 0
    while (
        suffix < previous.length - start &&
        suffix < next.length - start &&
        previous[previous.length - suffix - 1] == next[next.length - suffix - 1]
    ) {
        suffix += 1
    }
    val end = previous.length - suffix
    return if (start < end) TextRange(start, end) else null
}

private fun String.chineseMode(): String = when (this) {
    "act" -> "扮演人物"
    "insert" -> "自设入场"
    else -> "旁观群聊"
}
