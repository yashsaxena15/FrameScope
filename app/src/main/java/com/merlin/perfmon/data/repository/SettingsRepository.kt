package com.merlin.perfmon.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.merlin.perfmon.data.model.AppSettings
import com.merlin.perfmon.data.model.MetricType
import com.merlin.perfmon.data.model.OverlayMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "perf_mon_settings"
)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val ds = context.dataStore

    // ── Keys ─────────────────────────────────────────────────────────────────
    companion object {
        val KEY_OVERLAY_ENABLED       = booleanPreferencesKey("overlay_enabled")
        val KEY_OVERLAY_X             = floatPreferencesKey("overlay_x")
        val KEY_OVERLAY_Y             = floatPreferencesKey("overlay_y")
        val KEY_OVERLAY_OPACITY       = floatPreferencesKey("overlay_opacity")
        val KEY_OVERLAY_TEXT_SIZE     = floatPreferencesKey("overlay_text_size")
        val KEY_OVERLAY_MODE          = stringPreferencesKey("overlay_mode")
        val KEY_VISIBLE_METRICS       = stringPreferencesKey("visible_metrics")
        val KEY_AUTO_START            = booleanPreferencesKey("auto_start")
        val KEY_USE_SHIZUKU           = booleanPreferencesKey("use_shizuku")
        val KEY_FPS_INTERVAL          = longPreferencesKey("fps_interval_ms")
        val KEY_CPU_INTERVAL          = longPreferencesKey("cpu_interval_ms")
        val KEY_GPU_INTERVAL          = longPreferencesKey("gpu_interval_ms")
        val KEY_RAM_INTERVAL          = longPreferencesKey("ram_interval_ms")
        val KEY_NETWORK_INTERVAL      = longPreferencesKey("net_interval_ms")
        val KEY_BATTERY_INTERVAL      = longPreferencesKey("bat_interval_ms")
        val KEY_THERMAL_INTERVAL      = longPreferencesKey("thr_interval_ms")
        val KEY_STORAGE_INTERVAL      = longPreferencesKey("sto_interval_ms")
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    val settings: Flow<AppSettings> = ds.data
        .catch { e ->
            if (e is IOException) emit(emptyPreferences())
            else throw e
        }
        .map { prefs -> prefs.toSettings() }

    private fun Preferences.toSettings(): AppSettings {
        val metricsStr = this[KEY_VISIBLE_METRICS]
        val metrics = if (metricsStr.isNullOrBlank()) {
            MetricType.defaultOrder()
        } else {
            metricsStr.split(",").mapNotNull { name ->
                try { MetricType.valueOf(name) } catch (e: IllegalArgumentException) { null }
            }.ifEmpty { MetricType.defaultOrder() }
        }

        return AppSettings(
            overlayEnabled     = this[KEY_OVERLAY_ENABLED]   ?: false,
            overlayX           = this[KEY_OVERLAY_X]         ?: 0f,
            overlayY           = this[KEY_OVERLAY_Y]         ?: 100f,
            overlayOpacity     = this[KEY_OVERLAY_OPACITY]   ?: 0.85f,
            overlayTextSizeSp  = this[KEY_OVERLAY_TEXT_SIZE] ?: 11f,
            overlayMode        = this[KEY_OVERLAY_MODE]?.let {
                try { OverlayMode.valueOf(it) } catch (e: Exception) { OverlayMode.COMPACT }
            } ?: OverlayMode.COMPACT,
            visibleMetrics     = metrics,
            autoStartOnBoot    = this[KEY_AUTO_START]         ?: false,
            useShizukuFallback = this[KEY_USE_SHIZUKU]        ?: true,
            fpsIntervalMs      = this[KEY_FPS_INTERVAL]       ?: 250L,
            cpuIntervalMs      = this[KEY_CPU_INTERVAL]       ?: 500L,
            gpuIntervalMs      = this[KEY_GPU_INTERVAL]       ?: 500L,
            ramIntervalMs      = this[KEY_RAM_INTERVAL]       ?: 1000L,
            networkIntervalMs  = this[KEY_NETWORK_INTERVAL]   ?: 1000L,
            batteryIntervalMs  = this[KEY_BATTERY_INTERVAL]   ?: 5000L,
            thermalIntervalMs  = this[KEY_THERMAL_INTERVAL]   ?: 5000L,
            storageIntervalMs  = this[KEY_STORAGE_INTERVAL]   ?: 30_000L,
        )
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    suspend fun setOverlayEnabled(enabled: Boolean) = ds.edit {
        it[KEY_OVERLAY_ENABLED] = enabled
    }

    suspend fun setOverlayPosition(x: Float, y: Float) = ds.edit {
        it[KEY_OVERLAY_X] = x
        it[KEY_OVERLAY_Y] = y
    }

    suspend fun setOverlayOpacity(opacity: Float) = ds.edit {
        it[KEY_OVERLAY_OPACITY] = opacity.coerceIn(0.1f, 1f)
    }

    suspend fun setOverlayTextSize(sp: Float) = ds.edit {
        it[KEY_OVERLAY_TEXT_SIZE] = sp.coerceIn(8f, 20f)
    }

    suspend fun setOverlayMode(mode: OverlayMode) = ds.edit {
        it[KEY_OVERLAY_MODE] = mode.name
    }

    suspend fun setVisibleMetrics(metrics: List<MetricType>) = ds.edit {
        it[KEY_VISIBLE_METRICS] = metrics.joinToString(",") { m -> m.name }
    }

    suspend fun setAutoStart(enabled: Boolean) = ds.edit {
        it[KEY_AUTO_START] = enabled
    }

    suspend fun setUseShizuku(enabled: Boolean) = ds.edit {
        it[KEY_USE_SHIZUKU] = enabled
    }

    suspend fun setIntervals(settings: AppSettings) = ds.edit {
        it[KEY_FPS_INTERVAL]     = settings.fpsIntervalMs
        it[KEY_CPU_INTERVAL]     = settings.cpuIntervalMs
        it[KEY_GPU_INTERVAL]     = settings.gpuIntervalMs
        it[KEY_RAM_INTERVAL]     = settings.ramIntervalMs
        it[KEY_NETWORK_INTERVAL] = settings.networkIntervalMs
        it[KEY_BATTERY_INTERVAL] = settings.batteryIntervalMs
        it[KEY_THERMAL_INTERVAL] = settings.thermalIntervalMs
        it[KEY_STORAGE_INTERVAL] = settings.storageIntervalMs
    }
}
