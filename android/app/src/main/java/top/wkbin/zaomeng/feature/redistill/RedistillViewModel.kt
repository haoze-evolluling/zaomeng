package top.wkbin.zaomeng.feature.redistill

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.backend.DistillationForegroundController
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.api.RedistillSegmentDto
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.feature.importbook.ImportDocumentKind
import top.wkbin.zaomeng.feature.importbook.ImportDocumentLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RedistillUiState(
    val loading: Boolean = true,
    val run: RunManifestDto? = null,
    val characters: String = "",
    val maxSentences: String = "120",
    val maxChars: String = "50000",
    val fileName: String = "",
    val fileSize: Long = 0,
    val readingFile: Boolean = false,
    val recommendationCharacter: String = "",
    val recommending: Boolean = false,
    val suggestions: RedistillSuggestionsDto? = null,
    val selectedSegmentId: String = "",
    val submitting: Boolean = false,
    val completed: Boolean = false,
    val error: String = "",
) {
    val selectedSegment: RedistillSegmentDto?
        get() = suggestions?.segments?.firstOrNull { it.segmentId == selectedSegmentId }
}

class RedistillViewModel(
    private val repository: ZaomengRepository,
    val runId: String,
    private val applicationContext: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RedistillUiState())
    val state: StateFlow<RedistillUiState> = mutableState.asStateFlow()
    private var selectedBytes: ByteArray? = null
    private var fileLoadJob: Job? = null

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        load()
    }

    fun load() {
        viewModelScope.launch {
            mutableState.update { it.copy(loading = true, error = "") }
            try {
                val run = repository.getRun(runId)
                val characters = run.lockedCharacters.ifEmpty { run.availableCharacters }.distinct()
                mutableState.update {
                    it.copy(
                        loading = false,
                        run = run,
                        characters = characters.joinToString("、"),
                        recommendationCharacter = characters.firstOrNull().orEmpty(),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(loading = false, error = error.message ?: "书卷读取失败。")
                }
            }
        }
    }

    fun loadDocument(uri: Uri) {
        fileLoadJob?.cancel()
        fileLoadJob = viewModelScope.launch {
            mutableState.update { it.copy(readingFile = true, error = "") }
            try {
                val document = ImportDocumentLoader.load(
                    contentResolver = applicationContext.contentResolver,
                    uri = uri,
                    expectedKind = ImportDocumentKind.NovelText,
                )
                selectFile(document.fileName, document.bytes)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(readingFile = false, error = error.message ?: "文件读取失败。")
                }
            }
        }
    }

    private fun selectFile(filename: String, bytes: ByteArray) {
        if (!filename.lowercase().endsWith(".txt")) {
            selectedBytes = null
            mutableState.update {
                it.copy(fileName = "", fileSize = 0, readingFile = false, error = "增量正文目前只支持 TXT。")
            }
            return
        }
        if (bytes.isEmpty()) {
            selectedBytes = null
            mutableState.update {
                it.copy(fileName = "", fileSize = 0, readingFile = false, error = "所选 TXT 文件为空。")
            }
            return
        }
        selectedBytes = bytes
        mutableState.update {
            it.copy(
                fileName = filename,
                fileSize = bytes.size.toLong(),
                readingFile = false,
                selectedSegmentId = "",
                error = "",
            )
        }
    }

    fun clearFile() {
        selectedBytes = null
        mutableState.update { it.copy(fileName = "", fileSize = 0, error = "") }
    }

    fun updateCharacters(value: String) = mutableState.update { it.copy(characters = value, error = "") }
    fun updateMaxSentences(value: String) = mutableState.update {
        it.copy(maxSentences = value.filter(Char::isDigit), error = "")
    }
    fun updateMaxChars(value: String) = mutableState.update {
        it.copy(maxChars = value.filter(Char::isDigit), error = "")
    }
    fun selectRecommendationCharacter(value: String) = mutableState.update {
        it.copy(recommendationCharacter = value, suggestions = null, selectedSegmentId = "", error = "")
    }

    fun recommendSegments() {
        val character = state.value.recommendationCharacter.trim()
        if (character.isBlank() || state.value.recommending) return
        viewModelScope.launch {
            mutableState.update { it.copy(recommending = true, error = "", selectedSegmentId = "") }
            try {
                val suggestions = repository.suggestRedistillSegments(runId, character)
                mutableState.update {
                    it.copy(recommending = false, suggestions = suggestions)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(recommending = false, error = error.message ?: "推荐片段失败。")
                }
            }
        }
    }

    fun selectSegment(segmentId: String) {
        selectedBytes = null
        mutableState.update {
            it.copy(
                fileName = "",
                fileSize = 0,
                selectedSegmentId = if (it.selectedSegmentId == segmentId) "" else segmentId,
                error = "",
            )
        }
    }

    fun submit() {
        val snapshot = state.value
        if (snapshot.submitting) return
        val characters = snapshot.characters
            .split(',', '，', '、', ';', '；', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (characters.isEmpty()) {
            mutableState.update { it.copy(error = "至少保留一位要蒸馏的人物。") }
            return
        }
        val maxSentences = snapshot.maxSentences.toIntOrNull() ?: 120
        val maxChars = snapshot.maxChars.toIntOrNull() ?: 50_000
        if (maxSentences !in 20..300 || maxChars !in 2_000..200_000) {
            mutableState.update { it.copy(error = "取样句数需为 20–300，字符数需为 2000–200000。") }
            return
        }
        val segment = snapshot.selectedSegment
        val bytes = selectedBytes ?: segment?.fullText?.toByteArray(Charsets.UTF_8)
        val name = snapshot.fileName.ifBlank {
            segment?.let { "${snapshot.recommendationCharacter}-推荐片段.txt" }.orEmpty()
        }

        viewModelScope.launch {
            mutableState.update { it.copy(submitting = true, error = "") }
            try {
                val run = repository.redistill(
                    runId = runId,
                    characters = characters,
                    novelName = name,
                    novelBytes = bytes,
                    maxSentences = maxSentences,
                    maxChars = maxChars,
                )
                if (run.status == "running") {
                    DistillationForegroundController.start(applicationContext)
                }
                mutableState.update { it.copy(submitting = false, completed = true) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = try {
                    repository.getRun(runId).takeIf { it.status == "running" }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    null
                }
                if (recovered != null) {
                    DistillationForegroundController.start(applicationContext)
                    mutableState.update { it.copy(submitting = false, completed = true, error = "") }
                } else {
                    mutableState.update {
                        it.copy(submitting = false, error = error.message ?: "重新蒸馏失败。")
                    }
                }
            }
        }
    }

}
