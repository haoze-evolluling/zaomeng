package top.wkbin.zaomeng.data.api

import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming

interface ZaomengApi {
    @GET("api/web/health")
    suspend fun health(): JsonObject

    @GET("api/web/settings/model")
    suspend fun getModelSettings(): ModelSettingsDto

    @PUT("api/web/settings/model")
    suspend fun saveModelSettings(@Body request: SaveModelSettingsRequest): ModelSettingsDto

    @GET("api/web/runs")
    suspend fun listRuns(): RunsResponse

    @GET("api/web/builtin-novels")
    suspend fun listBuiltinNovels(): BuiltinNovelsResponse

    @POST("api/web/builtin-novels/{packageId}/clone")
    suspend fun cloneBuiltinNovel(@Path("packageId") packageId: String): RunManifestDto

    @POST("api/web/runs")
    suspend fun createRun(@Body request: CreateRunRequest): RunManifestDto

    @POST("api/web/runs/import")
    suspend fun importRun(@Body request: ImportRunPackageRequest): RunManifestDto

    @GET("api/web/runs/{runId}")
    suspend fun getRun(@Path("runId") runId: String): RunManifestDto

    @DELETE("api/web/runs/{runId}")
    suspend fun deleteRun(@Path("runId") runId: String): DeleteRunResponse

    @POST("api/web/runs/{runId}/refresh")
    suspend fun refreshRun(@Path("runId") runId: String): RunManifestDto

    @POST("api/web/runs/{runId}/stop")
    suspend fun stopRun(@Path("runId") runId: String): RunManifestDto

    @POST("api/web/runs/{runId}/redistill")
    suspend fun redistillRun(
        @Path("runId") runId: String,
        @Body request: RestartRunRequest,
    ): RunManifestDto

    @POST("api/web/runs/{runId}/redistill/recommend")
    suspend fun suggestRedistillSegments(
        @Path("runId") runId: String,
        @Body request: SuggestRedistillSegmentsRequest,
    ): RedistillSuggestionsDto

    @Streaming
    @GET("api/web/runs/{runId}/export")
    suspend fun exportRun(@Path("runId") runId: String): Response<ResponseBody>

    @GET("api/web/runs/{runId}/personas/{character}")
    suspend fun getPersona(
        @Path("runId") runId: String,
        @Path("character") character: String,
    ): PersonaReviewDto

    @PUT("api/web/runs/{runId}/personas/{character}")
    suspend fun savePersona(
        @Path("runId") runId: String,
        @Path("character") character: String,
        @Body fields: JsonObject,
    ): PersonaReviewDto

    @GET("api/web/runs/{runId}/personas/{character}/quality-report")
    suspend fun getPersonaQuality(
        @Path("runId") runId: String,
        @Path("character") character: String,
    ): PersonaQualityReportDto

    @POST("api/web/runs/{runId}/personas/{character}/suggest-field")
    suspend fun suggestPersonaField(
        @Path("runId") runId: String,
        @Path("character") character: String,
        @Body request: SuggestPersonaFieldRequest,
    ): SuggestPersonaFieldResponse

    @GET("api/web/runs/{runId}/relations")
    suspend fun getRelations(@Path("runId") runId: String): RelationDetailsDto

    @PATCH("api/web/runs/{runId}/relations/{pairKey}")
    suspend fun updateRelation(
        @Path("runId") runId: String,
        @Path("pairKey") pairKey: String,
        @Body request: UpdateRelationDetailRequest,
    ): RelationDetailsDto

    @GET("api/web/scene-cards")
    suspend fun listSceneCards(): ReusableCardsResponse

    @GET("api/web/scene-cards/{cardId}")
    suspend fun getSceneCard(@Path("cardId") cardId: String): ReusableCardDto

    @POST("api/web/scene-cards")
    suspend fun createSceneCard(@Body fields: JsonObject): ReusableCardDto

    @PUT("api/web/scene-cards/{cardId}")
    suspend fun updateSceneCard(
        @Path("cardId") cardId: String,
        @Body fields: JsonObject,
    ): ReusableCardDto

    @DELETE("api/web/scene-cards/{cardId}")
    suspend fun deleteSceneCard(@Path("cardId") cardId: String): DeleteStatusDto

    @POST("api/web/scene-cards/generate")
    suspend fun generateSceneCard(): ReusableCardDto

    @POST("api/web/scene-cards/recommend")
    suspend fun recommendSceneCards(@Body request: RecommendSceneCardsRequest): JsonObject

    @GET("api/web/self-cards")
    suspend fun listSelfCards(): ReusableCardsResponse

