package me.mavenried.Ryde.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import me.mavenried.Ryde.service.TrackingService
import me.mavenried.Ryde.service.TrackingState
import me.mavenried.Ryde.ui.components.UpdateDialog
import me.mavenried.Ryde.ui.nav.AppNavigation
import me.mavenried.Ryde.ui.theme.RydeTheme
import me.mavenried.Ryde.util.UpdateInstaller
import me.mavenried.Ryde.util.UpdateManager
import me.mavenried.Ryde.util.UserPrefs

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* location + notification. Handled at use-site via PermissionHelper */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UserPrefs.initTheme(this)
        UserPrefs.initMetric(this)
        requestInitialPermissions()
        setContent {
            val scope = rememberCoroutineScope()
            val updateInfo by UpdateManager.updateInfo.collectAsState()
            var downloadProgress by remember { mutableFloatStateOf(-1f) }
            var downloadReady by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) { UpdateManager.checkAsync() }
            LaunchedEffect(updateInfo) {
                if (updateInfo == null) { downloadProgress = -1f; downloadReady = false }
            }

            val theme by UserPrefs.themeFlow.collectAsState()
            val isMetric by UserPrefs.metricsFlow.collectAsState()
            val trackingState by TrackingService.globalState.collectAsState()
            val activeState = trackingState as? TrackingState.Active
            val isRiding = activeState != null

            DisposableEffect(isRiding, activeState?.activityType) {
                val keepOn = activeState != null &&
                    UserPrefs.isKeepScreenOn(this@MainActivity, activeState.activityType)
                if (keepOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            val systemDark = isSystemInDarkTheme()
            val darkTheme = when {
                isRiding && UserPrefs.isLightModeRiding(this@MainActivity) -> false
                theme == "dark" -> true
                theme == "light" -> false
                else -> systemDark
            }

            RydeTheme(darkTheme = darkTheme, isMetric = isMetric) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation()

                    updateInfo?.let { info ->
                        UpdateDialog(
                            info = info,
                            downloading = downloadProgress >= 0f && !downloadReady,
                            downloadProgress = downloadProgress.coerceAtLeast(0f),
                            readyToInstall = downloadReady,
                            onDownload = {
                                downloadProgress = 0f
                                scope.launch {
                                    UpdateInstaller.download(this@MainActivity, info.apkUrl) { p ->
                                        downloadProgress = p
                                    }
                                    downloadReady = true
                                }
                            },
                            onInstall = { UpdateInstaller.install(this@MainActivity) },
                            onDismiss = { UpdateManager.dismiss() },
                        )
                    }
                }
            }
        }
    }

    private fun requestInitialPermissions() {
        val perms = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(perms.toTypedArray())
    }
}
