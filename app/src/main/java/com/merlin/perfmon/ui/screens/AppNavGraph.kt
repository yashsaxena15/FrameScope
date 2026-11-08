package com.merlin.perfmon.ui.screens

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.merlin.perfmon.ui.AlertsViewModel
import com.merlin.perfmon.ui.BenchmarkViewModel
import com.merlin.perfmon.ui.ExportViewModel
import com.merlin.perfmon.ui.MainViewModel

private object Routes {
    const val DASHBOARD = "dashboard"
    const val SETTINGS  = "settings"
    const val THERMAL   = "thermal"
    const val BATTERY   = "battery"
    const val GRAPHS    = "graphs"
    const val BENCHMARK = "benchmark"
    const val EXPORT    = "export"
    const val ALERTS    = "alerts"
}

@Composable
fun AppNavGraph(
    mainViewModel: MainViewModel,
    onRequestOverlayPermission: () -> Unit,
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.DASHBOARD) {

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                viewModel            = mainViewModel,
                onNavigateSettings   = { navController.navigate(Routes.SETTINGS) },
                onNavigateThermal    = { navController.navigate(Routes.THERMAL) },
                onNavigateBattery    = { navController.navigate(Routes.BATTERY) },
                onNavigateGraphs     = { navController.navigate(Routes.GRAPHS) },
                onNavigateBenchmark  = { navController.navigate(Routes.BENCHMARK) },
                onNavigateExport     = { navController.navigate(Routes.EXPORT) },
                onNavigateAlerts     = { navController.navigate(Routes.ALERTS) },
                onRequestOverlayPerm = onRequestOverlayPermission,
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = mainViewModel,
                onBack    = { navController.popBackStack() },
            )
        }

        composable(Routes.THERMAL) {
            ThermalScreen(
                viewModel = mainViewModel,
                onBack    = { navController.popBackStack() },
            )
        }

        composable(Routes.BATTERY) {
            BatteryScreen(
                viewModel = mainViewModel,
                onBack    = { navController.popBackStack() },
            )
        }

        composable(Routes.GRAPHS) {
            GraphScreen(
                viewModel = mainViewModel,
                onBack    = { navController.popBackStack() },
            )
        }

        composable(Routes.BENCHMARK) {
            // BenchmarkViewModel is scoped to this back-stack entry via hiltViewModel()
            BenchmarkScreen(
                onBack    = { navController.popBackStack() },
                viewModel = hiltViewModel<BenchmarkViewModel>(),
            )
        }

        composable(Routes.EXPORT) {
            ExportScreen(
                onBack    = { navController.popBackStack() },
                viewModel = hiltViewModel<ExportViewModel>(),
            )
        }

        composable(Routes.ALERTS) {
            AlertsScreen(
                onBack    = { navController.popBackStack() },
                viewModel = hiltViewModel<AlertsViewModel>(),
            )
        }
    }
}
