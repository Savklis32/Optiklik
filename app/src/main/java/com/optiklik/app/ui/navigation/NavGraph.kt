package com.optiklik.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.optiklik.app.OptiklikApp
import com.optiklik.app.ui.batch.BatchScanScreen
import com.optiklik.app.ui.batch.BatchScanViewModel
import com.optiklik.app.ui.directory.StudentDirectoryScreen
import com.optiklik.app.ui.directory.StudentDirectoryViewModel
import com.optiklik.app.ui.home.HomeScreen
import com.optiklik.app.ui.home.HomeViewModel
import com.optiklik.app.ui.results.ExamResultsScreen
import com.optiklik.app.ui.results.ExamResultsViewModel
import com.optiklik.app.ui.scanner.LiveScannerScreen
import com.optiklik.app.ui.scanner.LiveScannerViewModel

object Routes {
    const val HOME = "home"
    const val STUDENT_DIRECTORY = "student_directory"
    const val LIVE_SCAN = "live_scan/{sessionId}"
    const val BATCH_SCAN = "batch_scan/{sessionId}"
    const val RESULTS = "results/{sessionId}"

    fun liveScan(sessionId: Long) = "live_scan/$sessionId"
    fun batchScan(sessionId: Long) = "batch_scan/$sessionId"
    fun results(sessionId: Long) = "results/$sessionId"
}

@Composable
fun OptiklikNavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val app = context.applicationContext as OptiklikApp
    val repository = app.repository

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(
                factory = HomeViewModel.Factory(repository, app.teacherProfile)
            )
            HomeScreen(
                viewModel = vm,
                onOpenStudentDirectory = { navController.navigate(Routes.STUDENT_DIRECTORY) },
                onOpenLiveScanner = { id -> navController.navigate(Routes.liveScan(id)) },
                onOpenBatchScan = { id -> navController.navigate(Routes.batchScan(id)) },
                onOpenResults = { id -> navController.navigate(Routes.results(id)) }
            )
        }

        composable(Routes.STUDENT_DIRECTORY) {
            val vm: StudentDirectoryViewModel = viewModel(
                factory = StudentDirectoryViewModel.Factory(repository, context)
            )
            StudentDirectoryScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.LIVE_SCAN,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) { entry ->
            val sessionId = entry.arguments?.getLong("sessionId") ?: 0L
            val vm: LiveScannerViewModel = viewModel(
                factory = LiveScannerViewModel.Factory(sessionId, repository)
            )
            LiveScannerScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.BATCH_SCAN,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) { entry ->
            val sessionId = entry.arguments?.getLong("sessionId") ?: 0L
            val vm: BatchScanViewModel = viewModel(
                factory = BatchScanViewModel.Factory(sessionId, repository, context)
            )
            BatchScanScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onViewResults = {
                    navController.navigate(Routes.results(sessionId)) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }

        composable(
            route = Routes.RESULTS,
            arguments = listOf(navArgument("sessionId") { type = NavType.LongType })
        ) { entry ->
            val sessionId = entry.arguments?.getLong("sessionId") ?: 0L
            val vm: ExamResultsViewModel = viewModel(
                factory = ExamResultsViewModel.Factory(sessionId, repository, context)
            )
            ExamResultsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
