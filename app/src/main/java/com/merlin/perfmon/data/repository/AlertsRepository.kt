package com.merlin.perfmon.data.repository

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.merlin.perfmon.R
import com.merlin.perfmon.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.alertsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "perf_mon_alerts"
)

private const val ALERT_CHANNEL_ID = "perf_mon_alerts_channel"

@Singleton
class AlertsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val perfRepository: PerformanceRepository,
) {
    private val ds = context.alertsDataStore
    private val notifManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Key that holds all rules serialised as a pipe-delimited string of JSON-like entries.
    // Format per rule:  id|metricName|conditionName|threshold|enabled|label
    private val KEY_RULES = stringPreferencesKey("alert_rules_v1")

    // Track which alert IDs have already fired (reset when the condition clears).
    private val firedAlerts = mutableSetOf<String>()

    // ── Public flow ───────────────────────────────────────────────────────────

    val rulesFlow: Flow<List<AlertRule>> = ds.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> deserialise(prefs[KEY_RULES] ?: "") }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    suspend fun addRule(rule: AlertRule) {
        ds.edit { prefs ->
            val current = deserialise(prefs[KEY_RULES] ?: "").toMutableList()
            current.removeAll { it.id == rule.id }
            current.add(rule)
            prefs[KEY_RULES] = serialise(current)
        }
    }

    suspend fun removeRule(id: String) {
        ds.edit { prefs ->
            val current = deserialise(prefs[KEY_RULES] ?: "").toMutableList()
            current.removeAll { it.id == id }
            prefs[KEY_RULES] = serialise(current)
        }
        firedAlerts.remove(id)
    }

    suspend fun setRuleEnabled(id: String, enabled: Boolean) {
        ds.edit { prefs ->
            val current = deserialise(prefs[KEY_RULES] ?: "").map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            prefs[KEY_RULES] = serialise(current)
        }
    }

    // ── Evaluation engine ─────────────────────────────────────────────────────

    /**
     * Start evaluating rules against the live snapshot flow.
     * Called once from the MonitorService.
     */
    fun startEvaluating() {
        createNotificationChannel()
        scope.launch {
            combine(rulesFlow, perfRepository.snapshotFlow) { rules, snapshot ->
                Pair(rules, snapshot)
            }.collect { (rules, snapshot) ->
                rules.filter { it.enabled }.forEach { rule ->
                    val value = extractValue(rule.metric, snapshot) ?: return@forEach
                    val triggered = when (rule.condition) {
                        AlertCondition.ABOVE -> value > rule.threshold
                        AlertCondition.BELOW -> value < rule.threshold
                    }
                    if (triggered && rule.id !in firedAlerts) {
                        firedAlerts.add(rule.id)
                        fireNotification(rule, value)
                    } else if (!triggered) {
                        firedAlerts.remove(rule.id)
                    }
                }
            }
        }
    }

    // ── Serialisation (no external JSON library required) ─────────────────────

    private fun serialise(rules: List<AlertRule>): String =
        rules.joinToString(separator = "\n") { r ->
            listOf(r.id, r.metric.name, r.condition.name, r.threshold, r.enabled, r.label)
                .joinToString("|")
        }

    private fun deserialise(raw: String): List<AlertRule> {
        if (raw.isBlank()) return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 6) return@mapNotNull null
            try {
                AlertRule(
                    id        = parts[0],
                    metric    = MetricType.valueOf(parts[1]),
                    condition = AlertCondition.valueOf(parts[2]),
                    threshold = parts[3].toFloat(),
                    enabled   = parts[4].toBoolean(),
                    label     = parts[5],
                )
            } catch (_: Exception) { null }
        }
    }

    // ── Value extraction ──────────────────────────────────────────────────────

    private fun extractValue(metric: MetricType, snap: PerformanceSnapshot): Float? =
        when (metric) {
            MetricType.FPS             -> (snap.fps.currentFps as? MetricValue.Available)?.value
            MetricType.CPU_USAGE       -> (snap.cpu.totalUsagePercent as? MetricValue.Available)?.value
            MetricType.CPU_TEMP        -> (snap.cpu.temperatureCelsius as? MetricValue.Available)?.value
            MetricType.CPU_FREQ        -> (snap.cpu.bigClusterFreqMhz as? MetricValue.Available)?.value?.toFloat()
            MetricType.GPU_USAGE       -> (snap.gpu.utilizationPercent as? MetricValue.Available)?.value?.toFloat()
            MetricType.GPU_FREQ        -> (snap.gpu.frequencyMhz as? MetricValue.Available)?.value?.toFloat()
            MetricType.GPU_MEM         -> (snap.gpu.memoryBytes as? MetricValue.Available)?.value
                ?.div(1_048_576f)
            MetricType.RAM_USED        -> when (val v = snap.ram.usedBytes) {
                is MetricValue.Available -> v.value / 1_048_576f
                else -> null
            }
            MetricType.BATTERY_LEVEL   -> (snap.battery.capacityPercent as? MetricValue.Available)?.value?.toFloat()
            MetricType.BATTERY_TEMP    -> (snap.battery.temperatureCelsius as? MetricValue.Available)?.value
            MetricType.BATTERY_CURRENT -> (snap.battery.currentMa as? MetricValue.Available)?.value
            MetricType.BATTERY_POWER   -> (snap.battery.powerMw as? MetricValue.Available)?.value
            MetricType.NETWORK_DOWN    -> (snap.network.downloadBps as? MetricValue.Available)?.value?.div(1024f)
            MetricType.NETWORK_UP      -> (snap.network.uploadBps as? MetricValue.Available)?.value?.div(1024f)
            MetricType.THERMAL_ZONES   -> snap.thermal.zones
                .firstOrNull { it.index == 3 }
                ?.temperatureCelsius
                ?.let { (it as? MetricValue.Available)?.value }
            MetricType.STORAGE_USED    -> when (val v = snap.storage.usedBytes) {
                is MetricValue.Available -> v.value / 1_048_576f
                else -> null
            }
        }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ALERT_CHANNEL_ID,
            "Performance Alerts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Hardware performance threshold alerts"
        }
        notifManager.createNotificationChannel(channel)
    }

    private fun fireNotification(rule: AlertRule, value: Float) {
        val notifId = rule.id.hashCode()
        val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_monitor)
            .setContentTitle("Performance Alert")
            .setContentText(
                "${rule.displayLabel} — current: ${"%.1f".format(value)}"
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        notifManager.notify(notifId, notif)
    }
}