    @GET("api/web/self-cards/{cardId}")
    suspend fun getSelfCard(@Path("cardId") cardId: String): ReusableCardDto

    @POST("api/web/self-cards")
    suspend fun createSelfCard(@Body fields: JsonObject): ReusableCardDto

    @PUT("api/web/self-cards/{cardId}")
    suspend fun updateSelfCard(
        @Path("cardId") cardId: String,
        @Body fields: JsonObject,
    ): ReusableCardDto

    @DELETE("api/web/self-cards/{cardId}")
    suspend fun deleteSelfCard(@Path("cardId") cardId: String): DeleteStatusDto

    @POST("api/web/self-cards/generate")
    suspend fun generateSelfCard(): ReusableCardDto

    @GET("api/web/opening-presets")
    suspend fun listOpeningPresets(): ReusableCardsResponse

    @GET("api/web/opening-presets/{cardId}")
    suspend fun getOpeningPreset(@Path("cardId") cardId: String): ReusableCardDto

    @POST("api/web/opening-presets")
    suspend fun createOpeningPreset(@Body fields: JsonObject): ReusableCardDto

    @PUT("api/web/opening-presets/{cardId}")
    suspend fun updateOpeningPreset(
        @Path("cardId") cardId: String,
        @Body fields: JsonObject,
    ): ReusableCardDto

    @DELETE("api/web/opening-presets/{cardId}")
    suspend fun deleteOpeningPreset(@Path("cardId") cardId: String): DeleteStatusDto

    @GET("api/web/sessions")
    suspend fun listRecentSessions(): SessionsResponse

    @GET("api/web/runs/{runId}/dialogue/sessions")
    suspend fun listRunSessions(@Path("runId") runId: String): SessionsResponse

    @POST("api/web/runs/{runId}/dialogue/sessions")
    suspend fun createDialogueSession(
        @Path("runId") runId: String,
        @Body request: CreateDialogueSessionRequest,
    ): DialogueSessionDto

    @GET("api/web/runs/{runId}/dialogue/sessions/{sessionId}")
    suspend fun getDialogueSession(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/recover")
    suspend fun recoverDialogueSession(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/reply")
    suspend fun replyDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: DialogueReplyRequest,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/suggest")
    suspend fun suggestDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: DialogueSuggestionRequest,
    ): DialogueSuggestionResponse

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/correct-latest")
    suspend fun correctLatestDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/deep-review")
    suspend fun deepReviewLatestDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/associations")
    suspend fun associateDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: DialogueAssociationsRequest,
    ): JsonObject

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/director-options")
    suspend fun directDialogue(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: DialogueDirectorRequest,
    ): JsonObject

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/branch-turn")
    suspend fun branchDialogueTurn(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: BranchDialogueTurnRequest,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/branch")
    suspend fun branchDialogueScene(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: BranchDialogueSceneRequest,
    ): DialogueSessionDto

    @PATCH("api/web/runs/{runId}/dialogue/sessions/{sessionId}/branch-meta")
    suspend fun updateDialogueBranchMeta(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: UpdateDialogueBranchMetaRequest,
    ): DialogueSessionDto

    @PUT("api/web/runs/{runId}/dialogue/sessions/{sessionId}/relation-lock")
    suspend fun updateDialogueRelationLock(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: UpdateDialogueRelationLockRequest,
    ): DialogueSessionDto

    @PUT("api/web/runs/{runId}/dialogue/sessions/{sessionId}/scene-card")
    suspend fun switchDialogueScene(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: SwitchDialogueSceneRequest,
    ): DialogueSessionDto

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/scene-card/recommend")
    suspend fun recommendDialogueScene(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): JsonObject

    @POST("api/web/runs/{runId}/dialogue/sessions/{sessionId}/memories")
    suspend fun createDialogueMemory(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Body request: UpsertDialogueMemoryRequest,
    ): DialogueSessionDto

    @PUT("api/web/runs/{runId}/dialogue/sessions/{sessionId}/memories/{memoryId}")
    suspend fun updateDialogueMemory(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Path("memoryId") memoryId: String,
        @Body request: UpsertDialogueMemoryRequest,
    ): DialogueSessionDto

    @DELETE("api/web/runs/{runId}/dialogue/sessions/{sessionId}/memories/{memoryId}")
    suspend fun deleteDialogueMemory(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
        @Path("memoryId") memoryId: String,
    ): DialogueSessionDto

    @DELETE("api/web/runs/{runId}/dialogue/sessions/{sessionId}")
    suspend fun deleteDialogueSession(
        @Path("runId") runId: String,
        @Path("sessionId") sessionId: String,
    ): DeleteStatusDto
}
