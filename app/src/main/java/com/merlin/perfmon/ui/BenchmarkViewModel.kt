package com.merlin.perfmon.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.merlin.perfmon.data.repository.BenchmarkRepository
import com.merlin.perfmon.data.repository.BenchmarkResult
import com.merlin.perfmon.data.repository.BenchmarkState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BenchmarkViewModel @Inject constructor(
    private val benchmarkRepo: BenchmarkRepository,
) : ViewModel() {

    val state:    StateFlow<BenchmarkState>   = benchmarkRepo.stateFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BenchmarkState.IDLE)

    val progress: StateFlow<Float>            = benchmarkRepo.progressFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    val result:   StateFlow<BenchmarkResult?> = benchmarkRepo.resultFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun start(durationMs: Long = 30_000L) = benchmarkRepo.start(durationMs)
    fun cancel()                          = benchmarkRepo.cancel()
    fun reset()                           = benchmarkRepo.reset()
}
