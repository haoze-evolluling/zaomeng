package top.wkbin.zaomeng.data

import android.util.Base64
import java.io.File
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.backend.EmbeddedBackendController
import top.wkbin.zaomeng.data.api.CreateDialogueSessionRequest
import top.wkbin.zaomeng.data.api.CreateRunRequest
import top.wkbin.zaomeng.data.api.BuiltinNovelDto
import top.wkbin.zaomeng.data.api.BranchDialogueTurnRequest
import top.wkbin.zaomeng.data.api.BranchDialogueSceneRequest
import top.wkbin.zaomeng.data.api.DeleteRunResponse
import top.wkbin.zaomeng.data.api.DialogueAssociationsRequest
import top.wkbin.zaomeng.data.api.DialogueDirectorRequest
import top.wkbin.zaomeng.data.api.DialogueMemoryDto
import top.wkbin.zaomeng.data.api.DialogueReplyRequest
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.DialogueSuggestionRequest
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.PersonaQualityReportDto
import top.wkbin.zaomeng.data.api.PersonaReviewDto
import top.wkbin.zaomeng.data.api.RelationDetailsDto
import top.wkbin.zaomeng.data.api.RelationItemDto
import top.wkbin.zaomeng.data.api.ReusableCardDto
import top.wkbin.zaomeng.data.api.RecommendSceneCardsRequest
import top.wkbin.zaomeng.data.api.RestartRunRequest
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.SuggestPersonaFieldRequest
import top.wkbin.zaomeng.data.api.SuggestPersonaFieldResponse
import top.wkbin.zaomeng.data.api.SuggestRedistillSegmentsRequest
import top.wkbin.zaomeng.data.api.SwitchDialogueSceneRequest
import top.wkbin.zaomeng.data.api.UpdateRelationDetailRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueBranchMetaRequest
import top.wkbin.zaomeng.data.api.UpdateDialogueRelationLockRequest
import top.wkbin.zaomeng.data.api.UpsertDialogueMemoryRequest
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.data.preferences.AppPreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

