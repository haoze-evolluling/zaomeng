package top.wkbin.zaomeng.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ReusableCardKind
import top.wkbin.zaomeng.data.ApiRequestException
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueStreamEvent
import top.wkbin.zaomeng.data.api.ChatSearchResultDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import top.wkbin.zaomeng.data.preferences.ChatDisplayPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class ChatToolOption(
    val label: String,
    val value: String,
    val description: String = "",
    val messageKind: String = "plot",
    val suggestionDirection: String = "",
)

data class StreamingReplyPart(
    val index: Int,
    val speaker: String = "",
    val role: String = "character",
    val text: String = "",
)

data class ChatUiState(
    val runId: String = "",
    val sessionId: String = "",
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val sending: Boolean = false,
    val recovering: Boolean = false,
    val sendOutcomeUnknown: Boolean = false,
    val sendBaselineTranscript: List<TranscriptItemDto>? = null,
    val failedOperationId: String = "",
    val failedMessage: String = "",
    val failedMessageKind: String = "dialogue",
    val streamStatus: String = "",
    val streamingReplies: List<StreamingReplyPart> = emptyList(),
    val toolBusy: String = "",
    val session: DialogueSessionDto? = null,
    val sceneCards: List<ReusableCardDto> = emptyList(),
    val toolOptions: List<ChatToolOption> = emptyList(),
    val toolOptionsTitle: String = "",
    val recommendedSceneCardId: String = "",
    val recommendedTransition: String = "",
    val navigationSession: DialogueSessionDto? = null,
    val memorySaveRevision: Long = 0,
    val draft: String = "",
    val messageKind: String = "dialogue",
    val searchQuery: String = "",
    val searching: Boolean = false,
    val searchResults: List<ChatSearchResultDto> = emptyList(),
    val chatDisplay: ChatDisplayPreferences = ChatDisplayPreferences(),
    val notice: String = "",
    val error: String = "",
) {
    val canSend: Boolean
        get() = !loading &&
            !refreshing &&
            !sending &&
            !recovering &&
            !sendOutcomeUnknown &&
            failedOperationId.isBlank() &&
            toolBusy.isBlank() &&
            session?.status == "ready" &&
            draft.isNotBlank()

    val canUseTools: Boolean
        get() = !loading &&
            !refreshing &&
            !sending &&
            !recovering &&
            !sendOutcomeUnknown &&
            failedOperationId.isBlank() &&
            toolBusy.isBlank() &&
            session?.status == "ready"

    val canRefresh: Boolean
        get() = !loading &&
            !refreshing &&
            !sending &&
            !recovering &&
            toolBusy.isBlank()
}

