package com.merlin.perfmon.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.compositionContext
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.merlin.perfmon.data.repository.PerformanceRepository
import com.merlin.perfmon.data.repository.SettingsRepository
import com.merlin.perfmon.ui.theme.PerfMonTheme
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OverlayManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val perfRepo:     PerformanceRepository,
    private val settingsRepo: SettingsRepository,
) {
    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayView: ComposeView? = null
    private var positionJob: Job?         = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val isShowing: Boolean get() = overlayView != null

    suspend fun toggle() {
        if (isShowing) hide() else show()
        settingsRepo.setOverlayEnabled(isShowing)
    }

    fun show() {
        if (overlayView != null) return

        val lifecycleOwner = OverlayLifecycleOwner().also {
            it.performRestore(null)
            it.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        val composeView = ComposeView(context)

        // Wire lifecycle owners so collectAsStateWithLifecycle() works inside the overlay.
        composeView.setViewTreeLifecycleOwner(lifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        // Attach a Recomposer — required for Compose to run outside an Activity window.
        val recomposeScope = CoroutineScope(AndroidUiDispatcher.Main)
        val recomposer     = Recomposer(AndroidUiDispatcher.Main)
        composeView.compositionContext = recomposer
        recomposeScope.launch { recomposer.runRecomposeAndApplyChanges() }

        composeView.setContent {
            PerfMonTheme(darkTheme = true) {
                OverlayContent(
                    perfRepo     = perfRepo,
                    settingsRepo = settingsRepo,
                    onMove       = { dx, dy ->
                        params.x = (params.x + dx).coerceAtLeast(0)
                        params.y = (params.y + dy).coerceAtLeast(0)
                        try {
                            windowManager.updateViewLayout(composeView, params)
                        } catch (_: Exception) {}
                        scope.launch {
                            settingsRepo.setOverlayPosition(
                                params.x.toFloat(),
                                params.y.toFloat(),
                            )
                        }
                    }
                )
            }
        }

        windowManager.addView(composeView, params)
        overlayView = composeView

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        // Restore saved position when settings change
        positionJob = scope.launch {
            settingsRepo.settings.collect { s ->
                params.x = s.overlayX.toInt().coerceAtLeast(0)
                params.y = s.overlayY.toInt().coerceAtLeast(0)
                try {
                    windowManager.updateViewLayout(composeView, params)
                } catch (_: Exception) {}
            }
        }
    }

    fun hide() {
        positionJob?.cancel()
        positionJob = null
        overlayView?.let { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        overlayView = null
    }
}

/**
 * Minimal LifecycleOwner + SavedStateRegistryOwner that lets Compose work
 * inside a WindowManager-hosted view without an Activity.
 */
private class OverlayLifecycleOwner :
    LifecycleOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val ssrc              = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = ssrc.savedStateRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) =
        lifecycleRegistry.handleLifecycleEvent(event)

    fun performRestore(savedState: Bundle?) =
        ssrc.performRestore(savedState)
}
