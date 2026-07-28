package top.wkbin.zaomeng.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.SaveModelSettingsRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val provider: String = "openai-compatible",
    val model: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val maxTokens: String = "",
    val apiKeyConfigured: Boolean = false,
    val configured: Boolean = false,
    val message: String = "",
    val error: String = "",
)

class SettingsViewModel(
    private val repository: ZaomengRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "") }
            runCatching { repository.getModelSettings() }
                .onSuccess { settings ->
                    mutableState.value = SettingsUiState(
                        loading = false,
                        provider = settings.provider.ifBlank { "openai-compatible" },
                        model = settings.model,
                        baseUrl = settings.baseUrl,
                        maxTokens = settings.maxTokens.takeIf { it > 0 }?.toString().orEmpty(),
                        apiKeyConfigured = settings.apiKeyConfigured,
                        configured = settings.configured,
                    )
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(loading = false, error = error.message ?: "模型设置读取失败。")
                    }
                }
        }
    }

    fun updateProvider(value: String) = update { copy(provider = value) }
    fun updateModel(value: String) = update { copy(model = value) }
    fun updateBaseUrl(value: String) = update { copy(baseUrl = value) }
    fun updateApiKey(value: String) = update { copy(apiKey = value) }
    fun updateMaxTokens(value: String) = update { copy(maxTokens = value.filter(Char::isDigit)) }

    fun save() {
        val current = state.value
        if (current.model.isBlank()) {
            mutableState.update { it.copy(error = "请填写模型名称。", message = "") }
            return
        }
        val maxTokens = current.maxTokens.toIntOrNull() ?: 0
        if (maxTokens !in 0..16_000) {
            mutableState.update { it.copy(error = "单次输出上限需在 0 到 16000 之间。", message = "") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = "", message = "") }
            runCatching {
                repository.saveModelSettings(
                    SaveModelSettingsRequest(
                        provider = current.provider,
                        model = current.model.trim(),
                        baseUrl = current.baseUrl.trim(),
                        apiKey = current.apiKey.trim(),
                        maxTokens = maxTokens,
                    ),
                )
            }.onSuccess { saved ->
                mutableState.update {
                    it.copy(
                        saving = false,
                        apiKey = "",
                        apiKeyConfigured = saved.apiKeyConfigured,
                        configured = saved.configured,
                        baseUrl = saved.baseUrl,
                        maxTokens = saved.maxTokens.takeIf { value -> value > 0 }?.toString().orEmpty(),
                        message = "模型配置已保存在这台手机上。",
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(saving = false, error = error.message ?: "模型设置保存失败。")
                }
            }
        }
    }

    private inline fun update(transform: SettingsUiState.() -> SettingsUiState) {
        mutableState.update { it.transform().copy(error = "", message = "") }
    }
}
