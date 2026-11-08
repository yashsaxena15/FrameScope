package com.merlin.perfmon.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.merlin.perfmon.data.repository.ExportFile
import com.merlin.perfmon.data.repository.ExportRepository
import com.merlin.perfmon.data.repository.PerformanceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ExportUiState {
    object Idle      : ExportUiState()
    object Recording : ExportUiState()
    object Exporting : ExportUiState()
    data class Done(val uri: Uri, val mimeType: String) : ExportUiState()
    data class Error(val message: String)               : ExportUiState()
}

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val exportRepo: ExportRepository,
    private val perfRepo:   PerformanceRepository,
) : ViewModel() {

    private val _uiState       = MutableStateFlow<ExportUiState>(ExportUiState.Idle)
    val uiState: StateFlow<ExportUiState> = _uiState.asStateFlow()

    private val _exports       = MutableStateFlow<List<ExportFile>>(emptyList())
    val exports: StateFlow<List<ExportFile>> = _exports.asStateFlow()

    /** Reactive sample count — incremented in the recording loop so the UI updates live. */
    private val _recordedCount = MutableStateFlow(0)
    val recordedCount: StateFlow<Int> = _recordedCount.asStateFlow()

    init { refreshExports() }

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording() {
        exportRepo.startRecording()
        _recordedCount.value = 0
        _uiState.value = ExportUiState.Recording

        viewModelScope.launch {
            perfRepo.snapshotFlow
                .sample(500L)
                .takeWhile { _uiState.value == ExportUiState.Recording }
                .collect { snap ->
                    exportRepo.recordSnapshot(snap)
                    _recordedCount.value = exportRepo.recordedCount
                }
        }
    }

    fun stopRecording() {
        exportRepo.stopRecording()
        _uiState.value = ExportUiState.Idle
    }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportCsv() = viewModelScope.launch {
        _uiState.value = ExportUiState.Exporting
        runCatching { exportRepo.exportCsv() }
            .onSuccess { uri ->
                _uiState.value = ExportUiState.Done(uri, "text/csv")
                refreshExports()
            }
            .onFailure { e ->
                _uiState.value = ExportUiState.Error(e.message ?: "Export failed")
            }
    }

    fun exportJson() = viewModelScope.launch {
        _uiState.value = ExportUiState.Exporting
        runCatching { exportRepo.exportJson() }
            .onSuccess { uri ->
                _uiState.value = ExportUiState.Done(uri, "application/json")
                refreshExports()
            }
            .onFailure { e ->
                _uiState.value = ExportUiState.Error(e.message ?: "Export failed")
            }
    }

    fun shareIntent(uri: Uri, mimeType: String): Intent =
        exportRepo.shareIntent(uri, mimeType)

    fun deleteExport(uri: Uri) {
        exportRepo.deleteExport(uri)
        refreshExports()
    }

    fun resetState() { _uiState.value = ExportUiState.Idle }

    private fun refreshExports() {
        _exports.value = exportRepo.listExports()
    }
}
