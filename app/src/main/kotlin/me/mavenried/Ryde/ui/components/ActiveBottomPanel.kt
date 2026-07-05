package me.mavenried.Ryde.ui.components

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.domain.model.StepType
import me.mavenried.Ryde.service.MediaListenerService
import me.mavenried.Ryde.service.TrackingState
import me.mavenried.Ryde.ui.theme.LocalIsMetric
import me.mavenried.Ryde.util.PermissionHelper
import me.mavenried.Ryde.util.UserPrefs

@Composable
fun ActiveBottomPanel(
    state: TrackingState.Active,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }
    var dragAccumPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { 48.dp.toPx() }

    // Stats shared by the collapsed grid and the fullscreen expanded view
    val hours = state.elapsedMs / 3_600_000
    val minutes = (state.elapsedMs % 3_600_000) / 60_000
    val seconds = (state.elapsedMs % 60_000) / 1000
    val timeStr = if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
                  else "%02d:%02d".format(minutes, seconds)
    val isMetric = LocalIsMetric.current
    val movementValue = if (state.activityType == ActivityType.CYCLING)
        UserPrefs.formatSpeed(state.currentSpeed, isMetric)
    else {
        val pace = state.currentPace
        if (pace > 0) UserPrefs.formatPace(pace, isMetric) else "--:--"
    }
    val movementLabel = if (state.activityType == ActivityType.CYCLING) "SPEED" else "PACE"

    Surface(
        modifier = if (isExpanded) modifier.fillMaxSize() else modifier.fillMaxWidth(),
        shape = if (isExpanded) RectangleShape else RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .let { if (isExpanded) it.fillMaxSize() else it }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(isExpanded) {
                        detectVerticalDragGestures(
                            onDragStart = { dragAccumPx = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragAccumPx += dragAmount
                                if (!isExpanded && dragAccumPx < -dragThresholdPx) {
                                    isExpanded = true
                                } else if (isExpanded && dragAccumPx > dragThresholdPx) {
                                    isExpanded = false
                                }
                            }
                        )
                    }
                    .padding(top = 10.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            CircleShape
                        )
                )
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BigStatCell(label = "TIME", value = timeStr, modifier = Modifier.weight(1f))
                        BigStatCell(
                            label = "DIST",
                            value = UserPrefs.formatDistance(state.distanceKm, isMetric),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BigStatCell(label = movementLabel, value = movementValue, modifier = Modifier.weight(1f))
                        BigStatCell(
                            label = "CALS",
                            value = "%.0f kcal".format(state.calories),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ActionButtonsRow(state = state, onPauseResume = onPauseResume, onStop = onStop)
                return@Column
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 14.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatCell(label = "TIME", value = timeStr, modifier = Modifier.weight(1f))
                    StatDivider()
                    StatCell(label = "DIST", value = UserPrefs.formatDistance(state.distanceKm, isMetric), modifier = Modifier.weight(1f))
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(top = 8.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatCell(label = movementLabel, value = movementValue, modifier = Modifier.weight(1f))
                    StatDivider()
                    StatCell(
                        label = "CALS",
                        value = "%.0f kcal".format(state.calories),
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            MusicRow()

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Goal progress bar
            val goalDist = state.goalDistanceKm
            val goalDur = state.goalDurationMs
            if (goalDist != null || goalDur != null) {
                val progress = when {
                    goalDist != null -> (state.distanceKm / goalDist).coerceIn(0.0, 1.0).toFloat()
                    goalDur != null -> (state.elapsedMs.toDouble() / goalDur).coerceIn(0.0, 1.0).toFloat()
                    else -> 0f
                }
                val goalLabel = when {
                    goalDist != null -> "%.1f / %.1f km".format(state.distanceKm, goalDist)
                    goalDur != null -> {
                        val elapsed = state.elapsedMs / 60_000L
                        val total = goalDur / 60_000L
                        "$elapsed / $total min"
                    }
                    else -> ""
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (state.goalReached) "Goal reached!" else "Goal",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.goalReached) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            goalLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (state.goalReached) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // HR chip + interval banner row
            val showHr = state.currentHeartRate != null
            val showInterval = state.intervalStepType != null
            if (showHr || showInterval) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (showHr) {
                        SuggestionChip(
                            onClick = {},
                            label = { Text("${state.currentHeartRate} bpm") },
                            icon = {
                                Icon(
                                    Icons.Rounded.Favorite,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        )
                    }
                    if (showInterval) {
                        val stepType = state.intervalStepType!!
                        val mins = state.intervalStepRemainingSec / 60
                        val secs = state.intervalStepRemainingSec % 60
                        val timeLeft = if (mins > 0) "$mins:%02d".format(secs) else "$secs s"
                        val chipColors = if (stepType == StepType.WORK)
                            SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        else
                            SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        SuggestionChip(
                            onClick = {},
                            label = {
                                Text("${if (stepType == StepType.WORK) "Work" else "Rest"} · $timeLeft")
                            },
                            colors = chipColors
                        )
                    }
                }
            }

            // Auto-pause banner
            if (state.isAutoPaused) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Rounded.PauseCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Auto-paused — move to resume",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            ActionButtonsRow(state = state, onPauseResume = onPauseResume, onStop = onStop)
        }
    }
}

@Composable
private fun ActionButtonsRow(
    state: TrackingState.Active,
    onPauseResume: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onPauseResume,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 14.dp)
        ) {
            Icon(
                if (state.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (state.isPaused) "Resume" else "Pause")
        }
        Button(
            onClick = onStop,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(
                Icons.Rounded.Stop,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("End Ride")
        }
    }
}

@Composable
internal fun MusicRow() {
    val context = LocalContext.current
    val hasPermission = remember { mutableStateOf(PermissionHelper.hasNotificationListenerPermission(context)) }
    val nowPlaying by MediaListenerService.nowPlaying.collectAsState()
    val np = nowPlaying

    LaunchedEffect(Unit) {
        hasPermission.value = PermissionHelper.hasNotificationListenerPermission(context)
    }

    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    var volumeFraction by remember { mutableStateOf<Float?>(null) }
    var volumeResetKey by remember { mutableStateOf(0) }
    LaunchedEffect(volumeResetKey) {
        if (volumeResetKey > 0) {
            delay(1000L)
            volumeFraction = null
        }
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            if (!hasPermission.value) {
                Icon(
                    Icons.Rounded.MusicOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Music access not granted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) {
                    Text("Allow")
                }
                return
            }

            if (np != null) {
                val art = np.albumArt
                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = np.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (np.artist.isNotBlank()) {
                        Text(
                            text = np.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "No music playing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    modifier = Modifier.weight(1f)
                )
            }

            IconButton(onClick = { MediaListenerService.skipToPrevious() }, enabled = np != null) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous")
            }
            FilledTonalIconButton(
                onClick = {
                    if (np?.isPlaying == true) MediaListenerService.pause()
                    else MediaListenerService.play()
                },
                enabled = np != null
            ) {
                Icon(
                    if (np?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (np?.isPlaying == true) "Pause" else "Play"
                )
            }
            IconButton(onClick = { MediaListenerService.skipToNext() }, enabled = np != null) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next")
            }
            IconButton(onClick = {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                volumeFraction = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeResetKey++
            }) {
                Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = "Volume down",
                    modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                volumeFraction = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() /
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeResetKey++
            }) {
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = "Volume up",
                    modifier = Modifier.size(20.dp))
            }
        }

        if (hasPermission.value && np != null && np.durationMs > 0) {
            var displayPosition by remember(np.title, np.durationMs) {
                mutableStateOf(np.positionMs)
            }
            LaunchedEffect(np.title, np.isPlaying, np.positionMs) {
                if (np.isPlaying) {
                    val startWall = System.currentTimeMillis()
                    val startPos = np.positionMs
                    while (true) {
                        delay(500L)
                        displayPosition = (startPos + System.currentTimeMillis() - startWall)
                            .coerceAtMost(np.durationMs)
                    }
                } else {
                    displayPosition = np.positionMs
                }
            }
            val vf = volumeFraction
            val fraction = (vf ?: (displayPosition.toFloat() / np.durationMs)).coerceIn(0f, 1f)
            val barColor = if (vf != null) MaterialTheme.colorScheme.tertiary
                           else MaterialTheme.colorScheme.primary
            Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(barColor)
                )
            }
        }
    }
}


@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontWeight = FontWeight.Medium,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun BigStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontWeight = FontWeight.Medium,
            letterSpacing = androidx.compose.ui.unit.TextUnit(1.5f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .height(36.dp)
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

