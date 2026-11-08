package com.merlin.perfmon.data.repository

import com.merlin.perfmon.data.collectors.*
import com.merlin.perfmon.data.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central repository that drives all metric collectors independently on their
 * own intervals, then merges the latest values into a unified
 * [PerformanceSnapshot] flow.
 *
 * Kotlinx combine() only has overloads up to 5 parameters that take a typed
 * lambda. For 8 sub-flows we chain two combine() calls to avoid the vararg
 * Array<*> overload whose lambda receives an untyped Array.
 */
@Singleton
class PerformanceRepository @Inject constructor(
    private val cpuCollector:     CpuCollector,
    private val gpuCollector:     GpuCollector,
    private val ramCollector:     RamCollector,
    private val batteryCollector: BatteryCollector,
    private val thermalCollector: ThermalCollector,
    private val storageCollector: StorageCollector,
    private val networkCollector: NetworkCollector,
    private val fpsCollector:     FpsCollector,
    private val settingsRepo:     SettingsRepository,
) {
    // ── Per-collector state flows ─────────────────────────────────────────────

    private val _cpu     = MutableStateFlow<CpuSnapshot>(emptyCpu())
    private val _gpu     = MutableStateFlow<GpuSnapshot>(emptyGpu())
    private val _ram     = MutableStateFlow<RamSnapshot>(emptyRam())
    private val _battery = MutableStateFlow<BatterySnapshot>(emptyBattery())
    private val _thermal = MutableStateFlow<ThermalSnapshot>(ThermalSnapshot(emptyList()))
    private val _storage = MutableStateFlow<StorageSnapshot>(emptyStorage())
    private val _network = MutableStateFlow<NetworkSnapshot>(emptyNetwork())
    private val _fps     = MutableStateFlow<FpsSnapshot>(emptyFps())

    val cpuFlow:     StateFlow<CpuSnapshot>     = _cpu.asStateFlow()
    val gpuFlow:     StateFlow<GpuSnapshot>     = _gpu.asStateFlow()
    val ramFlow:     StateFlow<RamSnapshot>     = _ram.asStateFlow()
    val batteryFlow: StateFlow<BatterySnapshot> = _battery.asStateFlow()
    val thermalFlow: StateFlow<ThermalSnapshot> = _thermal.asStateFlow()
    val storageFlow: StateFlow<StorageSnapshot> = _storage.asStateFlow()
    val networkFlow: StateFlow<NetworkSnapshot> = _network.asStateFlow()
    val fpsFlow:     StateFlow<FpsSnapshot>     = _fps.asStateFlow()

    /**
     * Combined snapshot.
     * We split into two combine() calls (4 + 4) and then zip them to avoid
     * the untyped vararg overload that kotlinx.coroutines exposes for > 5 flows.
     */
    val snapshotFlow: Flow<PerformanceSnapshot> = combine(
        _cpu, _gpu, _ram, _battery,
    ) { cpu, gpu, ram, battery ->
        Partial(cpu, gpu, ram, battery)
    }.combine(
        combine(_thermal, _storage, _network, _fps) { t, s, n, f -> Rest(t, s, n, f) }
    ) { partial, rest ->
        PerformanceSnapshot(
            timestampMs = System.currentTimeMillis(),
            cpu         = partial.cpu,
            gpu         = partial.gpu,
            ram         = partial.ram,
            battery     = partial.battery,
            thermal     = rest.thermal,
            storage     = rest.storage,
            network     = rest.network,
            fps         = rest.fps,
        )
    }

    private data class Partial(
        val cpu: CpuSnapshot, val gpu: GpuSnapshot,
        val ram: RamSnapshot, val battery: BatterySnapshot,
    )
    private data class Rest(
        val thermal: ThermalSnapshot, val storage: StorageSnapshot,
        val network: NetworkSnapshot, val fps: FpsSnapshot,
    )

    // ── Polling orchestration ─────────────────────────────────────────────────

    private val scope    = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs     = mutableListOf<Job>()
    private var settingsJob: Job? = null

    fun startCollecting() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settingsRepo.settings.collect { settings ->
                restartPollJobs(settings)
            }
        }
    }

    fun stopCollecting() {
        settingsJob?.cancel()
        settingsJob = null
        cancelPollJobs()
    }

    private fun restartPollJobs(s: AppSettings) {
        cancelPollJobs()
        jobs += scope.launch { ticker(s.fpsIntervalMs)     { _fps.value     = fpsCollector.collect() } }
        jobs += scope.launch { ticker(s.cpuIntervalMs)     { _cpu.value     = cpuCollector.collect() } }
        jobs += scope.launch { ticker(s.gpuIntervalMs)     { _gpu.value     = gpuCollector.collect() } }
        jobs += scope.launch { ticker(s.ramIntervalMs)     { _ram.value     = ramCollector.collect() } }
        jobs += scope.launch { ticker(s.networkIntervalMs) { _network.value = networkCollector.collect() } }
        jobs += scope.launch { ticker(s.batteryIntervalMs) { _battery.value = batteryCollector.collect() } }
        jobs += scope.launch { ticker(s.thermalIntervalMs) { _thermal.value = thermalCollector.collect() } }
        jobs += scope.launch { ticker(s.storageIntervalMs) { _storage.value = storageCollector.collect() } }
    }

    private fun cancelPollJobs() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private suspend fun ticker(intervalMs: Long, block: suspend () -> Unit) {
        while (true) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Transient read error — StateFlow keeps last good value.
            }
            delay(intervalMs)
        }
    }

    // ── Empty initial values ──────────────────────────────────────────────────

    private fun emptyCpu() = CpuSnapshot(
        totalUsagePercent    = MetricValue.Unavailable,
        perCoreUsagePercent  = List(8) { MetricValue.Unavailable },
        littleClusterFreqMhz = MetricValue.Unavailable,
        bigClusterFreqMhz    = MetricValue.Unavailable,
        temperatureCelsius   = MetricValue.Unavailable,
    )
    private fun emptyGpu() = GpuSnapshot(
        utilizationPercent = MetricValue.Unavailable,
        frequencyMhz       = MetricValue.Unavailable,
        memoryBytes        = MetricValue.Unavailable,
    )
    private fun emptyRam() = RamSnapshot(
        totalBytes     = MetricValue.Unavailable,
        availableBytes = MetricValue.Unavailable,
        freeBytes      = MetricValue.Unavailable,
        cachedBytes    = MetricValue.Unavailable,
        buffersBytes   = MetricValue.Unavailable,
        swapTotalBytes = MetricValue.Unavailable,
        swapFreeBytes  = MetricValue.Unavailable,
    )
    private fun emptyBattery() = BatterySnapshot(
        capacityPercent     = MetricValue.Unavailable,
        temperatureCelsius  = MetricValue.Unavailable,
        voltageMv           = MetricValue.Unavailable,
        currentMa           = MetricValue.Unavailable,
        currentAvgMa        = MetricValue.Unavailable,
        powerMw             = MetricValue.Unavailable,
        chargeCounterUah    = MetricValue.Unavailable,
        chargeFullUah       = MetricValue.Unavailable,
        chargeFullDesignUah = MetricValue.Unavailable,
        health              = MetricValue.Unavailable,
        status              = MetricValue.Unavailable,
        cycleCount          = MetricValue.Unavailable,
        technology          = MetricValue.Unavailable,
    )
    private fun emptyStorage() = StorageSnapshot(
        totalBytes = MetricValue.Unavailable,
        freeBytes  = MetricValue.Unavailable,
    )
    private fun emptyNetwork() = NetworkSnapshot(
        downloadBps = MetricValue.Unavailable,
        uploadBps   = MetricValue.Unavailable,
    )
    private fun emptyFps() = FpsSnapshot(
        currentFps  = MetricValue.Unavailable,
        averageFps  = MetricValue.Unavailable,
        frameTimeMs = MetricValue.Unavailable,
    )
}
