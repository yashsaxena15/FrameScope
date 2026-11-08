package com.merlin.perfmon.data.repository

import com.merlin.perfmon.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sin
import kotlin.math.sqrt

enum class BenchmarkState { IDLE, RUNNING, FINISHED }

data class BenchmarkResult(
    val durationMs:       Long,
    val samples:          List<PerformanceSnapshot>,
    val avgCpuPct:        Float?,
    val peakCpuPct:       Float?,
    val avgGpuPct:        Float?,
    val peakGpuPct:       Float?,
    val avgFps:           Float?,
    val minFps:           Float?,
    val avgCpuTempC:      Float?,
    val peakCpuTempC:     Float?,
    val avgBatteryPowerMw: Float?,
)

@Singleton
class BenchmarkRepository @Inject constructor(
    private val perfRepository: PerformanceRepository,
) {
    private val _state   = MutableStateFlow(BenchmarkState.IDLE)
    private val _progress = MutableStateFlow(0f)          // 0..1
    private val _result   = MutableStateFlow<BenchmarkResult?>(null)

    val stateFlow:    StateFlow<BenchmarkState>      = _state.asStateFlow()
    val progressFlow: StateFlow<Float>               = _progress.asStateFlow()
    val resultFlow:   StateFlow<BenchmarkResult?>    = _result.asStateFlow()

    private var benchmarkJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Run a [durationMs]-millisecond benchmark.
     *
     * The benchmark applies synthetic CPU load across all available threads
     * while collecting [PerformanceSnapshot]s at 500 ms intervals.
     */
    fun start(durationMs: Long = 30_000L) {
        if (_state.value == BenchmarkState.RUNNING) return

        _state.value    = BenchmarkState.RUNNING
        _progress.value = 0f
        _result.value   = null

        benchmarkJob = scope.launch {
            val samples   = mutableListOf<PerformanceSnapshot>()
            val startMs   = System.currentTimeMillis()
            val endMs     = startMs + durationMs

            // Launch CPU load threads — one per logical core (8 for Helio G85)
            val loadJobs = (0 until 8).map {
                launch(Dispatchers.Default) { syntheticLoad(endMs) }
            }

            // Collect snapshots every 500 ms
            val collector = launch {
                perfRepository.snapshotFlow
                    .sample(500L)
                    .takeWhile { System.currentTimeMillis() < endMs }
                    .collect { snap ->
                        samples.add(snap)
                        _progress.value =
                            (System.currentTimeMillis() - startMs).toFloat() / durationMs.toFloat()
                    }
            }

            loadJobs.joinAll()
            collector.cancelAndJoin()

            _progress.value = 1f
            _result.value   = computeResult(durationMs, samples)
            _state.value    = BenchmarkState.FINISHED
        }
    }

    fun cancel() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _state.value    = BenchmarkState.IDLE
        _progress.value = 0f
    }

    fun reset() {
        if (_state.value == BenchmarkState.RUNNING) return
        _state.value  = BenchmarkState.IDLE
        _result.value = null
        _progress.value = 0f
    }

    // ── Synthetic CPU load ────────────────────────────────────────────────────

    /**
     * A tight loop doing floating-point sqrt/sin math — representative of a
     * real workload and not trivially optimised away by the JIT.
     */
    private fun syntheticLoad(endMs: Long) {
        var acc = 0.0
        var i   = 0L
        while (System.currentTimeMillis() < endMs) {
            acc += sqrt(sin(i.toDouble()) * i.toDouble() + i.toDouble())
            i++
        }
        // Use acc so it isn't dead-code eliminated
        if (acc.isNaN()) throw IllegalStateException("impossible")
    }

    // ── Result computation ────────────────────────────────────────────────────

    private fun computeResult(durationMs: Long, samples: List<PerformanceSnapshot>): BenchmarkResult {
        fun List<Float>.safeAvg()  = if (isEmpty()) null else average().toFloat()
        fun List<Float>.safePeak() = if (isEmpty()) null else max()
        fun List<Float>.safeMin()  = if (isEmpty()) null else min()

        val cpuPcts  = samples.mapNotNull { (it.cpu.totalUsagePercent as? MetricValue.Available)?.value }
        val gpuPcts  = samples.mapNotNull { (it.gpu.utilizationPercent as? MetricValue.Available)?.value?.toFloat() }
        val fpsVals  = samples.mapNotNull { (it.fps.currentFps as? MetricValue.Available)?.value }
        val temps    = samples.mapNotNull { (it.cpu.temperatureCelsius as? MetricValue.Available)?.value }
        val powers   = samples.mapNotNull { (it.battery.powerMw as? MetricValue.Available)?.value }

        return BenchmarkResult(
            durationMs        = durationMs,
            samples           = samples,
            avgCpuPct         = cpuPcts.safeAvg(),
            peakCpuPct        = cpuPcts.safePeak(),
            avgGpuPct         = gpuPcts.safeAvg(),
            peakGpuPct        = gpuPcts.safePeak(),
            avgFps            = fpsVals.safeAvg(),
            minFps            = fpsVals.safeMin(),
            avgCpuTempC       = temps.safeAvg(),
            peakCpuTempC      = temps.safePeak(),
            avgBatteryPowerMw = powers.safeAvg(),
        )
    }
}
