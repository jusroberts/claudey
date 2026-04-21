package com.wiggletonabbey.wigglebot

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wiggletonabbey.wigglebot.notifications.NotificationHelper
import com.wiggletonabbey.wigglebot.schedule.WorkScheduler
import com.wiggletonabbey.wigglebot.ui.ChatScreen
import com.wiggletonabbey.wigglebot.ui.MainViewModel
import com.wiggletonabbey.wigglebot.ui.SettingsScreen
import com.wiggletonabbey.wigglebot.ui.TmuxSessionScreen
import com.wiggletonabbey.wigglebot.ui.TmuxSessionsScreen
import com.wiggletonabbey.wigglebot.ui.TmuxViewModel
import com.wiggletonabbey.wigglebot.ui.WiggleBotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val tmuxViewModel: TmuxViewModel by viewModels()

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    private val requestHealthPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(healthPermissions)) {
                Log.d("MainActivity", "Health Connect permissions granted")
            } else {
                Log.w("MainActivity", "Health Connect permissions not fully granted — schedule inference disabled")
            }
        }

    private val requestRuntimePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            results.forEach { (perm, granted) ->
                Log.d("MainActivity", "$perm granted=$granted")
            }
            // Request Health Connect after the system dialogs clear.
            requestHealthConnectPermissions()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        NotificationHelper.createChannels(this)
        WorkScheduler.schedule(this)
        requestRuntimePermissions()

        val keyPreview = BuildConfig.GOOGLE_MAPS_API_KEY.take(4).ifEmpty { "(empty)" }
        Log.d("WiggleBot", "GOOGLE_MAPS_API_KEY prefix: $keyPreview")

        enableEdgeToEdge()
        setContent {
            WiggleBotTheme {
                AppNavigation(viewModel, tmuxViewModel)
            }
        }
    }

    private fun requestRuntimePermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (needed.isNotEmpty()) {
            requestRuntimePermissions.launch(needed.toTypedArray())
        } else {
            requestHealthConnectPermissions()
        }
    }

    private fun requestHealthConnectPermissions() {
        val status = HealthConnectClient.getSdkStatus(this)
        Log.d("MainActivity", "Health Connect SDK status: $status " +
            "(AVAILABLE=3, UNAVAILABLE=1, UPDATE_REQUIRED=2)")
        if (status != HealthConnectClient.SDK_AVAILABLE) return
        lifecycleScope.launch {
            val client = HealthConnectClient.getOrCreate(this@MainActivity)
            val granted = client.permissionController.getGrantedPermissions()
            Log.d("MainActivity", "HC granted permissions: $granted")
            Log.d("MainActivity", "HC required permissions: $healthPermissions")
            if (!granted.containsAll(healthPermissions)) {
                Log.d("MainActivity", "Launching HC permission request")
                requestHealthPermissions.launch(healthPermissions)
            } else {
                Log.d("MainActivity", "HC permissions already granted")
            }
        }
    }
}

private object Routes {
    const val CHAT = "chat"
    const val SETTINGS = "settings"
    const val TMUX_SESSIONS = "tmux"
    const val TMUX_SESSION = "tmux/{name}"
    fun tmuxSession(name: String) = "tmux/$name"
}

@Composable
private fun AppNavigation(viewModel: MainViewModel, tmuxViewModel: TmuxViewModel) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.CHAT) {
        composable(Routes.CHAT) {
            ChatScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToTmux = { navController.navigate(Routes.TMUX_SESSIONS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TMUX_SESSIONS) {
            TmuxSessionsScreen(
                viewModel = tmuxViewModel,
                onNavigateBack = { navController.popBackStack() },
                onOpenSession = { name -> navController.navigate(Routes.tmuxSession(name)) },
            )
        }
        composable(
            route = Routes.TMUX_SESSION,
            arguments = listOf(navArgument("name") { type = NavType.StringType }),
        ) { backStackEntry ->
            val name = backStackEntry.arguments?.getString("name") ?: return@composable
            TmuxSessionScreen(
                sessionName = name,
                viewModel = tmuxViewModel,
                onNavigateBack = { navController.popBackStack() },
            )
        }
    }
}
