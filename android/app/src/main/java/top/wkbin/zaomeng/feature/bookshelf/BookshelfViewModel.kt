package top.wkbin.zaomeng.feature.bookshelf

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.backend.DistillationForegroundController
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.RunManifestDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class BookshelfFilter(val label: String) {
    All("全部"),
    Ready("可使用"),
    Running("蒸馏中"),
    Draft("待蒸馏"),
    NeedsAttention("需处理"),
}

enum class BookshelfSort(val label: String) {
    Recent("按最近"),
    Title("按书名"),
}

data class BookshelfUiState(
    val backendState: BackendState = BackendState.Idle,
    val runs: List<RunManifestDto> = emptyList(),
    val loadingRuns: Boolean = false,
    val refreshing: Boolean = false,
    val stoppingTasks: Boolean = false,
    val modelConfigured: Boolean? = null,
    val activeModelLabel: String = "",
    val searchQuery: String = "",
    val filter: BookshelfFilter = BookshelfFilter.All,
    val sort: BookshelfSort = BookshelfSort.Recent,
    val error: String = "",
)

class BookshelfViewModel(
    private val repository: ZaomengRepository,
    private val applicationContext: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BookshelfUiState())
    val state: StateFlow<BookshelfUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var modelConfigurationJob: Job? = null

    init {
        repository.startBackend()
        viewModelScope.launch {
            repository.backendState.collectLatest { backendState ->
                mutableState.update {
                    it.copy(
                        backendState = backendState,
                        error = if (backendState is BackendState.Failed) backendState.message else it.error,
                    )
                }
                if (backendState is BackendState.Ready) {
                    loadRuns(manualRefresh = false)
                    refreshModelConfiguration()
                }
            }
        }
    }

    fun refresh() {
        if (state.value.backendState is BackendState.Ready) {
            loadRuns(manualRefresh = true)
        } else {
            retryBackend()
        }
    }

    /** Refresh after this retained destination returns from import or detail. */
    fun refreshWhenResumed() {
        if (state.value.backendState is BackendState.Ready) {
            loadRuns(manualRefresh = true)
            refreshModelConfiguration()
        }
    }

    private fun refreshModelConfiguration() {
        if (modelConfigurationJob?.isActive == true) return
        modelConfigurationJob = viewModelScope.launch {
            try {
                val settings = repository.getModelSettings()
                val activeProfile = settings.profiles.firstOrNull { it.profileId == settings.activeProfileId }
                val label = activeProfile?.let { profile -> profile.name.ifBlank { profile.model } }
                    ?: settings.model
                mutableState.update {
                    it.copy(
                        modelConfigured = settings.configured,
                        activeModelLabel = label,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The book list remains usable if this secondary check cannot be read.
                mutableState.update { it.copy(modelConfigured = null, activeModelLabel = "") }
            }
        }
    }

    fun retryBackend() {
        loadJob?.cancel()
        loadJob = null
        mutableState.update {
            it.copy(
                backendState = BackendState.Idle,
                loadingRuns = false,
                refreshing = false,
                error = "",
            )
        }
        repository.retryBackend()
    }

    fun dismissError() {
        mutableState.update { it.copy(error = "") }
    }

    fun updateSearchQuery(value: String) {
        mutableState.update { it.copy(searchQuery = value.take(MAX_SEARCH_LENGTH)) }
    }

    fun selectFilter(filter: BookshelfFilter) {
        mutableState.update { it.copy(filter = filter) }
    }

    fun toggleSort() {
        mutableState.update {
            it.copy(sort = if (it.sort == BookshelfSort.Recent) BookshelfSort.Title else BookshelfSort.Recent)
        }
    }

    fun clearFilters() {
        mutableState.update { it.copy(searchQuery = "", filter = BookshelfFilter.All) }
    }

    fun stopRunningTasks() {
        val runningIds = state.value.runs.filter { it.status == RUNNING_STATUS }.map(RunManifestDto::runId)
        if (runningIds.isEmpty() || state.value.stoppingTasks) return
        viewModelScope.launch {
            mutableState.update { it.copy(stoppingTasks = true, error = "") }
            DistillationForegroundController.stopAll(applicationContext)
            try {
                val stopped = runningIds.map { repository.stopRun(it) }
                mutableState.update { current ->
                    current.copy(
                        runs = current.runs.map { run -> stopped.firstOrNull { it.runId == run.runId } ?: run },
                        stoppingTasks = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(stoppingTasks = false, error = error.message ?: "停止蒸馏任务失败。")
                }
            }
        }
    }

    private fun loadRuns(manualRefresh: Boolean) {
        if (loadJob?.isActive == true) return
        val initialLoad = state.value.runs.isEmpty() && !manualRefresh
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loadingRuns = initialLoad,
                    refreshing = !initialLoad,
                    error = "",
                )
            }
            try {
                val runs = repository.listRuns()
                if (runs.any { it.status == RUNNING_STATUS }) {
                    DistillationForegroundController.start(applicationContext)
                }
                mutableState.update {
                    it.copy(
                        runs = runs.sortedByDescending(RunManifestDto::updatedAt),
                        loadingRuns = false,
                        refreshing = false,
                        error = "",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loadingRuns = false,
                        refreshing = false,
                        error = error.message ?: "书架读取失败，请稍后重试。",
                    )
                }
            }
        }
    }

    private companion object {
        const val RUNNING_STATUS = "running"
        const val MAX_SEARCH_LENGTH = 120
    }
}

internal fun filterBookshelfRuns(state: BookshelfUiState): List<RunManifestDto> {
    val query = state.searchQuery.trim().lowercase()
    val statusMatches: (RunManifestDto) -> Boolean = when (state.filter) {
        BookshelfFilter.All -> { _ -> true }
        BookshelfFilter.Ready -> { run -> run.status == "ready" }
        BookshelfFilter.Running -> { run -> run.status == "running" }
        BookshelfFilter.Draft -> { run -> run.status == "draft" }
        BookshelfFilter.NeedsAttention -> { run -> run.status == "failed" || run.status == "stopped" }
    }
    val filtered = state.runs.filter { run ->
        statusMatches(run) && (
            query.isBlank() ||
                run.title.lowercase().contains(query) ||
                run.novelSources.any { it.sourceName.lowercase().contains(query) } ||
                run.lockedCharacters.any { it.lowercase().contains(query) }
            )
    }
    return when (state.sort) {
        BookshelfSort.Recent -> filtered
        BookshelfSort.Title -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}
