package top.wkbin.zaomeng.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.library.OnlineLibraryBook
import top.wkbin.zaomeng.data.library.OnlineLibraryRepository

data class OnlineLibraryUiState(
    val books: List<OnlineLibraryBook> = emptyList(),
    val loading: Boolean = false,
    val importingBookId: String = "",
    val downloadedBytes: Long = 0,
    val downloadTotalBytes: Long = 0,
    val error: String = "",
    val createdRunId: String = "",
)

class OnlineLibraryViewModel(
    private val libraryRepository: OnlineLibraryRepository,
    private val repository: ZaomengRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(OnlineLibraryUiState())
    val state: StateFlow<OnlineLibraryUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (state.value.loading) return
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "") }
            try {
                mutableState.update {
                    it.copy(books = libraryRepository.listBooks(), loading = false, error = "")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(loading = false, error = error.message ?: "在线书卷包加载失败。")
                }
            }
        }
    }

    fun importBook(book: OnlineLibraryBook) {
        if (book.id.isBlank() || state.value.importingBookId.isNotBlank()) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    importingBookId = book.id,
                    downloadedBytes = 0,
                    downloadTotalBytes = book.sizeBytes,
                    error = "",
                )
            }
            try {
                val packageBytes = libraryRepository.downloadBook(book) { downloadedBytes, totalBytes ->
                    mutableState.update { current ->
                        if (current.importingBookId == book.id) {
                            current.copy(
                                downloadedBytes = downloadedBytes,
                                downloadTotalBytes = totalBytes,
                            )
                        } else {
                            current
                        }
                    }
                }
                val run = repository.importPackage("${book.id}.zaomeng-run.zip", packageBytes)
                mutableState.update {
                    it.copy(
                        importingBookId = "",
                        downloadedBytes = 0,
                        downloadTotalBytes = 0,
                        createdRunId = run.runId,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        importingBookId = "",
                        downloadedBytes = 0,
                        downloadTotalBytes = 0,
                        error = error.message ?: "书卷导入失败。",
                    )
                }
            }
        }
    }

    fun consumeCreatedRun() {
        mutableState.update { it.copy(createdRunId = "") }
    }
}
