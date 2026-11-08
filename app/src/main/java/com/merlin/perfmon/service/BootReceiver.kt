package com.merlin.perfmon.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.merlin.perfmon.data.repository.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Starts the MonitorService on device boot when the user has enabled auto-start.
 *
 * Hilt injection into BroadcastReceivers requires @AndroidEntryPoint and that the
 * Application class is annotated with @HiltAndroidApp (done in PerfMonApplication).
 * goAsync() extends the BroadcastReceiver's execution window beyond the normal 10 s
 * to give the DataStore coroutine time to complete.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                if (settings.autoStartOnBoot) {
                    MonitorService.start(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
