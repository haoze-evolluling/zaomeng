package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.ModelProfileDto
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import top.wkbin.zaomeng.data.api.TestModelSettingsRequest

data class ModelProfileEditorUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val deleting: Boolean = false,
    val isNew: Boolean = true,
    val profileId: String = "",
    val activeProfileId: String = "",
    val profileCount: Int = 0,
    val profileName: String = "新模型",
    val selectedCatalogId: String = "",
    val provider: String = "openai-compatible",
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val apiKeyConfigured: Boolean = false,
    val maxTokens: String = "0",
    val message: String = "",
    val error: String = "",
    val completed: Boolean = false,
    val original: EditorSnapshot? = null,
) {
    val isDirty: Boolean
        get() = original?.let {
            profileName != it.profileName || selectedCatalogId != it.selectedCatalogId || provider != it.provider ||
                model != it.model || baseUrl != it.baseUrl || maxTokens != it.maxTokens || apiKey.isNotBlank()
        } ?: false
}

data class EditorSnapshot(
    val profileName: String,
    val selectedCatalogId: String,
    val provider: String,
    val model: String,
    val baseUrl: String,
    val maxTokens: String,
)

class ModelProfileEditorViewModel(
    private val repository: ZaomengRepository,
    private val requestedProfileId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ModelProfileEditorUiState())
    val state: StateFlow<ModelProfileEditorUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun selectCatalog(catalog: ModelCatalog) = update {
        copy(
            selectedCatalogId = catalog.id,
            provider = catalog.provider,
            model = catalog.models.firstOrNull()?.id.orEmpty(),
            baseUrl = catalog.baseUrl,
        )
    }

    fun updateProfileName(value: String) = update { copy(profileName = value) }
    fun updateProvider(value: String) = update { copy(provider = value, selectedCatalogId = "custom") }
    fun updateModel(value: String) = update { copy(model = value) }
    fun updateBaseUrl(value: String) = update { copy(baseUrl = value, selectedCatalogId = "custom") }
    fun updateApiKey(value: String) = update { copy(apiKey = value) }
    fun updateMaxTokens(value: String) = update { copy(maxTokens = value.filter(Char::isDigit).take(5)) }

    fun testConnection() {
        val request = validatedRequest() ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(testing = true, error = "", message = "") }
            try {
                val tested = repository.testModelSettings(
                    TestModelSettingsRequest(
                        provider = request.provider,
                        model = request.model,
                        baseUrl = request.baseUrl,
                        apiKey = request.apiKey,
                        maxTokens = request.maxTokens,
                        profileId = request.profileId,
                    ),
                )
                mutableState.update {
                    it.copy(testing = false, message = "连接成功：${tested.provider} / ${tested.model}（${tested.latencyMs} ms）")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(testing = false, error = error.message ?: "模型连接失败。") }
            }
        }
    }

    fun save() {
        val request = validatedRequest() ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            try {
                repository.saveModelSettings(request.copy(activateProfile = state.value.isNew))
                mutableState.update { it.copy(saving = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(saving = false, error = error.message ?: "模型配置保存失败。") }
            }
        }
    }

    fun delete() {
        val current = state.value
        if (current.isNew || current.profileCount <= 1 || current.deleting) return
        viewModelScope.launch {
            mutableState.update { it.copy(deleting = true, error = "", message = "") }
            try {
                repository.deleteModelProfile(current.profileId)
                mutableState.update { it.copy(deleting = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(deleting = false, error = error.message ?: "删除模型档案失败。") }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val settings = repository.getModelSettings()
                val profile = settings.profiles.firstOrNull { it.profileId == requestedProfileId }
                if (requestedProfileId.isNotBlank() && profile == null) {
                    mutableState.value = ModelProfileEditorUiState(loading = false, error = "模型档案不存在。")
                    return@launch
                }
                val initial = profile?.toEditorState(settings.activeProfileId, settings.profiles.size)
                    ?: ModelProfileEditorUiState(loading = false, profileCount = settings.profiles.size)
                mutableState.value = initial.copy(original = initial.snapshot())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = ModelProfileEditorUiState(loading = false, error = error.message ?: "模型档案读取失败。")
            }
        }
    }

    private fun validatedRequest(): SaveModelSettingsRequest? {
        val current = state.value
        val maxTokens = current.maxTokens.toIntOrNull() ?: 0
        val error = when {
            current.profileName.trim().isBlank() -> "请填写档案名称。"
            current.model.trim().isBlank() -> "请填写或选择模型名称。"
            maxTokens !in 0..16000 -> "最大输出 Token 需要在 0 到 16000 之间。"
            else -> ""
        }
        if (error.isNotBlank()) {
            mutableState.update { it.copy(error = error, message = "") }
            return null
        }
        return SaveModelSettingsRequest(
            provider = current.provider,
            model = current.model.trim(),
            baseUrl = current.baseUrl.trim(),
            apiKey = current.apiKey.trim(),
            maxTokens = maxTokens,
            profileId = current.profileId,
            profileName = current.profileName.trim(),
            createProfile = current.isNew,
        )
    }

    private inline fun update(transform: ModelProfileEditorUiState.() -> ModelProfileEditorUiState) {
        mutableState.update { it.transform().copy(error = "", message = "") }
    }
}

private fun ModelProfileDto.toEditorState(activeProfileId: String, profileCount: Int): ModelProfileEditorUiState =
    ModelProfileEditorUiState(
        loading = false,
        isNew = false,
        profileId = profileId,
        activeProfileId = activeProfileId,
        profileCount = profileCount,
        profileName = name.ifBlank { model },
        selectedCatalogId = editorCatalogFor(provider, baseUrl, model)?.id ?: "custom",
        provider = provider,
        model = model,
        baseUrl = baseUrl,
        apiKeyConfigured = apiKeyConfigured,
        maxTokens = maxTokens.toString(),
    )

private fun ModelProfileEditorUiState.snapshot() = EditorSnapshot(profileName, selectedCatalogId, provider, model, baseUrl, maxTokens)

private fun editorCatalogFor(provider: String, baseUrl: String, model: String): ModelCatalog? =
    modelCatalogs.firstOrNull { catalog ->
        catalog.models.any { it.id == model } && catalog.provider == provider &&
            catalog.baseUrl.trimEnd('/') == baseUrl.trimEnd('/')
    }
