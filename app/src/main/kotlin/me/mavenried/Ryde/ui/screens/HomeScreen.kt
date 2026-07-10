package me.mavenried.Ryde.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.SportsScore
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Navigation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.location.Geocoder
import androidx.compose.material3.FilterChip
import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.domain.model.IntervalWorkout
import me.mavenried.Ryde.service.TrackingState
import me.mavenried.Ryde.ui.components.*
import me.mavenried.Ryde.ui.viewmodel.DestinationPoint
import me.mavenried.Ryde.ui.viewmodel.TrackingViewModel
import me.mavenried.Ryde.util.PermissionHelper
import me.mavenried.Ryde.util.UserPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

@Composable
fun HomeScreen(
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    vm: TrackingViewModel = viewModel()
) {
    val state by vm.trackingState.collectAsState()
    val crashRoute by vm.crashRecoveryRoute.collectAsState()
    val overlayPoints by vm.overlayPoints.collectAsState()
    val allRoutes by vm.allRoutes.collectAsState()
    val destination by vm.destination.collectAsState()

    var selectedActivity by remember { mutableStateOf(ActivityType.CYCLING) }
    var showStopDialog by remember { mutableStateOf(false) }
    var showOnboarding by remember { mutableStateOf(false) }
    var showOverlaySelector by remember { mutableStateOf(false) }
    var goalDistanceKm by remember { mutableStateOf<Double?>(null) }
    var goalDurationMs by remember { mutableStateOf<Long?>(null) }
    var intervalWorkout by remember { mutableStateOf<IntervalWorkout?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (!UserPrefs.isOnboarded(context)) showOnboarding = true
    }

    if (showOnboarding) {
        OnboardingDialog(onDone = { showOnboarding = false })
    }

    // Crash recovery dialog
    crashRoute?.let { cr ->
        val activityLabel = when (cr.activityType) {
            ActivityType.CYCLING -> "ride"
            ActivityType.RUNNING -> "run"
            ActivityType.WALKING -> "walk"
        }
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(cr.startTime))
        AlertDialog(
            onDismissRequest = { vm.discardCrashedRoute() },
            title = { Text("Resume $activityLabel?") },
            text = { Text("A $activityLabel from $timeStr wasn't saved properly. Resume it or discard it.") },
            confirmButton = {
                TextButton(onClick = { vm.resumeCrashedTracking() }) { Text("Resume") }
            },
            dismissButton = {
                TextButton(onClick = { vm.discardCrashedRoute() }) { Text("Discard") }
            }
        )
    }

    // Overlay selector dialog
    if (showOverlaySelector) {
        AlertDialog(
            onDismissRequest = { showOverlaySelector = false },
            title = { Text("Select overlay route") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (allRoutes.isEmpty()) {
                        Text(
                            "No saved routes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        allRoutes.take(15).forEach { route ->
                            TextButton(
                                onClick = { vm.setOverlayRoute(route.id); showOverlaySelector = false },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(route.name, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                if (overlayPoints.isNotEmpty()) {
                    TextButton(onClick = { vm.clearOverlay(); showOverlaySelector = false }) {
                        Text("Clear overlay")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlaySelector = false }) { Text("Cancel") }
            }
        )
    }

    val bgLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { vm.startTracking(selectedActivity) }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                !PermissionHelper.hasBackgroundLocationPermission(context)
            ) {
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            } else {
                vm.startTracking(selectedActivity)
            }
        }
    }

    fun onStartPressed() {
        when {
            !PermissionHelper.hasLocationPermission(context) ->
                locationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    !PermissionHelper.hasBackgroundLocationPermission(context) ->
                bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            else -> vm.startTracking(selectedActivity, goalDistanceKm, goalDurationMs, intervalWorkout)
        }
    }

    when (val s = state) {
        is TrackingState.Idle -> IdleContent(
            selected = selectedActivity,
            onSelect = { selectedActivity = it },
            onStart = ::onStartPressed,
            onHistory = onNavigateToHistory,
            onSettings = onNavigateToSettings,
            overlayPoints = overlayPoints,
            onShowOverlaySelector = { showOverlaySelector = true },
            hasOverlay = overlayPoints.isNotEmpty(),
            goalDistanceKm = goalDistanceKm,
            goalDurationMs = goalDurationMs,
            onGoalChange = { dist, dur -> goalDistanceKm = dist; goalDurationMs = dur },
            intervalWorkout = intervalWorkout,
            onIntervalChange = { intervalWorkout = it },
        )
        is TrackingState.Active -> ActiveContent(
            state = s,
            onPauseResume = { if (s.isPaused) vm.resumeTracking() else vm.pauseTracking() },
            onStop = { showStopDialog = true },
            overlayPoints = overlayPoints,
            onShowOverlaySelector = { showOverlaySelector = true },
            hasOverlay = overlayPoints.isNotEmpty(),
            destination = destination,
            onSetDestination = { lat, lng, label -> vm.setDestination(lat, lng, label) },
            onClearDestination = { vm.clearDestination() }
        )
    }

    if (showStopDialog) {
        val activeState = state as? TrackingState.Active
        val isShort = activeState != null &&
                activeState.elapsedMs < 60_000L && activeState.distanceKm < 0.1
        var selectedTag by remember { mutableStateOf("Other") }

        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text(if (isShort) "Short ride" else "End ride?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (isShort) {
                        val mins = (activeState!!.elapsedMs / 60_000L)
                        val secs = (activeState.elapsedMs % 60_000L) / 1_000L
                        Text("Only ${"%d:%02d".format(mins, secs)} and ${"%.2f".format(activeState.distanceKm)} km — worth saving?")
                    } else {
                        Text("Save your route to history, or discard it entirely.")
                    }
                    if (!isShort) {
                        Text("Tag", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        me.mavenried.Ryde.ui.viewmodel.ROUTE_TAGS.chunked(3).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { tag ->
                                    FilterChip(
                                        selected = selectedTag == tag,
                                        onClick = { selectedTag = tag },
                                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    goalDistanceKm = null
                    goalDurationMs = null
                    intervalWorkout = null
                    vm.stopTracking(selectedTag)
                }) { Text(if (isShort) "Save anyway" else "Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showStopDialog = false
                        goalDistanceKm = null
                        goalDurationMs = null
                        intervalWorkout = null
                        vm.discardTracking()
                    }) {
                        Text(
                            "Discard",
                            color = if (isShort) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                        )
                    }
                    TextButton(onClick = { showStopDialog = false }) { Text("Cancel") }
                }
            }
        )
    }
}

