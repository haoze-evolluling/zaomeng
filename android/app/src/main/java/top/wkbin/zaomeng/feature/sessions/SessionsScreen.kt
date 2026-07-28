package top.wkbin.zaomeng.feature.sessions

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.ui.format.toLocalDateTimeDisplay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private data class DialogueModeOption(
    val value: String,
    val label: String,
    val description: String,
)

private val dialogueModeOptions = listOf(
    DialogueModeOption("observe", "旁观", "让人物自己推进故事"),
    DialogueModeOption("act", "扮演", "代入已有故事人物"),
    DialogueModeOption("insert", "入场", "以自设身份进入场景"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    viewModel: SessionsViewModel,
    runId: String? = null,
    onBack: () -> Unit,
    onOpenChat: (runId: String, sessionId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var pendingDeletion by remember { mutableStateOf<DialogueSessionDto?>(null) }

    LaunchedEffect(runId) {
        viewModel.load(runId)
    }
    LifecycleResumeEffect(runId) {
        viewModel.onScreenResumed()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.createdSession?.sessionId) {
        state.createdSession?.let { session ->
            viewModel.consumeCreatedSession()
            onOpenChat(session.runId, session.sessionId)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (runId.isNullOrBlank()) "全部会话" else "书中会话")
                        if (!runId.isNullOrBlank()) {
                            Text(
                                text = state.runs.firstOrNull { it.runId == runId }?.title.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
                    IconButton(
                        onClick = viewModel::refresh,
                        enabled = !state.loading && !state.refreshing && !state.creating &&
                            state.deletingSessionKeys.isEmpty(),
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新会话")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::openCreateDialog,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("开始新会话") },
            )
        },
    ) { innerPadding ->
        when {
            state.loading -> LoadingSessions(Modifier.padding(innerPadding))
            else -> SessionsContent(
                state = state,
                onOpenChat = onOpenChat,
                onDelete = { pendingDeletion = it },
                onDismissError = viewModel::clearError,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }

    if (state.createDialogVisible) {
        NewSessionDialog(
            state = state,
            onDismiss = viewModel::closeCreateDialog,
            onSelectRun = viewModel::selectRun,
            onSelectMode = viewModel::selectMode,
            onToggleParticipant = viewModel::toggleParticipant,
            onSelectControlled = viewModel::selectControlledCharacter,
            onSelfNameChange = viewModel::updateSelfName,
            onSelfIdentityChange = viewModel::updateSelfIdentity,
            onSelfStyleChange = viewModel::updateSelfStyle,
            onSelectOpeningPreset = viewModel::selectOpeningPreset,
            onSelectSceneCard = viewModel::selectSceneCard,
            onRecommendSceneCard = viewModel::recommendSceneCard,
            onSelectSelfCard = viewModel::selectSelfCard,
            onCreate = viewModel::createSession,
        )
    }

    pendingDeletion?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("删除这段会话？") },
            text = { Text("聊天记录会从这台手机上永久删除，人物资料和书卷不会受影响。") },
            confirmButton = {
                Button(
                    onClick = {
                        pendingDeletion = null
                        viewModel.deleteSession(session)
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun LoadingSessions(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("正在整理本机会话…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionsContent(
    state: SessionsUiState,
    onOpenChat: (String, String) -> Unit,
    onDelete: (DialogueSessionDto) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.error.isNotBlank()) {
            item {
                ErrorCard(message = state.error, onDismiss = onDismissError)
            }
        }

        if (state.sessions.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Chat,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("还没有聊天记录", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "选择一本已蒸馏的书，就能让人物在新的场景里开口。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.sessions, key = DialogueSessionDto::key) { session ->
                SessionCard(
                    session = session,
                    run = state.runs.firstOrNull { it.runId == session.runId },
                    deleting = session.key in state.deletingSessionKeys,
                    onOpen = { onOpenChat(session.runId, session.sessionId) },
                    onDelete = { onDelete(session) },
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: DialogueSessionDto,
    run: RunManifestDto?,
    deleting: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = run?.title ?: session.novelId.ifBlank { "未命名书卷" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${session.mode.chineseMode()} · ${session.participants.joinToString("、").ifBlank { "未记录人物" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    color = if (session.status == "ready") {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    },
                    shape = MaterialTheme.shapes.extraLarge,
                ) {
                    Text(
                        text = if (session.status == "ready") "可继续" else "待处理",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            Text(
                text = session.lastEntryPreview.ifBlank { "这一幕刚刚开始，进去说第一句话吧。" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.updatedAt.toLocalDateTimeDisplay(),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onDelete, enabled = !deleting) {
                    if (deleting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Delete, contentDescription = "删除会话")
                    }
                }
                Button(onClick = onOpen, enabled = !deleting) {
                    Text("继续聊天")
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    }
}

@Composable
private fun NewSessionDialog(
    state: SessionsUiState,
    onDismiss: () -> Unit,
    onSelectRun: (String) -> Unit,
    onSelectMode: (String) -> Unit,
    onToggleParticipant: (String) -> Unit,
    onSelectControlled: (String) -> Unit,
    onSelfNameChange: (String) -> Unit,
    onSelfIdentityChange: (String) -> Unit,
    onSelfStyleChange: (String) -> Unit,
    onSelectOpeningPreset: (String) -> Unit,
    onSelectSceneCard: (String) -> Unit,
    onRecommendSceneCard: () -> Unit,
    onSelectSelfCard: (String) -> Unit,
    onCreate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开始一段新会话") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Text("选择书卷", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    RunPicker(
                        runs = state.runs.filter { it.availableCharacters.isNotEmpty() },
                        selectedRunId = state.draft.runId,
                        enabled = !state.creating && state.scopedRunId == null,
                        onSelect = onSelectRun,
                    )
                }

                if (state.openingPresets.isNotEmpty()) {
                    item {
                        ReusableCardPicker(
                            label = "开场预设",
                            cards = state.openingPresets,
                            selectedCardId = state.draft.openingPresetId,
                            titleKey = "title",
                            noneLabel = "不使用预设",
                            enabled = !state.creating,
                            onSelect = onSelectOpeningPreset,
                        )
                    }
                }

                item {
                    Text("你的身份", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(dialogueModeOptions, key = DialogueModeOption::value) { option ->
                            FilterChip(
                                selected = state.draft.mode == option.value,
                                onClick = { onSelectMode(option.value) },
                                enabled = !state.creating,
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Text(
                        dialogueModeOptions.first { it.value == state.draft.mode }.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                item {
                    Text("出场人物", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Column {
                        state.availableCharacters.forEach { character ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.creating) { onToggleParticipant(character) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = character in state.draft.participants,
                                    onCheckedChange = null,
                                    enabled = !state.creating,
                                )
                                Text(character)
                            }
                        }
                    }
                }

                if (state.draft.mode == "act") {
                    item {
                        Text("你要扮演谁", style = MaterialTheme.typography.labelLarge)
                        state.draft.participants.forEach { character ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !state.creating) { onSelectControlled(character) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.draft.controlledCharacter == character,
                                    onClick = null,
                                    enabled = !state.creating,
                                )
                                Text(character)
                            }
                        }
                    }
                }

                item {
                    ReusableCardPicker(
                        label = "场景卡",
                        cards = state.sceneCards,
                        selectedCardId = state.draft.sceneCardId,
                        titleKey = "title",
                        noneLabel = "不指定场景",
                        enabled = !state.creating,
                        onSelect = onSelectSceneCard,
                    )
                    OutlinedButton(
                        onClick = onRecommendSceneCard,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        enabled = !state.creating && !state.recommendingScene &&
                            state.draft.participants.isNotEmpty() && state.sceneCards.isNotEmpty(),
                    ) {
                        if (state.recommendingScene) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                        Text(
                            if (state.recommendingScene) "正在推荐…" else "按人物与模式推荐场景",
                            modifier = Modifier.padding(start = if (state.recommendingScene) 8.dp else 0.dp),
                        )
                    }
                }

                if (state.draft.mode == "insert") {
                    item {
                        ReusableCardPicker(
                            label = "自设卡",
                            cards = state.selfCards,
                            selectedCardId = state.draft.selfCardId,
                            titleKey = "display_name",
                            noneLabel = "临时填写身份",
                            enabled = !state.creating,
                            onSelect = onSelectSelfCard,
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = state.draft.selfName,
                            onValueChange = onSelfNameChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = { Text("你的名字") },
                            placeholder = { Text("例如：沈照") },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.draft.selfIdentity,
                            onValueChange = onSelfIdentityChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = { Text("场景身份") },
                            placeholder = { Text("例如：刚到府中的远房客人") },
                            minLines = 2,
                            maxLines = 3,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = state.draft.selfStyle,
                            onValueChange = onSelfStyleChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.creating,
                            label = { Text("互动风格") },
                            placeholder = { Text("例如：克制、观察为主，熟悉后更直接") },
                            minLines = 2,
                            maxLines = 3,
                        )
                    }
                }

                if (state.error.isNotBlank()) {
                    item {
                        Text(
                            text = state.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (state.creating) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                "正在让人物进入场景，模型生成开场可能需要一会儿…",
                                modifier = Modifier.padding(start = 10.dp),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onCreate,
                enabled = state.canCreate && !state.creating,
            ) {
                Text(if (state.creating) "创建中" else "进入场景")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.creating) { Text("取消") }
        },
    )
}

@Composable
private fun ReusableCardPicker(
    label: String,
    cards: List<ReusableCardDto>,
    selectedCardId: String,
    titleKey: String,
    noneLabel: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = cards.firstOrNull { it.cardId == selectedCardId }
    val selectedTitle = selected?.preview?.get(titleKey)?.jsonPrimitive?.contentOrNull
        ?: selected?.fields?.get(titleKey)?.jsonPrimitive?.contentOrNull
    Text(label, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
        ) {
            Text(
                selectedTitle?.takeIf(String::isNotBlank) ?: noneLabel,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(noneLabel) },
                onClick = {
                    expanded = false
                    onSelect("")
                },
            )
            cards.forEach { card ->
                val title = card.preview[titleKey]?.jsonPrimitive?.contentOrNull
                    ?: card.fields[titleKey]?.jsonPrimitive?.contentOrNull
                    ?: card.cardId
                DropdownMenuItem(
                    text = { Text(title) },
                    onClick = {
                        expanded = false
                        onSelect(card.cardId)
                    },
                )
            }
        }
    }
}

@Composable
private fun RunPicker(
    runs: List<RunManifestDto>,
    selectedRunId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = runs.firstOrNull { it.runId == selectedRunId }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled && runs.isNotEmpty(),
        ) {
            Text(
                text = selected?.title ?: "没有可用书卷",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            runs.forEach { run ->
                DropdownMenuItem(
                    text = { Text("${run.title} · ${run.availableCharacters.size} 人") },
                    onClick = {
                        expanded = false
                        onSelect(run.runId)
                    },
                )
            }
        }
    }
}

private fun String.chineseMode(): String = when (this) {
    "act" -> "扮演人物"
    "insert" -> "自设入场"
    else -> "旁观群聊"
}