class ChatViewModel(
    private val repository: ZaomengRepository,
    preferencesRepository: AppPreferencesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var loadRequestId = 0L
    private var toolJob: Job? = null
    private var nextToolRequestId = 0L
    private var activeToolRequest: ToolRequest? = null
    private var sendJob: Job? = null
    private var searchJob: Job? = null
    private var recoveryJob: Job? = null
    private var nextRecoveryRequestId = 0L

    init {
        viewModelScope.launch {
            preferencesRepository.chatDisplayPreferences.collect { preferences ->
                mutableState.update { it.copy(chatDisplay = preferences) }
            }
        }
    }

    private data class ToolRequest(
        val id: Long,
        val snapshot: ChatUiState,
    ) {
        val runId: String get() = snapshot.runId
        val sessionId: String get() = snapshot.sessionId
    }

    fun load(runId: String, sessionId: String, force: Boolean = false) {
        val normalizedRunId = runId.trim()
        val normalizedSessionId = sessionId.trim()
        val current = state.value
        if (
            !force &&
            !current.loading &&
            current.runId == normalizedRunId &&
            current.sessionId == normalizedSessionId
        ) {
            return
        }
        if (normalizedRunId.isBlank() || normalizedSessionId.isBlank()) {
            cancelLoadRequest()
            cancelToolRequest()
            sendJob?.cancel()
            searchJob?.cancel()
            cancelRecoveryRequest()
            mutableState.value = ChatUiState(
                runId = normalizedRunId,
                sessionId = normalizedSessionId,
                loading = false,
                chatDisplay = current.chatDisplay,
                error = "会话地址不完整，无法打开聊天。",
            )
            return
        }

        cancelLoadRequest()
        cancelToolRequest()
        val targetChanged = current.runId != normalizedRunId ||
            current.sessionId != normalizedSessionId
        if (targetChanged) {
            sendJob?.cancel()
            searchJob?.cancel()
            cancelRecoveryRequest()
        }
        val requestId = ++loadRequestId
        mutableState.update {
            if (targetChanged) {
                ChatUiState(
                    runId = normalizedRunId,
                    sessionId = normalizedSessionId,
                    loading = true,
                    chatDisplay = current.chatDisplay,
                )
            } else {
                it.copy(
                    loading = it.session == null,
                    refreshing = it.session != null,
                    toolBusy = "",
                    error = "",
                )
            }
        }
        loadJob = viewModelScope.launch {
            try {
                val session = repository.getSession(normalizedRunId, normalizedSessionId)
                updateLoadState(requestId, normalizedRunId, normalizedSessionId) {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        session = session,
                        error = "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateLoadState(requestId, normalizedRunId, normalizedSessionId) {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.readableMessage("会话加载失败，请稍后重试。"),
                    )
                }
            }
        }
    }

    fun refresh() {
        val current = state.value
        if (current.canRefresh) {
            if (current.sendOutcomeUnknown) {
                reconcileUnknownSend()
            } else {
                load(current.runId, current.sessionId, force = true)
            }
        }
    }

    fun updateDraft(value: String) {
        if (state.value.sendOutcomeUnknown || state.value.failedOperationId.isNotBlank()) return
        mutableState.update { it.copy(draft = value, error = "", notice = "") }
    }

    fun updateSearchQuery(value: String) {
        val query = value.take(120)
        val snapshot = state.value
        searchJob?.cancel()
        mutableState.update {
            it.copy(
                searchQuery = query,
                searching = query.isNotBlank(),
                searchResults = if (query.isBlank()) emptyList() else it.searchResults,
            )
        }
        if (query.isBlank() || snapshot.runId.isBlank() || snapshot.sessionId.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(250)
            try {
                val results = repository.searchSession(snapshot.runId, snapshot.sessionId, query)
                if (state.value.searchQuery == query) {
                    mutableState.update { it.copy(searching = false, searchResults = results) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (state.value.searchQuery == query) {
                    val fallback = snapshot.session?.transcript.orEmpty()
                        .filter { item ->
                            item.message.contains(query, ignoreCase = true) ||
                                item.speaker.contains(query, ignoreCase = true)
                        }
                        .map { item ->
                            ChatSearchResultDto(
                                speaker = item.speaker,
                                message = item.message,
                                role = item.role,
                                turnId = item.turnId,
                                timestamp = item.timestamp,
                            )
                        }
                    mutableState.update {
                        it.copy(
                            searching = false,
                            searchResults = fallback,
                            error = if (fallback.isEmpty()) {
                                error.readableMessage("聊天记录搜索失败，请稍后重试。")
                            } else {
                                ""
                            },
                        )
                    }
                }
            }
        }
    }

    fun selectMessageKind(kind: String) {
        val current = state.value
        if (
            kind !in messageKinds || current.sending || current.recovering ||
            current.sendOutcomeUnknown || current.failedOperationId.isNotBlank() ||
            current.toolBusy.isNotBlank()
        ) return
        mutableState.update { it.copy(messageKind = kind, error = "") }
    }

    fun send() {
        val snapshot = state.value
        if (
            snapshot.loading || snapshot.refreshing || snapshot.sending || snapshot.recovering ||
            snapshot.toolBusy.isNotBlank()
        ) return
        if (snapshot.sendOutcomeUnknown) {
            mutableState.update { it.copy(error = "先核对上一次发送结果，再继续聊天。") }
            return
        }
        val message = snapshot.draft.trim()
        if (message.isBlank()) {
            mutableState.update { it.copy(error = "先写点什么，再把这一句送进故事。") }
            return
        }
        if (snapshot.session?.status != "ready") {
            mutableState.update {
                it.copy(error = "当前会话还有一轮待处理，请刷新后再发送。")
            }
            return
        }

        startStreamingSend(
            snapshot = snapshot,
            message = message,
            messageKind = snapshot.messageKind,
            operationId = UUID.randomUUID().toString(),
        )
    }

    fun retryLastSend() {
        val snapshot = state.value
        if (
            snapshot.failedOperationId.isBlank() || snapshot.failedMessage.isBlank() ||
            snapshot.sending || snapshot.recovering
        ) return
        startStreamingSend(
            snapshot = snapshot,
            message = snapshot.failedMessage,
            messageKind = snapshot.failedMessageKind,
            operationId = snapshot.failedOperationId,
        )
    }

    private fun startStreamingSend(
        snapshot: ChatUiState,
        message: String,
        messageKind: String,
        operationId: String,
    ) {
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            mutableState.update { current ->
                if (
                    current.runId != snapshot.runId ||
                    current.sessionId != snapshot.sessionId
                ) {
                    current
                } else current.copy(
                    sending = true,
                    error = "",
                    streamStatus = "正在连接模型…",
                    streamingReplies = emptyList(),
                    sendOutcomeUnknown = false,
                    failedOperationId = operationId,
                    failedMessage = message,
                    failedMessageKind = messageKind,
                    sendBaselineTranscript = snapshot.session?.transcript,
                )
            }
            if (
                state.value.runId != snapshot.runId ||
                state.value.sessionId != snapshot.sessionId ||
                state.value.failedOperationId != operationId
            ) {
                return@launch
            }
            try {
                repository.streamReply(
                    runId = snapshot.runId,
                    sessionId = snapshot.sessionId,
                    message = message,
                    messageKind = messageKind,
                    operationId = operationId,
                ).collect { event ->
                    when (event) {
                        is DialogueStreamEvent.Status -> updateSendState(snapshot, operationId) {
                            it.copy(streamStatus = event.message.ifBlank { event.phase })
                        }
                        is DialogueStreamEvent.Delta -> updateSendState(snapshot, operationId) { current ->
                            val existing = current.streamingReplies
                                .firstOrNull { it.index == event.index }
                            val updated = StreamingReplyPart(
                                index = event.index,
                                speaker = event.speaker.ifBlank { existing?.speaker.orEmpty() },
                                role = event.role.ifBlank { existing?.role ?: "character" },
                                text = existing?.text.orEmpty() + event.text,
                            )
                            current.copy(
                                streamStatus = "回复正在生成…",
                                streamingReplies = current.streamingReplies
                                    .filterNot { it.index == event.index }
                                    .plus(updated)
                                    .sortedBy(StreamingReplyPart::index),
                            )
                        }
                        is DialogueStreamEvent.Reset -> updateSendState(snapshot, operationId) {
                            it.copy(
                                streamStatus = event.message,
                                streamingReplies = emptyList(),
                            )
                        }
                        is DialogueStreamEvent.Complete -> updateSendState(snapshot, operationId) {
                            it.copy(
                                sending = false,
                                session = event.session,
                                draft = "",
                                sendOutcomeUnknown = false,
                                sendBaselineTranscript = null,
                                failedOperationId = "",
                                failedMessage = "",
                                streamStatus = "",
                                streamingReplies = emptyList(),
                                notice = if (event.replayed) "已恢复这次发送的本地结果。" else it.notice,
                                error = "",
                            )
                        }
                        is DialogueStreamEvent.Failure -> throw StreamReplyException(
                            event.message,
                            event.retryable,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                handleStreamingFailure(snapshot, operationId, message, messageKind, error)
            }
        }
    }

    private suspend fun handleStreamingFailure(
        snapshot: ChatUiState,
        operationId: String,
        message: String,
        messageKind: String,
        error: Throwable,
    ) {
        val refreshed = try {
            repository.getSession(snapshot.runId, snapshot.sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        val baseline = snapshot.session?.transcript.orEmpty()
        val responseWasCommitted = refreshed?.status == "ready" &&
            refreshed.transcript != baseline
        val retryable = when (error) {
            is StreamReplyException -> error.retryable
            is ApiRequestException -> error.statusCode == null ||
                error.statusCode == 408 || error.statusCode == 429 || error.statusCode >= 500
            else -> true
        }
        val outcomeUnknown = !responseWasCommitted &&
            error !is StreamReplyException && retryable
        updateSendState(snapshot, operationId) {
            it.copy(
                sending = false,
                session = refreshed ?: it.session,
                draft = if (responseWasCommitted) "" else it.draft,
                sendOutcomeUnknown = outcomeUnknown,
                sendBaselineTranscript = if (outcomeUnknown) baseline else null,
                failedOperationId = if (responseWasCommitted || !retryable) "" else operationId,
                failedMessage = if (responseWasCommitted || !retryable) "" else message,
                failedMessageKind = messageKind,
                streamStatus = "",
                streamingReplies = emptyList(),
                error = if (responseWasCommitted) {
                    "连接中断，但已从本地恢复最新回复。"
                } else {
                    error.readableMessage(
                        if (retryable) {
                            "回复生成失败，可安全重试这次发送。"
                        } else {
                            "回复请求失败，请检查消息或模型配置。"
                        },
                    )
                },
            )
        }
    }

    fun discardFailedSend() {
        val snapshot = state.value
        if (
            snapshot.sending || snapshot.recovering || snapshot.sendOutcomeUnknown ||
            snapshot.failedOperationId.isBlank() || snapshot.session?.status != "ready"
        ) {
            return
        }
        mutableState.update {
            it.copy(
                failedOperationId = "",
                failedMessage = "",
                sendBaselineTranscript = null,
                error = "",
                notice = "已保留输入，可以修改后重新发送。",
            )
        }
    }

    fun recoverPending() {
        val snapshot = state.value
        if (
            snapshot.loading ||
            snapshot.sending ||
            snapshot.recovering ||
            snapshot.session == null ||
            snapshot.session.status == "ready"
        ) {
            return
        }

        cancelLoadRequest()
        cancelRecoveryRequest()
        val requestId = ++nextRecoveryRequestId
        recoveryJob = viewModelScope.launch {
            updateRecoveryState(requestId, snapshot) {
                it.copy(refreshing = false, recovering = true, error = "")
            }
            try {
                val recovered = repository.recoverSession(snapshot.runId, snapshot.sessionId)
                val responseWasCommitted = snapshot.sendBaselineTranscript?.let {
                    recovered.status == "ready" && recovered.transcript != it
                } == true
                val resolved = recovered.status == "ready"
                updateRecoveryState(requestId, snapshot) {
                    it.copy(
                        recovering = false,
                        session = recovered,
                        draft = if (responseWasCommitted) "" else it.draft,
                        sendOutcomeUnknown = snapshot.sendOutcomeUnknown && !resolved,
                        sendBaselineTranscript = if (resolved) null else it.sendBaselineTranscript,
                        failedOperationId = if (resolved) "" else it.failedOperationId,
                        failedMessage = if (resolved) "" else it.failedMessage,
                        error = if (responseWasCommitted) {
                            "已从本地恢复上一次发送的回复。"
                        } else {
                            ""
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateRecoveryState(requestId, snapshot) {
                    it.copy(
                        recovering = false,
                        error = error.readableMessage("会话恢复失败，请稍后重试。"),
                    )
                }
            }
        }
    }

    fun reconcileUnknownSend() {
        val snapshot = state.value
        if (
            !snapshot.sendOutcomeUnknown ||
            snapshot.loading ||
            snapshot.sending ||
            snapshot.recovering
        ) {
            return
        }

        cancelLoadRequest()
        cancelRecoveryRequest()
        val requestId = ++nextRecoveryRequestId
        recoveryJob = viewModelScope.launch {
            updateRecoveryState(requestId, snapshot) {
                it.copy(refreshing = false, recovering = true, error = "")
            }
            try {
                val refreshed = repository.getSession(snapshot.runId, snapshot.sessionId)
                val responseWasCommitted = snapshot.sendBaselineTranscript?.let {
                    refreshed.status == "ready" && refreshed.transcript != it
                } == true
                val resolved = refreshed.status == "ready"
                updateRecoveryState(requestId, snapshot) {
                    it.copy(
                        recovering = false,
                        session = refreshed,
                        draft = if (responseWasCommitted) "" else it.draft,
                        sendOutcomeUnknown = !resolved,
                        sendBaselineTranscript = if (resolved) null else it.sendBaselineTranscript,
                        failedOperationId = if (responseWasCommitted) "" else it.failedOperationId,
                        failedMessage = if (responseWasCommitted) "" else it.failedMessage,
                        error = when {
                            responseWasCommitted -> "已从本地恢复上一次发送的回复。"
                            resolved -> "本地没有发现新回复，可以安全重试同一次发送。"
                            else -> "这轮仍在处理中，可以稍后核对或恢复会话。"
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                updateRecoveryState(requestId, snapshot) {
                    it.copy(
                        recovering = false,
                        error = error.readableMessage("暂时无法核对发送结果，请稍后重试。"),
                    )
                }
            }
        }
    }

    fun loadSceneCards() {
        if (state.value.sceneCards.isNotEmpty() || state.value.toolBusy.isNotBlank()) return
        runTool("scene_cards") {
            val cards = repository.listReusableCards(ReusableCardKind.Scene)
            updateState { it.copy(sceneCards = cards) }
        }
    }

    fun suggestReply(direction: String = "") {
        val current = state.value
        requestSuggestedReply(direction, current.messageKind, current.draft)
    }

    private fun requestSuggestedReply(
        direction: String,
        resultMessageKind: String,
        seedText: String,
    ) {
        runTool("suggest") {
            val suggestion = repository.suggestReply(
                runId,
                sessionId,
                seedText = seedText,
                direction = direction,
            )
            updateState {
                it.copy(
                    draft = suggestion,
                    messageKind = resultMessageKind,
                    notice = "续写建议已放入输入框，可以修改后发送。",
                )
            }
        }
    }

    fun requestAssociations() {
        runTool("associations") {
            val options = repository.dialogueAssociations(runId, sessionId)
                .extractAssociationOptions(snapshot.session?.mode.orEmpty())
            updateState {
                it.copy(
                    toolOptionsTitle = "AI 联想",
                    toolOptions = options,
                    notice = if (options.isEmpty()) "这次没有生成可用联想。" else "",
                )
            }
        }
    }

    fun requestDirectorOptions(goal: String, action: String = "advance") {
        val normalizedGoal = goal.trim()
        if (normalizedGoal.isBlank()) {
            mutableState.update { it.copy(error = "请先写下希望剧情怎样发展。") }
            return
        }
        runTool("director") {
            val options = repository.dialogueDirectorOptions(
                runId,
                sessionId,
                goal = normalizedGoal,
                action = action,
            ).extractDirectorOptions()
            updateState {
                it.copy(
                    toolOptionsTitle = "剧情导演方案",
                    toolOptions = options,
                    notice = if (options.isEmpty()) "这次没有生成可用方案。" else "",
                )
            }
        }
    }

    fun chooseToolOption(option: ChatToolOption) {
        if (!state.value.canUseTools) return
        if (option.suggestionDirection.isNotBlank()) {
            mutableState.update {
                it.copy(
                    toolOptions = emptyList(),
                    toolOptionsTitle = "",
                    messageKind = option.messageKind,
                )
            }
            requestSuggestedReply(
                direction = option.suggestionDirection,
                resultMessageKind = option.messageKind,
                seedText = "",
            )
            return
        }
        mutableState.update {
            it.copy(
                draft = option.value,
                messageKind = option.messageKind,
                toolOptions = emptyList(),
                toolOptionsTitle = "",
                notice = "方案已放入输入框。",
            )
        }
    }

    fun dismissToolOptions() {
        mutableState.update { it.copy(toolOptions = emptyList(), toolOptionsTitle = "") }
    }

    fun correctLatest() {
        runTool("correct") {
            acceptSession(
                branchMutation(originKind = "consistency_correction") {
                    repository.correctLatestReply(runId, sessionId)
                },
                "已创建修正版分支，原会话仍然保留。",
                navigateToSession = true,
            )
        }
    }

    fun deepReviewLatest() {
        runTool("review") {
            acceptSession(
                sessionMutation { repository.deepReviewLatestReply(runId, sessionId) },
                "已完成最新一轮的深度复核。",
            )
        }
    }

    fun branchFromTurn(turnId: String) {
        if (turnId.isBlank()) return
        runTool("branch") {
            acceptSession(
                branchMutation(originKind = "event_timeline", originValue = turnId) {
                    repository.branchDialogueTurn(runId, sessionId, turnId)
                },
                "已从所选轮次创建新分支。",
                navigateToSession = true,
            )
        }
    }

    fun branchFromScene(sceneIndex: Int) {
        runTool("branch") {
            acceptSession(
                branchMutation(originKind = "scene_timeline", originValue = sceneIndex.toString()) {
                    repository.branchDialogueScene(runId, sessionId, sceneIndex)
                },
                "已从所选场景创建新分支。",
                navigateToSession = true,
            )
        }
    }

    fun updateBranchMeta(label: String, isMainline: Boolean) {
        runTool("branch_meta") {
            acceptSession(
                sessionMutation {
                    repository.updateDialogueBranchMeta(
                        runId,
                        sessionId,
                        label.trim(),
                        isMainline,
                    )
                },
                "分支信息已更新。",
            )
        }
    }

    fun setMainlineEventLocked(turnId: String, locked: Boolean) {
        val normalizedTurnId = turnId.trim()
        if (normalizedTurnId.isBlank()) return
        runTool("branch_event_lock") {
            val currentIds = snapshot.session?.branchMeta
                ?.stringList("locked_event_ids")
                .orEmpty()
            val nextIds = if (locked) {
                (currentIds + normalizedTurnId).distinct()
            } else {
                currentIds.filterNot { it == normalizedTurnId }
            }
            acceptSession(
                sessionMutation {
                    repository.updateDialogueBranchMeta(
                        runId = runId,
                        sessionId = sessionId,
                        lockedEventIds = nextIds,
                    )
                },
                if (locked) "已锁定为主线事件。" else "已解除主线事件锁定。",
            )
        }
    }

    fun recommendNextScene() {
        runTool("recommend_scene") {
            val payload = repository.recommendDialogueScene(runId, sessionId)
            val cardId = payload.stringValue("recommended_card_id")
            val transition = payload.stringValue("recommended_transition_message")
            val cards = if (snapshot.sceneCards.isEmpty()) {
                repository.listReusableCards(ReusableCardKind.Scene)
            } else {
                snapshot.sceneCards
            }
            updateState {
                it.copy(
                    sceneCards = cards,
                    recommendedSceneCardId = cardId,
                    recommendedTransition = transition,
                    notice = if (cardId.isBlank()) "目前没有可推荐的下一幕。" else "已找到一张适合承接的场景卡。",
                )
            }
        }
    }

    fun switchScene(cardId: String, transition: String = "", autoContinue: Boolean = false) {
        if (cardId.isBlank()) return
        runTool("switch_scene") {
            acceptSession(
                sessionMutation {
                    repository.switchDialogueScene(
                        runId,
                        sessionId,
                        cardId,
                        transition,
                        autoContinue,
                    )
                },
                "场景已切换。",
                clearSceneRecommendation = true,
            )
        }
    }

    fun saveMemory(memory: DialogueMemoryDto) {
        if (memory.text.isBlank()) {
            mutableState.update { it.copy(error = "记忆内容不能为空。") }
            return
        }
        runTool("memory") {
            acceptSession(
                sessionMutation { repository.saveDialogueMemory(runId, sessionId, memory) },
                "会话记忆已保存。",
                memorySaved = true,
            )
        }
    }

    fun deleteMemory(memoryId: String) {
        if (memoryId.isBlank()) return
        runTool("memory") {
            acceptSession(
                sessionMutation { repository.deleteDialogueMemory(runId, sessionId, memoryId) },
                "会话记忆已删除。",
            )
        }
    }

    fun setRelationLock(pairKey: String, locked: Boolean) {
        if (pairKey.isBlank()) return
        runTool("relation_lock") {
            acceptSession(
                sessionMutation {
                    repository.setDialogueRelationLock(
                        runId,
                        sessionId,
                        pairKey,
                        locked,
                    )
                },
                if (locked) "已锁定这组关系。" else "已解除关系锁定。",
            )
        }
    }

    fun clearNotice() {
        mutableState.update { it.copy(notice = "") }
    }

    fun clearError() {
        mutableState.update { it.copy(error = "") }
    }

    private fun runTool(name: String, block: suspend ToolRequest.() -> Unit) {
        val snapshot = state.value
        if (!snapshot.canUseTools) return

        val request = ToolRequest(++nextToolRequestId, snapshot)
        activeToolRequest = request
        mutableState.update {
            if (
                it.runId == request.runId &&
                it.sessionId == request.sessionId &&
                it.canUseTools
            ) {
                it.copy(toolBusy = name, error = "", notice = "")
            } else {
                it
            }
        }
        toolJob = viewModelScope.launch {
            try {
                request.block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                request.updateState {
                    it.copy(error = error.readableMessage("操作失败，请稍后重试。"))
                }
            } finally {
                if (activeToolRequest?.id == request.id) {
                    activeToolRequest = null
                    mutableState.update { it.copy(toolBusy = "") }
                    toolJob = null
                }
            }
        }
    }

    private fun ToolRequest.acceptSession(
        session: DialogueSessionDto,
        notice: String,
        navigateToSession: Boolean = false,
        memorySaved: Boolean = false,
        clearSceneRecommendation: Boolean = false,
    ) {
        updateState {
            it.copy(
                runId = session.runId.ifBlank { it.runId },
                sessionId = session.sessionId.ifBlank { it.sessionId },
                session = session,
                sendOutcomeUnknown = false,
                sendBaselineTranscript = null,
                navigationSession = if (navigateToSession) session else it.navigationSession,
                memorySaveRevision = if (memorySaved) {
                    it.memorySaveRevision + 1
                } else {
                    it.memorySaveRevision
                },
                recommendedSceneCardId = if (clearSceneRecommendation) {
                    ""
                } else {
                    it.recommendedSceneCardId
                },
                recommendedTransition = if (clearSceneRecommendation) {
                    ""
                } else {
                    it.recommendedTransition
                },
                notice = notice,
                error = "",
            )
        }
    }

    private suspend fun ToolRequest.sessionMutation(
        operation: suspend () -> DialogueSessionDto,
    ): DialogueSessionDto = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val recovered = try {
            repository.getSession(runId, sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        recovered?.takeIf { it != snapshot.session } ?: throw error
    }

    private suspend fun ToolRequest.branchMutation(
        originKind: String,
        originValue: String = "",
        operation: suspend () -> DialogueSessionDto,
    ): DialogueSessionDto {
        val knownSessionIds = try {
            repository.listSessions(runId).mapTo(mutableSetOf()) { it.sessionId }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        return try {
            operation()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (knownSessionIds == null) throw error
            val recovered = try {
                repository.listSessions(runId)
                    .asSequence()
                    .filter { it.sessionId !in knownSessionIds }
                    .filter { it.branchOrigin.stringValue("session_id") == sessionId }
                    .filter { it.branchOrigin.stringValue("kind") == originKind }
                    .filter {
                        when (originKind) {
                            "event_timeline", "consistency_correction" ->
                                originValue.isBlank() || it.branchOrigin.stringValue("turn_id") == originValue
                            "scene_timeline" -> it.branchOrigin.stringValue("scene_index") == originValue
                            else -> true
                        }
                    }
                    .singleOrNull()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            recovered ?: throw error
        }
    }

    fun consumeNavigationSession() {
        mutableState.update { it.copy(navigationSession = null) }
    }

    private fun ToolRequest.updateState(transform: (ChatUiState) -> ChatUiState) {
        mutableState.update { current ->
            if (
                activeToolRequest?.id == id &&
                current.runId == runId &&
                current.sessionId == sessionId
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private fun updateLoadState(
        requestId: Long,
        runId: String,
        sessionId: String,
        transform: (ChatUiState) -> ChatUiState,
    ) {
        mutableState.update { current ->
            if (
                loadRequestId == requestId &&
                current.runId == runId &&
                current.sessionId == sessionId
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private fun cancelLoadRequest() {
        loadRequestId += 1
        loadJob?.cancel()
        loadJob = null
    }

    private fun cancelToolRequest() {
        activeToolRequest = null
        toolJob?.cancel()
        toolJob = null
        mutableState.update {
            if (it.toolBusy.isBlank()) it else it.copy(toolBusy = "")
        }
    }

    private fun cancelRecoveryRequest() {
        nextRecoveryRequestId += 1
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun updateRecoveryState(
        requestId: Long,
        snapshot: ChatUiState,
        transform: (ChatUiState) -> ChatUiState,
    ) {
        mutableState.update { current ->
            if (
                nextRecoveryRequestId == requestId &&
                current.runId == snapshot.runId &&
                current.sessionId == snapshot.sessionId
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private fun updateSendState(
        snapshot: ChatUiState,
        operationId: String,
        transform: (ChatUiState) -> ChatUiState,
    ) {
        mutableState.update { current ->
            if (
                current.runId == snapshot.runId &&
                current.sessionId == snapshot.sessionId &&
                current.failedOperationId == operationId
            ) {
                transform(current)
            } else {
                current
            }
        }
    }

    private companion object {
        val messageKinds = setOf("dialogue", "narration", "plot")
    }
}

internal fun JsonObject.extractAssociationOptions(sessionMode: String): List<ChatToolOption> = this["options"]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element -> element.toAssociationOption(sessionMode) }
    .orEmpty()

private fun JsonElement.toAssociationOption(sessionMode: String): ChatToolOption? {
    val messageKind = if (sessionMode == "observe") "narration" else "dialogue"
    runCatching { jsonPrimitive.contentOrNull }.getOrNull()?.takeIf(String::isNotBlank)?.let {
        return ChatToolOption(
            label = it,
            value = "",
            messageKind = messageKind,
            suggestionDirection = it,
        )
    }
    val item = runCatching { jsonObject }.getOrNull() ?: return null
    val direction = item.stringValue("direction")
    val suggestion = item.stringValue("suggestion").ifBlank { item.stringValue("draft") }
    val label = item.stringValue("label").ifBlank { direction }.ifBlank { suggestion }
    if (label.isBlank() || (direction.isBlank() && suggestion.isBlank())) return null
    val anchor = listOf(item.stringValue("anchor_speaker"), item.stringValue("anchor_quote"))
        .filter(String::isNotBlank)
        .joinToString("：")
    return ChatToolOption(
        label = label,
        value = suggestion,
        description = listOf(direction, anchor).filter(String::isNotBlank).joinToString(" · "),
        messageKind = messageKind,
        suggestionDirection = direction.takeIf { suggestion.isBlank() }.orEmpty(),
    )
}

internal fun JsonObject.extractDirectorOptions(): List<ChatToolOption> = this["options"]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element ->
        val item = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
        val title = item.stringValue("title")
        val beat = item.stringValue("beat")
        val direction = item.stringValue("direction")
        if (title.isBlank() || beat.isBlank() || direction.isBlank()) return@mapNotNull null
        val details = buildList {
            item.stringValue("focus").takeIf(String::isNotBlank)?.let { add("焦点：$it") }
            item.stringValue("expected_effect").takeIf(String::isNotBlank)?.let { add("效果：$it") }
            item.stringValue("risk").takeIf(String::isNotBlank)?.let { add("风险：$it") }
        }
        ChatToolOption(
            label = title,
            value = listOf(beat, direction).distinct().joinToString("；"),
            description = details.joinToString(" · "),
            messageKind = "plot",
        )
    }
    .orEmpty()

private fun JsonObject.stringValue(key: String): String = this[key]
    ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    .orEmpty()

private fun JsonObject.stringList(key: String): List<String> = this[key]
    ?.let { runCatching { it.jsonArray }.getOrNull() }
    ?.mapNotNull { element ->
        runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
    }
    .orEmpty()

private fun Throwable.readableMessage(fallback: String): String = message
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?: fallback

private class StreamReplyException(
    message: String,
    val retryable: Boolean,
) : Exception(message)
