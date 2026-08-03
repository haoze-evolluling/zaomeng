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
import top.wkbin.zaomeng.data.api.PluginDto

data class PluginsUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val plugins: List<PluginDto> = emptyList(),
    val busyPluginId: String = "",
    val message: String = "",
    val error: String = "",
)

class PluginsViewModel(
    private val repository: ZaomengRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PluginsUiState())
    val state: StateFlow<PluginsUiState> = mutableState.asStateFlow()

    init {
        load()
    }

    fun load(refresh: Boolean = false) {
        if (state.value.refreshing || state.value.busyPluginId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update { current ->
                current.copy(
                    loading = current.plugins.isEmpty(),
                    refreshing = refresh,
                    message = "",
                    error = "",
                )
            }
            try {
                val plugins = if (refresh) repository.refreshPlugins() else repository.listPlugins()
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        plugins = plugins.sortedWith(compareByDescending<PluginDto> { plugin -> plugin.source == "official" }.thenBy { plugin -> plugin.name }),
                        message = if (refresh) "插件列表已刷新。" else "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "读取插件列表失败。",
                    )
                }
            }
        }
    }

    fun setEnabled(plugin: PluginDto, enabled: Boolean) {
        if (state.value.busyPluginId.isNotBlank() || plugin.enabled == enabled) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(busyPluginId = plugin.id, message = "", error = "")
            }
            try {
                val updated = if (enabled) {
                    repository.enablePlugin(plugin.id)
                } else {
                    repository.disablePlugin(plugin.id)
                }
                mutableState.update { current ->
                    current.copy(
                        busyPluginId = "",
                        plugins = current.plugins.map { item ->
                            if (item.id == updated.id) updated else item
                        },
                        message = if (enabled) "已启用「${plugin.name}」。" else "已停用「${plugin.name}」。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        busyPluginId = "",
                        error = error.message ?: "插件状态更新失败。",
                    )
                }
            }
        }
    }
}
