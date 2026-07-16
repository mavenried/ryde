package me.mavenried.Ryde.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.ui.components.ElevationChart
import me.mavenried.Ryde.ui.components.RouteMapView
import me.mavenried.Ryde.ui.components.SpeedChart
import me.mavenried.Ryde.ui.theme.LocalIsMetric
import me.mavenried.Ryde.ui.viewmodel.ROUTE_TAGS
import me.mavenried.Ryde.ui.viewmodel.RouteDetailViewModel
import me.mavenried.Ryde.util.FitExporter
import me.mavenried.Ryde.util.GpxExporter
import me.mavenried.Ryde.util.ShareCardRenderer
import me.mavenried.Ryde.util.UserPrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(
    routeId: String,
    onNavigateBack: () -> Unit,
    vm: RouteDetailViewModel = viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val route by vm.route.collectAsState()
    val points by vm.points.collectAsState()
    val topSpeedKmh by vm.topSpeedKmh.collectAsState()
    val stoppedTimeSec by vm.stoppedTimeSec.collectAsState()
    val lapSplits by vm.lapSplits.collectAsState()
    val isMetric = LocalIsMetric.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var showShareMenu by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var openStravaAfterExport by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri ->
        uri?.let {
            val r = route ?: return@let
            val p = points
            if (GpxExporter.exportToUri(context, it, r, p)) {
                Toast.makeText(context, "Exported successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val fitExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val forStrava = openStravaAfterExport
        openStravaAfterExport = false
        uri?.let {
            val r = route ?: return@let
            val p = points
            scope.launch {
                val ok = withContext(Dispatchers.IO) { FitExporter.exportToUri(context, it, r, p) }
                Toast.makeText(context,
                    if (ok) "FIT exported" else "FIT export failed",
                    Toast.LENGTH_SHORT).show()
                if (ok && forStrava) {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.strava.com/upload/select"))
                    )
                    Toast.makeText(
                        context,
                        "Choose the file you just saved to upload it",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(routeId) { vm.load(routeId) }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename ride") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (renameText.isNotBlank()) vm.renameRoute(routeId, renameText.trim())
                    showRenameDialog = false
                }) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showTagDialog) {
        AlertDialog(
            onDismissRequest = { showTagDialog = false },
            title = { Text("Tag this ride") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ROUTE_TAGS.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { tag ->
                                FilterChip(
                                    selected = route?.category == tag,
                                    onClick = { vm.updateTag(routeId, tag); showTagDialog = false },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showTagDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ride?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { vm.deleteRoute(routeId, onNavigateBack) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            route?.name ?: "Route",
                            modifier = Modifier.clickable {
                                renameText = route?.name ?: ""
                                showRenameDialog = true
                            }
                        )
                        val tag = route?.category
                        if (tag != null && tag != "Other") {
                            Surface(
                                shape = MaterialTheme.shapes.extraSmall,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.clickable { showTagDialog = true }
                            ) {
                                Text(
                                    tag,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showShareMenu = true }) {
                            Icon(Icons.Rounded.Share, contentDescription = "Share")
                        }
                        DropdownMenu(
                            expanded = showShareMenu,
                            onDismissRequest = { showShareMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Export GPX") },
                                onClick = {
                                    showShareMenu = false
                                    val name = route?.name?.replace(" ", "_") ?: "route"
                                    exportLauncher.launch("Ryde_$name.gpx")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export FIT") },
                                onClick = {
                                    showShareMenu = false
                                    val name = route?.name?.replace(" ", "_") ?: "route"
                                    fitExportLauncher.launch("Ryde_$name.fit")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Share as image") },
                                onClick = {
                                    showShareMenu = false
                                    val r = route ?: return@DropdownMenuItem
                                    val pts = points
                                    scope.launch {
                                        val intent = withContext(Dispatchers.IO) {
                                            ShareCardRenderer.buildShareIntent(context, r, pts)
                                        }
                                        context.startActivity(
                                            android.content.Intent.createChooser(intent, "Share ride")
                                        )
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Send to Strava") },
                                leadingIcon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) },
                                onClick = {
                                    showShareMenu = false
                                    openStravaAfterExport = true
                                    val name = route?.name?.replace(" ", "_") ?: "route"
                                    fitExportLauncher.launch("Ryde_$name.fit")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Tag ride") },
                                onClick = { showShareMenu = false; showTagDialog = true }
                            )
                        }
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Rounded.DeleteOutline,
                            contentDescription = "Delete ride",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            Box {
                RouteMapView(
                    points = points,
                    activityType = route?.activityType ?: ActivityType.RUNNING,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                )
                SpeedLegend(
                    activityType = route?.activityType ?: ActivityType.RUNNING,
                    isMetric = isMetric,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(10.dp)
                )
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (points.isNotEmpty()) {
                    Text(
                        "Elevation Profile",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    ElevationChart(
                        points = points,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Text(
                        "Speed",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    SpeedChart(
                        points = points,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                route?.let { r ->
                    val durationMs = r.endTime - r.startTime
                    val hours = durationMs / 3_600_000
                    val mins = (durationMs % 3_600_000) / 60_000
                    val secs = (durationMs % 60_000) / 1000

                    val stats = buildList {
                        add("Distance" to UserPrefs.formatDistance(r.distanceKm, isMetric))
                        add("Duration" to if (hours > 0) "%d:%02d:%02d".format(hours, mins, secs)
                                          else "%d:%02d".format(mins, secs))
                        add("Elevation" to "+%.0f m".format(r.elevationGainM))
                        if (r.activityType == ActivityType.CYCLING) {
                            add("Avg Speed" to if (r.avgSpeedKmh > 0)
                                UserPrefs.formatSpeed(r.avgSpeedKmh, isMetric) else "--")
                        } else {
                            val pace = r.avgPace
                            add("Avg Pace" to if (pace > 0 && pace <= 60)
                                UserPrefs.formatPace(pace, isMetric) else "--")
                        }
                        if (topSpeedKmh > 0) {
                            add("Top Speed" to UserPrefs.formatSpeed(topSpeedKmh, isMetric))
                        }
                        if (stoppedTimeSec > 0) {
                            val sh = stoppedTimeSec / 3600
                            val sm = (stoppedTimeSec % 3600) / 60
                            val ss = stoppedTimeSec % 60
                            add("Stopped" to if (sh > 0) "%d:%02d:%02d".format(sh, sm, ss)
                                            else "%d:%02d".format(sm, ss))
                        }
                        add("Calories" to "%.0f kcal".format(r.calories))
                    }

                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        stats.chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (label, value) ->
                                    ElevatedCard(modifier = Modifier.weight(1f)) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Text(
                                                label,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(value, style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }

                    // Lap splits
                    if (lapSplits.isNotEmpty()) {
                        Text(
                            "Lap Splits",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        ElevatedCard(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    Text("LAP", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        modifier = Modifier.weight(0.8f))
                                    Text("TIME", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        modifier = Modifier.weight(1.5f))
                                    Text(
                                        if (r.activityType == ActivityType.CYCLING) "SPEED" else "PACE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                        modifier = Modifier.weight(1.5f)
                                    )
                                }
                                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                                lapSplits.forEach { lap ->
                                    val lapMin = lap.durationMs / 60_000L
                                    val lapSec = (lap.durationMs % 60_000L) / 1_000L
                                    val movStr = if (r.activityType == ActivityType.CYCLING) {
                                        "%.1f km/h".format(lap.avgSpeedKmh)
                                    } else {
                                        val pace = if (lap.avgSpeedKmh > 0) 60.0 / lap.avgSpeedKmh else 0.0
                                        val pm = pace.toInt(); val ps = ((pace - pm) * 60).toInt()
                                        "%d:%02d /km".format(pm, ps)
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${lap.lapNumber}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(0.8f)
                                        )
                                        Text(
                                            "%d:%02d".format(lapMin, lapSec),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1.5f)
                                        )
                                        Text(
                                            movStr,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1.5f)
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                } ?: Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun SpeedLegend(activityType: ActivityType, isMetric: Boolean, modifier: Modifier = Modifier) {
    val (slowMs, fastMs) = when (activityType) {
        ActivityType.RUNNING -> 2f to 4.5f
        ActivityType.CYCLING -> 3f to 9f
        ActivityType.WALKING -> 0.8f to 2f
    }
    val slowLabel = if (isMetric) "${(slowMs * 3.6).toInt()} kmph"
                    else "${(slowMs * 3.6 * 0.621371).toInt()} mph"
    val fastLabel = if (isMetric) "${(fastMs * 3.6).toInt()} kmph"
                    else "${(fastMs * 3.6 * 0.621371).toInt()} mph"
    val gradientColors = listOf(
        Color(0xFF2979FF.toInt()),
        Color(0xFF00E676.toInt()),
        Color(0xFFFF1744.toInt()),
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(gradientColors))
            )
            Spacer(Modifier.height(3.dp))
            Row(
                modifier = Modifier.width(100.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    slowLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    fastLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}
