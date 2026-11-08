package com.merlin.perfmon.ui

import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.merlin.perfmon.data.collectors.RootShellManager
import com.merlin.perfmon.data.collectors.ShizukuShellManager
import com.merlin.perfmon.data.model.*
import com.merlin.perfmon.data.repository.PerformanceRepository
import com.merlin.perfmon.data.repository.SettingsRepository
import com.merlin.perfmon.service.MonitorService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val perfRepo:              PerformanceRepository,
    private val settingsRepo:  SettingsRepository,
    private val rootManager:   RootShellManager,
    val shizukuManager:        ShizukuShellManager,
) : ViewModel() {

    // ── Permission / access state ─────────────────────────────────────────────

    val isRootAvailable: Boolean
        get() = rootManager.isRootAvailable

    val hasOverlayPermission: Boolean
        get() = Settings.canDrawOverlays(context)

    /** Reactive: updated when Shizuku permission is granted or revoked. */
    private val _shizukuGranted = MutableStateFlow(shizukuManager.hasPermission)
    val shizukuGranted: StateFlow<Boolean> = _shizukuGranted.asStateFlow()

    val isShizukuAvailable: Boolean
        get() = shizukuManager.isShizukuAvailable

    /** Listener wired to Shizuku's callback so the UI reacts without a poll. */
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        _shizukuGranted.value = (result == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }

    init {
        shizukuManager.addPermissionResultListener(shizukuPermissionListener)
    }

    override fun onCleared() {
        shizukuManager.removePermissionResultListener(shizukuPermissionListener)
        super.onCleared()
    }

    fun requestShizukuPermission() = shizukuManager.requestPermission()

    // ── Settings ──────────────────────────────────────────────────────────────

    val settings: StateFlow<AppSettings> = settingsRepo.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    // ── Live metric flows ─────────────────────────────────────────────────────

    val cpu     = perfRepo.cpuFlow
    val gpu     = perfRepo.gpuFlow
    val ram     = perfRepo.ramFlow
    val battery = perfRepo.batteryFlow
    val thermal = perfRepo.thermalFlow
    val storage = perfRepo.storageFlow
    val network = perfRepo.networkFlow
    val fps     = perfRepo.fpsFlow

    // ── Service state ─────────────────────────────────────────────────────────

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    fun startService() {
        MonitorService.start(context)
        _serviceRunning.value = true
    }

    fun stopService() {
        MonitorService.stop(context)
        _serviceRunning.value = false
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    fun setOverlayEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setOverlayEnabled(enabled)
        if (enabled) MonitorService.showOverlay(context)
        else         MonitorService.hideOverlay(context)
    }

    // ── Settings mutations ────────────────────────────────────────────────────

    fun setOverlayOpacity(opacity: Float) = viewModelScope.launch {
        settingsRepo.setOverlayOpacity(opacity)
    }

    fun setOverlayTextSize(sp: Float) = viewModelScope.launch {
        settingsRepo.setOverlayTextSize(sp)
    }

    fun setOverlayMode(mode: OverlayMode) = viewModelScope.launch {
        settingsRepo.setOverlayMode(mode)
    }

    fun setVisibleMetrics(metrics: List<MetricType>) = viewModelScope.launch {
        settingsRepo.setVisibleMetrics(metrics)
    }

    fun setAutoStart(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setAutoStart(enabled)
    }

    fun setUseShizuku(enabled: Boolean) = viewModelScope.launch {
        settingsRepo.setUseShizuku(enabled)
    }

    fun setIntervals(
        fpsMs:     Long,
        cpuMs:     Long,
        gpuMs:     Long,
        ramMs:     Long,
        networkMs: Long,
        batteryMs: Long,
        thermalMs: Long,
        storageMs: Long,
    ) = viewModelScope.launch {
        settingsRepo.setIntervals(
            settings.value.copy(
                fpsIntervalMs     = fpsMs,
                cpuIntervalMs     = cpuMs,
                gpuIntervalMs     = gpuMs,
                ramIntervalMs     = ramMs,
                networkIntervalMs = networkMs,
                batteryIntervalMs = batteryMs,
                thermalIntervalMs = thermalMs,
                storageIntervalMs = storageMs,
            )
        )
    }
}