class ZaomengRepository(
    private val backend: EmbeddedBackendController,
    private val appPreferences: AppPreferencesRepository,
) {
    val backendState: StateFlow<BackendState> = backend.state
    val preferences: Flow<AppPreferences> = appPreferences.preferences

    fun startBackend() = backend.start()
    fun retryBackend() = backend.retry()

    suspend fun getModelSettings(): ModelSettingsDto = request {
        backend.requireApi().getModelSettings()
    }

    suspend fun saveModelSettings(request: SaveModelSettingsRequest): ModelSettingsDto = request {
        backend.requireApi().saveModelSettings(request)
    }

    suspend fun listRuns(): List<RunManifestDto> = request {
        backend.requireApi().listRuns().items
    }

    suspend fun listBuiltinNovels(): List<BuiltinNovelDto> = request {
        backend.requireApi().listBuiltinNovels().items
    }

    suspend fun cloneBuiltinNovel(packageId: String): RunManifestDto = request {
        val run = backend.requireApi().cloneBuiltinNovel(packageId)
        appPreferences.rememberRun(run.runId)
        run
    }

    suspend fun createNovel(
        filename: String,
        bytes: ByteArray,
        characters: List<String>,
        maxSentences: Int,
        maxChars: Int,
        autoRun: Boolean = true,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            CreateRunRequest(
                novelName = filename,
                novelContentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                characters = characters,
                maxSentences = maxSentences,
                maxChars = maxChars,
                autoRun = autoRun,
                deferRun = !autoRun,
            )
        }
        return request {
            val run = backend.requireApi().createRun(
                payload,
            )
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    suspend fun importPackage(filename: String, bytes: ByteArray): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            ImportRunPackageRequest(
                filename = filename,
                contentBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            )
        }
        return request {
            val run = backend.requireApi().importRun(
                payload,
            )
            appPreferences.rememberRun(run.runId)
            run
        }
    }

    suspend fun saveImportDefaults(characters: String, autoDistill: Boolean = true) {
        appPreferences.saveImportDefaults(characters, autoDistill)
    }

    suspend fun rememberRunLocation(runId: String) {
        appPreferences.rememberRun(runId)
    }

    suspend fun rememberSessionLocation(runId: String, sessionId: String) {
        appPreferences.rememberSession(runId, sessionId)
    }

    suspend fun clearLastSessionLocation() {
        appPreferences.clearLastSession()
    }

    suspend fun clearLastLocation() {
        appPreferences.clearLastLocation()
    }

    suspend fun getRun(runId: String): RunManifestDto = request {
        backend.requireApi().getRun(runId)
    }

    suspend fun deleteRun(runId: String): DeleteRunResponse = request {
        backend.requireApi().deleteRun(runId).also {
            appPreferences.forgetRun(runId)
        }
    }

    suspend fun refreshRun(runId: String): RunManifestDto = request {
        backend.requireApi().refreshRun(runId)
    }

    suspend fun stopRun(runId: String): RunManifestDto = request {
        backend.requireApi().stopRun(runId)
    }

    suspend fun redistill(runId: String, characters: List<String>): RunManifestDto = request {
        backend.requireApi().redistillRun(runId, RestartRunRequest(characters = characters))
    }

    suspend fun redistill(
        runId: String,
        characters: List<String>,
        novelName: String,
        novelBytes: ByteArray?,
        maxSentences: Int,
        maxChars: Int,
    ): RunManifestDto {
        val payload = withContext(Dispatchers.Default) {
            RestartRunRequest(
                characters = characters,
                novelName = novelName.takeIf { novelBytes != null }.orEmpty(),
                novelContentBase64 = novelBytes
                    ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                    .orEmpty(),
                maxSentences = maxSentences,
                maxChars = maxChars,
            )
        }
        return request { backend.requireApi().redistillRun(runId, payload) }
    }

    suspend fun suggestRedistillSegments(
        runId: String,
        character: String,
        maxSegments: Int = 3,
    ): RedistillSuggestionsDto = request {
        backend.requireApi().suggestRedistillSegments(
            runId,
            SuggestRedistillSegmentsRequest(character = character, maxSegments = maxSegments),
        )
    }

    suspend fun exportRun(runId: String, cacheDirectory: File): ExportedRunPackage = request {
        val response = backend.requireApi().exportRun(runId)
        if (!response.isSuccessful) {
            throw ApiRequestException(errorDetail(response.errorBody()?.string(), response.code()))
        }
        val body = response.body() ?: throw ApiRequestException("导出内容为空。")
        val disposition = response.headers()["Content-Disposition"].orEmpty()
        val filename = parseFilename(disposition).ifBlank { "$runId.zaomeng-run.zip" }
        val streamed = body.use {
            streamToTempFile(it.byteStream(), cacheDirectory)
        }
        ExportedRunPackage(
            filename = filename,
            file = streamed.file,
            byteCount = streamed.byteCount,
        )
    }

    suspend fun getPersona(runId: String, character: String): PersonaReviewDto = request {
        backend.requireApi().getPersona(runId, character)
    }

    suspend fun savePersona(
        runId: String,
        character: String,
        completeFields: Map<String, String>,
        reviewNote: String,
    ): PersonaReviewDto = request {
        val payload = buildJsonObject {
            completeFields.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            put("review_source", JsonPrimitive("android"))
            put("review_note", JsonPrimitive(reviewNote))
        }
        backend.requireApi().savePersona(runId, character, payload)
    }

    suspend fun getPersonaQuality(runId: String, character: String): PersonaQualityReportDto = request {
        backend.requireApi().getPersonaQuality(runId, character)
    }

    suspend fun suggestPersonaField(
        runId: String,
        character: String,
        field: String,
    ): SuggestPersonaFieldResponse = request {
        backend.requireApi().suggestPersonaField(runId, character, SuggestPersonaFieldRequest(field))
    }

    suspend fun getRelations(runId: String): RelationDetailsDto = request {
        backend.requireApi().getRelations(runId)
    }

    suspend fun updateRelation(runId: String, relation: RelationItemDto): RelationDetailsDto = request {
        backend.requireApi().updateRelation(
            runId,
            relation.pairKey,
            UpdateRelationDetailRequest(
                trust = relation.trust.coerceIn(0, 10),
                affection = relation.affection.coerceIn(0, 10),
                hostility = relation.hostility.coerceIn(0, 10),
                ambiguity = relation.ambiguity.coerceIn(0, 10),
                relationshipType = relation.relationshipType,
                relationChange = relation.relationChange,
                conflictPoint = relation.conflictPoint,
                typicalInteraction = relation.typicalInteraction,
            ),
        )
    }

    suspend fun listReusableCards(kind: ReusableCardKind): List<ReusableCardDto> = request {
        val api = backend.requireApi()
        when (kind) {
            ReusableCardKind.Scene -> api.listSceneCards().items
            ReusableCardKind.Self -> api.listSelfCards().items
            ReusableCardKind.Opening -> api.listOpeningPresets().items
        }
    }

    suspend fun getReusableCard(kind: ReusableCardKind, cardId: String): ReusableCardDto = request {
        val api = backend.requireApi()
        when (kind) {
            ReusableCardKind.Scene -> api.getSceneCard(cardId)
            ReusableCardKind.Self -> api.getSelfCard(cardId)
            ReusableCardKind.Opening -> api.getOpeningPreset(cardId)
        }
    }

    suspend fun saveReusableCard(
        kind: ReusableCardKind,
        cardId: String,
        fields: JsonObject,
    ): ReusableCardDto = request {
        val api = backend.requireApi()
        when (kind) {
            ReusableCardKind.Scene -> if (cardId.isBlank()) {
                api.createSceneCard(fields)
            } else {
                api.updateSceneCard(cardId, fields)
            }
            ReusableCardKind.Self -> if (cardId.isBlank()) {
                api.createSelfCard(fields)
            } else {
                api.updateSelfCard(cardId, fields)
            }
            ReusableCardKind.Opening -> if (cardId.isBlank()) {
                api.createOpeningPreset(fields)
            } else {
                api.updateOpeningPreset(cardId, fields)
            }
        }
    }

    suspend fun deleteReusableCard(kind: ReusableCardKind, cardId: String) = request {
        val api = backend.requireApi()
        when (kind) {
            ReusableCardKind.Scene -> api.deleteSceneCard(cardId)
            ReusableCardKind.Self -> api.deleteSelfCard(cardId)
            ReusableCardKind.Opening -> api.deleteOpeningPreset(cardId)
        }
    }

    suspend fun generateReusableCard(kind: ReusableCardKind): ReusableCardDto = request {
        when (kind) {
            ReusableCardKind.Scene -> backend.requireApi().generateSceneCard()
            ReusableCardKind.Self -> backend.requireApi().generateSelfCard()
            ReusableCardKind.Opening -> throw ApiRequestException("开场预设需要先选择人物和卡片后保存。")
        }
    }

    suspend fun recommendSceneCard(mode: String, participants: List<String>): String = request {
        val response = backend.requireApi().recommendSceneCards(
            RecommendSceneCardsRequest(mode = mode, participants = participants),
        )
        response["recommended_card_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    suspend fun listSessions(runId: String? = null): List<DialogueSessionDto> = request {
        val api = backend.requireApi()
        if (runId.isNullOrBlank()) api.listRecentSessions().items else api.listRunSessions(runId).items
    }

    suspend fun createSession(
        runId: String,
        mode: String,
        participants: List<String>,
        controlledCharacter: String = "",
        selfName: String = "",
        selfIdentity: String = "",
        selfStyle: String = "",
        sceneCardId: String = "",
        sceneProfile: JsonObject = JsonObject(emptyMap()),
        selfCardId: String = "",
        selfCardProfile: JsonObject = JsonObject(emptyMap()),
    ): DialogueSessionDto = request {
        val inlineSelfProfile = buildJsonObject {
            if (selfName.isNotBlank()) put("display_name", JsonPrimitive(selfName))
            if (selfIdentity.isNotBlank()) put("scene_identity", JsonPrimitive(selfIdentity))
            if (selfStyle.isNotBlank()) put("interaction_style", JsonPrimitive(selfStyle))
        }
        val selfProfile = buildJsonObject {
            selfCardProfile.forEach { (key, value) -> put(key, value) }
            inlineSelfProfile.forEach { (key, value) -> put(key, value) }
        }
        val session = backend.requireApi().createDialogueSession(
            runId,
            CreateDialogueSessionRequest(
                mode = mode,
                participants = participants,
                controlledCharacter = controlledCharacter,
                sceneCardId = sceneCardId,
                sceneProfile = sceneProfile,
                selfCardId = selfCardId,
                selfProfile = selfProfile,
            ),
        )
        appPreferences.rememberSession(runId, session.sessionId)
        session
    }

    suspend fun getSession(runId: String, sessionId: String): DialogueSessionDto = request {
        backend.requireApi().getDialogueSession(runId, sessionId)
    }

    suspend fun recoverSession(runId: String, sessionId: String): DialogueSessionDto = request {
        backend.requireApi().recoverDialogueSession(runId, sessionId)
    }

    suspend fun reply(
        runId: String,
        sessionId: String,
        message: String,
        messageKind: String,
    ): DialogueSessionDto = request {
        backend.requireApi().replyDialogue(
            runId,
            sessionId,
            DialogueReplyRequest(
                message = message,
                messageKind = messageKind,
                suppressTranscriptMessage = messageKind == "plot",
            ),
        )
    }

    suspend fun suggestReply(
        runId: String,
        sessionId: String,
        seedText: String = "",
        direction: String = "",
    ): String = request {
        backend.requireApi().suggestDialogue(
            runId,
            sessionId,
            DialogueSuggestionRequest(seedText = seedText, direction = direction),
        ).suggestion
    }

    suspend fun correctLatestReply(runId: String, sessionId: String): DialogueSessionDto = request {
        backend.requireApi().correctLatestDialogue(runId, sessionId)
    }

    suspend fun deepReviewLatestReply(runId: String, sessionId: String): DialogueSessionDto = request {
        backend.requireApi().deepReviewLatestDialogue(runId, sessionId)
    }

    suspend fun dialogueAssociations(runId: String, sessionId: String): JsonObject = request {
        backend.requireApi().associateDialogue(runId, sessionId, DialogueAssociationsRequest())
    }

    suspend fun dialogueDirectorOptions(
        runId: String,
        sessionId: String,
        goal: String,
        action: String = "advance",
    ): JsonObject = request {
        backend.requireApi().directDialogue(
            runId,
            sessionId,
            DialogueDirectorRequest(goal = goal, action = action),
        )
    }

    suspend fun branchDialogueTurn(
        runId: String,
        sessionId: String,
        turnId: String,
    ): DialogueSessionDto = request {
        backend.requireApi().branchDialogueTurn(runId, sessionId, BranchDialogueTurnRequest(turnId))
    }

    suspend fun branchDialogueScene(
        runId: String,
        sessionId: String,
        sceneIndex: Int,
    ): DialogueSessionDto = request {
        backend.requireApi().branchDialogueScene(
            runId,
            sessionId,
            BranchDialogueSceneRequest(sceneIndex),
        )
    }

    suspend fun updateDialogueBranchMeta(
        runId: String,
        sessionId: String,
        label: String? = null,
        isMainline: Boolean? = null,
        lockedEventIds: List<String>? = null,
    ): DialogueSessionDto = request {
        backend.requireApi().updateDialogueBranchMeta(
            runId,
            sessionId,
            UpdateDialogueBranchMetaRequest(
                label = label,
                isMainline = isMainline,
                lockedEventIds = lockedEventIds,
            ),
        )
    }

    suspend fun setDialogueRelationLock(
        runId: String,
        sessionId: String,
        pairKey: String,
        locked: Boolean,
    ): DialogueSessionDto = request {
        backend.requireApi().updateDialogueRelationLock(
            runId,
            sessionId,
            UpdateDialogueRelationLockRequest(pairKey = pairKey, locked = locked),
        )
    }

    suspend fun switchDialogueScene(
        runId: String,
        sessionId: String,
        sceneCardId: String,
        transitionMessage: String,
        autoContinue: Boolean,
    ): DialogueSessionDto = request {
        backend.requireApi().switchDialogueScene(
            runId,
            sessionId,
            SwitchDialogueSceneRequest(
                sceneCardId = sceneCardId,
                transitionMessage = transitionMessage,
                autoContinue = autoContinue,
            ),
        )
    }

    suspend fun recommendDialogueScene(runId: String, sessionId: String): JsonObject = request {
        backend.requireApi().recommendDialogueScene(runId, sessionId)
    }

    suspend fun saveDialogueMemory(
        runId: String,
        sessionId: String,
        memory: DialogueMemoryDto,
    ): DialogueSessionDto = request {
        val payload = UpsertDialogueMemoryRequest(
            text = memory.text,
            category = memory.category,
            pinned = memory.pinned,
            enabled = memory.enabled,
        )
        if (memory.memoryId.isBlank()) {
            backend.requireApi().createDialogueMemory(runId, sessionId, payload)
        } else {
            backend.requireApi().updateDialogueMemory(runId, sessionId, memory.memoryId, payload)
        }
    }

    suspend fun deleteDialogueMemory(
        runId: String,
        sessionId: String,
        memoryId: String,
    ): DialogueSessionDto = request {
        backend.requireApi().deleteDialogueMemory(runId, sessionId, memoryId)
    }

    suspend fun deleteSession(runId: String, sessionId: String) = request {
        backend.requireApi().deleteDialogueSession(runId, sessionId).also {
            appPreferences.forgetSession(runId, sessionId)
        }
    }

    private suspend fun <T> request(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ApiRequestException) {
            throw error
        } catch (error: HttpException) {
            throw ApiRequestException(
                errorDetail(error.response()?.errorBody()?.string(), error.code()),
                error,
            )
        } catch (error: Throwable) {
            val readable = generateSequence(error) { it.cause }
                .mapNotNull { it.message?.trim() }
                .firstOrNull { it.isNotBlank() }
                ?: "请求失败，请稍后重试。"
            throw ApiRequestException(readable, error)
        }
    }

    private fun errorDetail(body: String?, status: Int): String {
        val detail = runCatching {
            json.parseToJsonElement(body.orEmpty()).jsonObject["detail"]
        }.getOrNull()
        val message = when (detail) {
            is JsonPrimitive -> detail.contentOrNull.orEmpty()
            is JsonArray -> detail.mapNotNull { item ->
                val issue = item as? JsonObject ?: return@mapNotNull null
                val location = (issue["loc"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.dropWhile { it == "body" }
                    ?.joinToString(".")
                    .orEmpty()
                val issueMessage = (issue["msg"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                when {
                    issueMessage.isBlank() -> null
                    location.isBlank() -> issueMessage
                    else -> "$location: $issueMessage"
                }
            }.take(3).joinToString("；")
            is JsonObject -> (detail["message"] as? JsonPrimitive)?.contentOrNull
                ?: (detail["detail"] as? JsonPrimitive)?.contentOrNull
                ?: ""
            else -> ""
        }
        return message.takeIf(String::isNotBlank) ?: "本地接口返回 HTTP $status。"
    }

    private fun parseFilename(contentDisposition: String): String {
        val encoded = Regex("filename\\*=UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return java.net.URLDecoder.decode(encoded, Charsets.UTF_8.name())
        }
        return Regex("filename=\\\"?([^;\\\"]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1).orEmpty()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

class ApiRequestException(message: String, cause: Throwable? = null) : Exception(message, cause)

enum class ReusableCardKind {
    Scene,
    Self,
    Opening,
}
