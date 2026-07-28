package top.wkbin.zaomeng.feature.bookshelf

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.backend.BackendState
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.RunManifestDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BookshelfUiState(
    val backendState: BackendState = BackendState.Idle,
    val runs: List<RunManifestDto> = emptyList(),
    val loadingRuns: Boolean = false,
    val refreshing: Boolean = false,
    val error: String = "",
)

class BookshelfViewModel(
    private val repository: ZaomengRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BookshelfUiState())
    val state: StateFlow<BookshelfUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null

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
            runCatching { repository.listRuns() }
                .onSuccess { runs ->
                    mutableState.update {
                        it.copy(
                            runs = runs.sortedByDescending(RunManifestDto::updatedAt),
                            loadingRuns = false,
                            refreshing = false,
                            error = "",
                        )
                    }
                }
                .onFailure { error ->
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
}