@Composable
private fun OnboardingDialog(onDone: () -> Unit) {
    val context = LocalContext.current
    var useLbs by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("70") }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = {},
        title = { Text("Welcome to RYDE") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Enter your body weight so we can calculate accurate calorie burn.")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it; isError = false },
                        modifier = Modifier.weight(1f),
                        label = { Text(if (useLbs) "lbs" else "kg") },
                        isError = isError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    @OptIn(ExperimentalMaterial3Api::class)
                    SingleChoiceSegmentedButtonRow {
                        SegmentedButton(
                            selected = !useLbs,
                            onClick = {
                                if (useLbs) {
                                    useLbs = false
                                    inputText.toDoubleOrNull()?.let {
                                        inputText = "%.1f".format(UserPrefs.lbsToKg(it))
                                    }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            label = { Text("kg") }
                        )
                        SegmentedButton(
                            selected = useLbs,
                            onClick = {
                                if (!useLbs) {
                                    useLbs = true
                                    inputText.toDoubleOrNull()?.let {
                                        inputText = "%.1f".format(UserPrefs.kgToLbs(it))
                                    }
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            label = { Text("lbs") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = inputText.toDoubleOrNull()
                if (parsed == null || parsed <= 0) { isError = true; return@Button }
                val kg = if (useLbs) UserPrefs.lbsToKg(parsed) else parsed
                UserPrefs.setWeightKg(context, kg)
                UserPrefs.setUseLbs(context, useLbs)
                UserPrefs.setOnboarded(context)
                onDone()
            }) {
                Text("Get started")
            }
        }
    )
}

@Composable
private fun IdleContent(
    selected: ActivityType,
    onSelect: (ActivityType) -> Unit,
    onStart: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
    overlayPoints: List<List<me.mavenried.Ryde.domain.model.LocationPoint>>,
    onShowOverlaySelector: () -> Unit,
    hasOverlay: Boolean,
    goalDistanceKm: Double?,
    goalDurationMs: Long?,
    onGoalChange: (Double?, Long?) -> Unit,
    intervalWorkout: IntervalWorkout?,
    onIntervalChange: (IntervalWorkout?) -> Unit,
) {
    var showGoalDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var goalMode by remember { mutableStateOf(if (goalDurationMs != null) "time" else "distance") }
    var goalDistText by remember { mutableStateOf(goalDistanceKm?.let { "%.1f".format(it) } ?: "") }
    var goalMinText by remember { mutableStateOf(goalDurationMs?.let { (it / 60_000L).toString() } ?: "") }
    Box(modifier = Modifier.fillMaxSize()) {
        TrackingMapView(
            points = emptyList(),
            activityType = selected,
            modifier = Modifier.fillMaxSize(),
            overlayRoutes = overlayPoints
        )

        // Top-right: settings
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(12.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.80f),
                    RoundedCornerShape(12.dp)
                )
        ) {
            IconButton(onClick = onSettings) {
                Icon(
                    Icons.Rounded.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Top-left: overlay button
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(12.dp)
                .background(
                    if (hasOverlay) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.80f),
                    RoundedCornerShape(12.dp)
                )
        ) {
            IconButton(onClick = onShowOverlaySelector) {
                Icon(
                    Icons.Rounded.Layers,
                    contentDescription = "Overlay route",
                    tint = if (hasOverlay) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp
        ) {
            Column(modifier = Modifier.navigationBarsPadding()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(top = 12.dp, bottom = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    RydeLogo(
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(36.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    ActivityPicker(selected = selected, onSelect = onSelect)
                    Spacer(modifier = Modifier.height(12.dp))

                    if (showIntervalDialog) {
                        IntervalDialog(
                            current = intervalWorkout,
                            onConfirm = { onIntervalChange(it); showIntervalDialog = false },
                            onDismiss = { showIntervalDialog = false }
                        )
                    }
                    if (showGoalDialog) {
                        GoalDialog(
                            goalMode = goalMode,
                            goalDistText = goalDistText,
                            goalMinText = goalMinText,
                            onModeChange = { mode ->
                                goalMode = mode
                                onGoalChange(null, null)
                            },
                            onDistTextChange = { v ->
                                goalDistText = v
                                onGoalChange(v.toDoubleOrNull()?.takeIf { it > 0 }, null)
                            },
                            onMinTextChange = { v ->
                                goalMinText = v
                                onGoalChange(null, v.toLongOrNull()?.takeIf { it > 0 }?.let { it * 60_000L })
                            },
                            onClear = {
                                goalDistText = ""
                                goalMinText = ""
                                onGoalChange(null, null)
                            },
                            onDismiss = { showGoalDialog = false }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val goalSet = goalDistanceKm != null || goalDurationMs != null
                        Button(
                            onClick = { showGoalDialog = true },
                            contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp)
                        ) {
                            Icon(Icons.Rounded.SportsScore, contentDescription = "Goal", modifier = Modifier.size(18.dp))
                            if (goalSet) {
                                Spacer(modifier = Modifier.width(6.dp))
                                val label = when {
                                    goalDistanceKm != null -> "%.1f km".format(goalDistanceKm)
                                    else -> "${goalDurationMs!! / 60_000L} min"
                                }
                                Text(label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Button(
                            onClick = { showIntervalDialog = true },
                            contentPadding = PaddingValues(vertical = 14.dp, horizontal = 16.dp)
                        ) {
                            Icon(Icons.Rounded.Loop, contentDescription = "Intervals", modifier = Modifier.size(18.dp))
                            if (intervalWorkout != null) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("${intervalWorkout.repeat}×", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        Button(
                            onClick = onStart,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 14.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                MusicRow()
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                TextButton(
                    onClick = onHistory,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 4.dp)
                ) {
                    Icon(Icons.Rounded.History, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("History")
                }
            }
        }
    }
}

@Composable
private fun ActiveContent(
    state: TrackingState.Active,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    overlayPoints: List<List<me.mavenried.Ryde.domain.model.LocationPoint>>,
    onShowOverlaySelector: () -> Unit,
    hasOverlay: Boolean,
    destination: DestinationPoint? = null,
    onSetDestination: (Double, Double, String) -> Unit = { _, _, _ -> },
    onClearDestination: () -> Unit = {}
) {
    var recenterTrigger by remember { mutableStateOf(0) }
    var panelHeightPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showDestSearch by remember { mutableStateOf(false) }
    var destSearchText by remember { mutableStateOf("") }
    var destSearchError by remember { mutableStateOf(false) }
    var destSearchBusy by remember { mutableStateOf(false) }

    if (showDestSearch) {
        AlertDialog(
            onDismissRequest = { showDestSearch = false; destSearchText = "" },
            title = { Text("Set destination") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = destSearchText,
                        onValueChange = { destSearchText = it; destSearchError = false },
                        label = { Text("Address or place") },
                        singleLine = true,
                        isError = destSearchError,
                        supportingText = if (destSearchError) { { Text("Place not found") } } else null
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = destSearchText.isNotBlank() && !destSearchBusy,
                    onClick = {
                        scope.launch {
                            destSearchBusy = true
                            val result = withContext(Dispatchers.IO) {
                                try {
                                    @Suppress("DEPRECATION")
                                    Geocoder(context, Locale.getDefault())
                                        .getFromLocationName(destSearchText.trim(), 1)
                                        ?.firstOrNull()
                                } catch (_: Exception) { null }
                            }
                            destSearchBusy = false
                            if (result != null) {
                                onSetDestination(result.latitude, result.longitude, destSearchText.trim())
                                showDestSearch = false
                                destSearchText = ""
                            } else {
                                destSearchError = true
                            }
                        }
                    }
                ) { Text(if (destSearchBusy) "Searching..." else "Go") }
            },
            dismissButton = {
                TextButton(onClick = { showDestSearch = false; destSearchText = "" }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        TrackingMapView(
            points = state.points,
            activityType = state.activityType,
            modifier = Modifier.fillMaxSize(),
            recenterTrigger = recenterTrigger,
            overlayRoutes = overlayPoints,
            destination = destination
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.80f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            RydeLogo(
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.height(22.dp)
            )
        }
        val panelHeightDp = with(density) { panelHeightPx.toDp() }

        // Overlay toggle button
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = panelHeightDp + 72.dp, end = 16.dp)
                .background(
                    if (hasOverlay) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f)
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(12.dp)
                )
        ) {
            IconButton(onClick = onShowOverlaySelector) {
                Icon(
                    Icons.Rounded.Layers,
                    contentDescription = "Overlay",
                    tint = if (hasOverlay) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        SmallFloatingActionButton(
            onClick = { recenterTrigger++ },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = panelHeightDp + 16.dp, end = 16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Icon(Icons.Rounded.MyLocation, contentDescription = "Recenter")
        }

        // Destination search button
        SmallFloatingActionButton(
            onClick = { showDestSearch = true },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(bottom = panelHeightDp + 16.dp, start = 16.dp),
            containerColor = if (destination != null)
                MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (destination != null)
                MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurface
        ) {
            Icon(Icons.Rounded.Search, contentDescription = "Set destination")
        }

        // Destination bearing chip
        destination?.let { dest ->
            val lastPt = state.points.lastOrNull()
            if (lastPt != null) {
                val dLat = Math.toRadians(dest.lat - lastPt.lat)
                val dLng = Math.toRadians(dest.lng - lastPt.lng)
                val lat1 = Math.toRadians(lastPt.lat)
                val lat2 = Math.toRadians(dest.lat)
                val a = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLng / 2).pow(2)
                val distKm = 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
                val y = sin(dLng) * cos(lat2)
                val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLng)
                val bearingDeg = (Math.toDegrees(atan2(y, x)).toFloat() + 360f) % 360f

                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Navigation,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "%.1f km  •  %.0f°".format(distKm, bearingDeg),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (dest.label.isNotBlank()) {
                            Text(
                                "→ ${dest.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                        IconButton(
                            onClick = onClearDestination,
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Clear destination",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        ActiveBottomPanel(
            state = state,
            onPauseResume = onPauseResume,
            onStop = onStop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { panelHeightPx = it.height }
        )
    }
}

@Composable
private fun IntervalDialog(
    current: IntervalWorkout?,
    onConfirm: (IntervalWorkout?) -> Unit,
    onDismiss: () -> Unit,
) {
    var workText by remember { mutableStateOf(current?.let { (it.workMs / 1_000L).toString() } ?: "60") }
    var restText by remember { mutableStateOf(current?.let { (it.restMs / 1_000L).toString() } ?: "30") }
    var repeatText by remember { mutableStateOf(current?.repeat?.toString() ?: "8") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Interval workout") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = workText,
                    onValueChange = { workText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Work (seconds)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = restText,
                    onValueChange = { restText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Rest (seconds)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = repeatText,
                    onValueChange = { repeatText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Repeat") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val work = workText.toLongOrNull()?.takeIf { it > 0 }?.times(1_000L)
                val rest = restText.toLongOrNull()?.takeIf { it > 0 }?.times(1_000L)
                val repeat = repeatText.toIntOrNull()?.takeIf { it > 0 }
                if (work != null && rest != null && repeat != null) {
                    onConfirm(IntervalWorkout(work, rest, repeat))
                } else {
                    onDismiss()
                }
            }) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(null) }) { Text("Clear") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDialog(
    goalMode: String,
    goalDistText: String,
    goalMinText: String,
    onModeChange: (String) -> Unit,
    onDistTextChange: (String) -> Unit,
    onMinTextChange: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = goalMode == "distance",
                        onClick = { onModeChange("distance") },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        label = { Text("Distance") }
                    )
                    SegmentedButton(
                        selected = goalMode == "time",
                        onClick = { onModeChange("time") },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        label = { Text("Time") }
                    )
                }
                if (goalMode == "distance") {
                    OutlinedTextField(
                        value = goalDistText,
                        onValueChange = onDistTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Distance (km)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                } else {
                    OutlinedTextField(
                        value = goalMinText,
                        onValueChange = onMinTextChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Duration (minutes)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Set") }
        },
        dismissButton = {
            TextButton(onClick = { onClear(); onDismiss() }) { Text("Clear") }
        }
    )
}
