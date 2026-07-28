package top.wkbin.zaomeng.feature.rundetail

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import top.wkbin.zaomeng.backend.DistillationForegroundController
import top.wkbin.zaomeng.data.ZaomengRepository
import top.wkbin.zaomeng.data.copyStream
import top.wkbin.zaomeng.data.api.ExportedRunPackage
import top.wkbin.zaomeng.data.api.RunManifestDto
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RunDetailUiState(
    val run: RunManifestDto? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val stopping: Boolean = false,
    val redistilling: Boolean = false,
    val exporting: Boolean = false,
    val deleting: Boolean = false,
    val deleted: Boolean = false,
    val error: String = "",
    val message: String = "",
    val exportedPackage: ExportedRunPackage? = null,
    val exportRequestId: Long = 0,
)

class RunDetailViewModel(
    private val repository: ZaomengRepository,
    val runId: String,
    private val applicationContext: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(RunDetailUiState())
    val state: StateFlow<RunDetailUiState> = mutableState.asStateFlow()

    private var loadJob: Job? = null
    private var pollingJob: Job? = null

    init {
        require(runId.isNotBlank()) { "runId 不能为空。" }
        load()
    }

    fun load() = loadRun(refreshManifest = false)

    fun refresh() = loadRun(refreshManifest = true)

    fun stop() {
        val run = state.value.run ?: return
        if (run.status != RUNNING_STATUS || state.value.stopping || run.control.stopRequested) return
        viewModelScope.launch {
            mutableState.update { it.copy(stopping = true, error = "", message = "") }
            try {
                val updated = repository.stopRun(runId)
                acceptRun(updated)
                mutableState.update {
                    it.copy(stopping = false, message = "停止请求已发送，正在等待当前步骤收尾。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = queryRunAfterUnknown()?.takeIf {
                    it.control.stopRequested || it.status != RUNNING_STATUS
                }
                if (recovered != null) {
                    acceptRun(recovered)
                    mutableState.update {
                        it.copy(stopping = false, message = "停止请求已发送，正在等待当前步骤收尾。")
                    }
                } else {
                    mutableState.update {
                        it.copy(stopping = false, error = error.message ?: "停止蒸馏失败。")
                    }
                }
            }
        }
    }

    fun redistillOriginalCharacters() {
        val run = state.value.run ?: return
        if (state.value.redistilling) return
        if (run.status == RUNNING_STATUS) {
            mutableState.update { it.copy(error = "请先停止当前蒸馏，再按原人物重新开始。", message = "") }
            return
        }
        val characters = run.lockedCharacters.ifEmpty { run.availableCharacters }.distinct()
        if (characters.isEmpty()) {
            mutableState.update { it.copy(error = "这份书卷里还没有可重新蒸馏的人物。", message = "") }
            return
        }

        viewModelScope.launch {
            mutableState.update { it.copy(redistilling = true, error = "", message = "") }
            try {
                val updated = repository.redistill(runId, characters)
                acceptRun(updated)
                mutableState.update {
                    it.copy(redistilling = false, message = "已按原人物名单重新开始蒸馏。")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val recovered = queryRunAfterUnknown()?.takeIf { it.status == RUNNING_STATUS }
                if (recovered != null) {
                    acceptRun(recovered)
                    mutableState.update {
                        it.copy(redistilling = false, message = "已按原人物名单重新开始蒸馏。")
                    }
                } else {
                    mutableState.update {
                        it.copy(redistilling = false, error = error.message ?: "重新蒸馏失败。")
                    }
                }
            }
        }
    }

    fun exportRun() {
        if (state.value.exporting) return
        viewModelScope.launch {
            val staleExport = state.value.exportedPackage
            mutableState.update {
                it.copy(exporting = true, exportedPackage = null, error = "", message = "")
            }
            withContext(Dispatchers.IO) { staleExport?.file?.delete() }
            var pendingExport: ExportedRunPackage? = null
            try {
                val exported = repository.exportRun(runId, applicationContext.cacheDir)
                pendingExport = exported
                mutableState.update {
                    it.copy(
                        exporting = false,
                        exportedPackage = exported,
                        exportRequestId = it.exportRequestId + 1,
                        message = "书卷包已经准备好，请选择保存位置。",
                    )
                }
                pendingExport = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(exporting = false, error = error.message ?: "导出书卷失败。")
                }
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { pendingExport?.file?.delete() }
            }
        }
    }

    fun consumeExportedPackage() {
        val exported = state.value.exportedPackage
        mutableState.update { it.copy(exportedPackage = null, message = "") }
        exported?.file?.delete()
    }

    fun saveExportedPackage(uri: Uri) {
        val exported = state.value.exportedPackage ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    exported.file.inputStream().buffered().use { source ->
                        applicationContext.contentResolver.openOutputStream(uri, "w")?.use { destination ->
                            copyStream(source, destination)
                        } ?: error("无法写入所选位置。")
                    }
                }
                withContext(Dispatchers.IO) { exported.file.delete() }
                mutableState.update {
                    it.copy(
                        exportedPackage = null,
                        error = "",
                        message = "书卷已导出（不含聊天记录）。",
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(error = error.message ?: "导出文件写入失败。")
                }
            }
        }
    }

    fun retryExportDestination() {
        if (state.value.exportedPackage == null) return
        mutableState.update {
            it.copy(
                error = "",
                message = "请重新选择保存位置。",
                exportRequestId = it.exportRequestId + 1,
            )
        }
    }

    fun deleteRun() {
        val run = state.value.run ?: return
        if (state.value.deleting) return
        if (run.status == RUNNING_STATUS) {
            mutableState.update { it.copy(error = "请先停止当前蒸馏，再删除这份书卷。") }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(deleting = true, error = "", message = "") }
            try {
                repository.deleteRun(runId)
                markDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val deleted = try {
                    repository.listRuns().none { it.runId == runId }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    false
                }
                if (deleted) {
                    markDeleted()
                } else {
                    mutableState.update {
                        it.copy(deleting = false, error = error.message ?: "删除书卷失败。")
                    }
                }
            }
        }
    }

    fun dismissNotice() {
        mutableState.update { it.copy(error = "", message = "") }
    }

    private fun loadRun(refreshManifest: Boolean) {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            mutableState.update {
                it.copy(
                    loading = it.run == null,
                    refreshing = it.run != null,
                    error = "",
                    message = "",
                )
            }
            try {
                acceptRun(if (refreshManifest) repository.refreshRun(runId) else repository.getRun(runId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update {
                    it.copy(
                        loading = false,
                        refreshing = false,
                        error = error.message ?: "书卷详情读取失败。",
                    )
                }
            }
        }
    }

    private fun acceptRun(run: RunManifestDto) {
        mutableState.update {
            it.copy(
                run = run,
                loading = false,
                refreshing = false,
                error = "",
            )
        }
        if (run.status == RUNNING_STATUS) {
            DistillationForegroundController.start(applicationContext)
            startPolling()
        } else {
            stopPolling()
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            try {
                while (isActive && state.value.run?.status == RUNNING_STATUS) {
                    delay(POLL_INTERVAL_MS)
                    try {
                        val run = repository.getRun(runId)
                        mutableState.update { it.copy(run = run, error = "") }
                        if (run.status != RUNNING_STATUS) return@launch
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        mutableState.update {
                            it.copy(error = error.message ?: "进度更新暂时失败，将继续重试。")
                        }
                    }
                }
            } finally {
                val currentJob = currentCoroutineContext()[Job]
                if (pollingJob === currentJob) pollingJob = null
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun markDeleted() {
        stopPolling()
        mutableState.update { it.copy(deleting = false, deleted = true) }
    }

    private suspend fun queryRunAfterUnknown(): RunManifestDto? = try {
        repository.getRun(runId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    override fun onCleared() {
        pollingJob?.cancel()
        state.value.exportedPackage?.file?.delete()
        super.onCleared()
    }

    private companion object {
        const val RUNNING_STATUS = "running"
        const val POLL_INTERVAL_MS = 2_000L
    }
}
