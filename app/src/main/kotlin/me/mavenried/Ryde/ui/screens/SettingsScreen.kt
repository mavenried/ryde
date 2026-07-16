package me.mavenried.Ryde.ui.screens

import android.annotation.SuppressLint
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.mavenried.Ryde.data.db.AppDatabase
import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.service.ActivityRecognitionReceiver
import me.mavenried.Ryde.service.HeartRateManager
import me.mavenried.Ryde.service.WeeklySummaryWorker
import me.mavenried.Ryde.util.FileLogger
import me.mavenried.Ryde.util.PermissionHelper
import me.mavenried.Ryde.util.TtsEngineFactory
import me.mavenried.Ryde.util.TtsVoices
import me.mavenried.Ryde.util.UpdateManager
import me.mavenried.Ryde.util.UserPrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val useLbs = remember { mutableStateOf(UserPrefs.useLbs(context)) }
    val weightKg = remember { mutableStateOf(UserPrefs.getWeightKg(context)) }
    val bikeWeightKg = remember { mutableStateOf(UserPrefs.getBikeWeightKg(context)) }
    var theme by remember { mutableStateOf(UserPrefs.getTheme(context)) }
    var isMetric by remember { mutableStateOf(UserPrefs.isMetric(context)) }
    var keepScreenOnCycling by remember {
        mutableStateOf(UserPrefs.isKeepScreenOn(context, ActivityType.CYCLING))
    }
    var keepScreenOnRunning by remember {
        mutableStateOf(UserPrefs.isKeepScreenOn(context, ActivityType.RUNNING))
    }
    var keepScreenOnWalking by remember {
        mutableStateOf(UserPrefs.isKeepScreenOn(context, ActivityType.WALKING))
    }
    var lightModeRiding by remember { mutableStateOf(UserPrefs.isLightModeRiding(context)) }
    var weeklySummaryEnabled by remember { mutableStateOf(UserPrefs.isWeeklyNotificationEnabled(context)) }
    var autoStartEnabled by remember { mutableStateOf(UserPrefs.isAutoStartEnabled(context)) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showRestoreConfirm by remember { mutableStateOf(false) }
    val updateCheckState by UpdateManager.checkState.collectAsState()
    val updateInfo by UpdateManager.updateInfo.collectAsState()
    val scope = rememberCoroutineScope()
    var hrDeviceName by remember { mutableStateOf(UserPrefs.getHrDeviceName(context)) }
    var batteryOptExempt by remember { mutableStateOf(PermissionHelper.isIgnoringBatteryOptimizations(context)) }
    val batteryOptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { batteryOptExempt = PermissionHelper.isIgnoringBatteryOptimizations(context) }
    var wakeLockCycling by remember { mutableStateOf(UserPrefs.isWakeLockEnabled(context, ActivityType.CYCLING)) }
    var wakeLockRunning by remember { mutableStateOf(UserPrefs.isWakeLockEnabled(context, ActivityType.RUNNING)) }
    var wakeLockWalking by remember { mutableStateOf(UserPrefs.isWakeLockEnabled(context, ActivityType.WALKING)) }

    var ttsEngine by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsVoices by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var selectedVoiceName by remember { mutableStateOf(UserPrefs.getTtsVoiceName(context)) }
    var debugSpeechText by remember { mutableStateOf("This is a test of the Ryde voice announcements.") }

    DisposableEffect(Unit) {
        TtsEngineFactory.create(context) { engine ->
            engine.language = Locale.US
            engine.setSpeechRate(0.95f)
            ttsVoices = TtsVoices.available(engine)
            val voice = TtsVoices.preferred(engine, selectedVoiceName)
            if (voice != null) {
                engine.voice = voice
                selectedVoiceName = voice.name
            }
            ttsEngine = engine
        }
        onDispose {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        }
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val dbFile = context.getDatabasePath("ryde.db")
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        dbFile.inputStream().use { it.copyTo(out) }
                    }
                }.isSuccess
            }
            Toast.makeText(context,
                if (ok) "Backup saved" else "Backup failed",
                Toast.LENGTH_SHORT).show()
        }
    }

    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestoreConfirm = true
        }
    }
    var showHrScanDialog by remember { mutableStateOf(false) }
    val hrScanResults by HeartRateManager.scanResults.collectAsState()
    val hrConnected by HeartRateManager.connected.collectAsState()
    val bleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            HeartRateManager.startScan(context)
            showHrScanDialog = true
        }
    }

    val displayValue = remember(useLbs.value, weightKg.value) {
        if (useLbs.value) "%.1f".format(UserPrefs.kgToLbs(weightKg.value))
        else "%.1f".format(weightKg.value)
    }
    var inputText by remember(displayValue) { mutableStateOf(displayValue) }
    var isError by remember { mutableStateOf(false) }

    val bikeDisplayValue = remember(useLbs.value, bikeWeightKg.value) {
        if (useLbs.value) "%.1f".format(UserPrefs.kgToLbs(bikeWeightKg.value))
        else "%.1f".format(bikeWeightKg.value)
    }
    var bikeInputText by remember(bikeDisplayValue) { mutableStateOf(bikeDisplayValue) }
    var isBikeError by remember { mutableStateOf(false) }

    if (showLogsDialog) {
        LogsDialog(onDismiss = { showLogsDialog = false })
    }

    if (showRestoreConfirm) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirm = false },
            title = { Text("Restore backup?") },
            text = { Text("This will replace all current rides and data with the backup. The app will restart.") },
            confirmButton = {
                TextButton(onClick = {
                    showRestoreConfirm = false
                    val uri = pendingRestoreUri ?: return@TextButton
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) {
                            runCatching {
                                val dbFile = context.getDatabasePath("ryde.db")
                                AppDatabase.closeInstance()
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    dbFile.outputStream().use { input.copyTo(it) }
                                }
                                // Delete WAL/SHM so Room opens cleanly
                                context.getDatabasePath("ryde.db-wal").delete()
                                context.getDatabasePath("ryde.db-shm").delete()
                            }.isSuccess
                        }
                        if (ok) {
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } else {
                            Toast.makeText(context, "Restore failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirm = false }) { Text("Cancel") }
            }
        )
    }
    if (showHrScanDialog) {
        HrScanDialog(
            devices = hrScanResults,
            onDeviceSelected = { device ->
                @Suppress("MissingPermission")
                val name = device.name ?: device.address
                UserPrefs.setHrDevice(context, device.address, name)
                hrDeviceName = name
                HeartRateManager.stopScan()
                showHrScanDialog = false
            },
            onDismiss = {
                HeartRateManager.stopScan()
                showHrScanDialog = false
            }
        )
    }

    fun save() {
        val parsed = inputText.toDoubleOrNull()
        if (parsed == null || parsed <= 0) { isError = true; return }
        isError = false
        val kg = if (useLbs.value) UserPrefs.lbsToKg(parsed) else parsed
        weightKg.value = kg
        UserPrefs.setWeightKg(context, kg)

        val bikeParsed = bikeInputText.toDoubleOrNull()
        if (bikeParsed == null || bikeParsed <= 0) { isBikeError = true; return }
        isBikeError = false
        val bikeKg = if (useLbs.value) UserPrefs.lbsToKg(bikeParsed) else bikeParsed
        bikeWeightKg.value = bikeKg
        UserPrefs.setBikeWeightKg(context, bikeKg)

        UserPrefs.setUseLbs(context, useLbs.value)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { save(); onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text("Profile", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Body weight", style = MaterialTheme.typography.bodyMedium)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it; isError = false },
                        modifier = Modifier.weight(1f),
                        label = { Text(if (useLbs.value) "lbs" else "kg") },
                        isError = isError,
                        supportingText = if (isError) {{ Text("Enter a valid weight") }} else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    UnitToggle(
                        useLbs = useLbs.value,
                        onToggle = { newUseLbs ->
                            save()
                            useLbs.value = newUseLbs
                            UserPrefs.setUseLbs(context, newUseLbs)
                            inputText = if (newUseLbs)
                                "%.1f".format(UserPrefs.kgToLbs(weightKg.value))
                            else
                                "%.1f".format(weightKg.value)
                            bikeInputText = if (newUseLbs)
                                "%.1f".format(UserPrefs.kgToLbs(bikeWeightKg.value))
                            else
                                "%.1f".format(bikeWeightKg.value)
                        }
                    )
                }
                Text(
                    "Used to estimate calorie burn during activities.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bike weight", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = bikeInputText,
                    onValueChange = { bikeInputText = it; isBikeError = false },
                    modifier = Modifier.fillMaxWidth(0.5f),
                    label = { Text(if (useLbs.value) "lbs" else "kg") },
                    isError = isBikeError,
                    supportingText = if (isBikeError) {{ Text("Enter a valid weight") }} else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true
                )
                Text(
                    "Combined with body weight for more accurate cycling calorie estimates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Units", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Distance & speed", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf(true to "Metric", false to "Imperial")
                        .forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = isMetric == value,
                                onClick = {
                                    isMetric = value
                                    UserPrefs.setMetric(context, value)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                                label = { Text(label) }
                            )
                        }
                }
                Text(
                    if (isMetric) "Showing km and kmph." else "Showing miles and mph.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Appearance", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark")
                        .forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = theme == value,
                                onClick = {
                                    theme = value
                                    UserPrefs.setTheme(context, value)
                                },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                                label = { Text(label) }
                            )
                        }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Keep screen on while riding", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Prevents the screen from sleeping during an active session, per activity type.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                NotificationToggleRow(
                    title = "Cycling",
                    subtitle = "",
                    checked = keepScreenOnCycling,
                    onCheckedChange = {
                        keepScreenOnCycling = it
                        UserPrefs.setKeepScreenOn(context, ActivityType.CYCLING, it)
                    }
                )
                NotificationToggleRow(
                    title = "Running",
                    subtitle = "",
                    checked = keepScreenOnRunning,
                    onCheckedChange = {
                        keepScreenOnRunning = it
                        UserPrefs.setKeepScreenOn(context, ActivityType.RUNNING, it)
                    }
                )
                NotificationToggleRow(
                    title = "Walking",
                    subtitle = "",
                    checked = keepScreenOnWalking,
                    onCheckedChange = {
                        keepScreenOnWalking = it
                        UserPrefs.setKeepScreenOn(context, ActivityType.WALKING, it)
                    }
                )
            }

            NotificationToggleRow(
                title = "Light mode while riding",
                subtitle = "Switches to light theme during an active session for outdoor visibility",
                checked = lightModeRiding,
                onCheckedChange = { lightModeRiding = it; UserPrefs.setLightModeRiding(context, it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Notifications", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            NotificationToggleRow(
                title = "Weekly summary",
                subtitle = "Monday recap of last week's distance and activity count",
                checked = weeklySummaryEnabled,
                onCheckedChange = { enabled ->
                    weeklySummaryEnabled = enabled
                    UserPrefs.setWeeklyNotificationEnabled(context, enabled)
                    if (enabled) WeeklySummaryWorker.schedule(context)
                    else WeeklySummaryWorker.cancel(context)
                }
            )

            NotificationToggleRow(
                title = "Auto-start detection",
                subtitle = "Notifies you when the phone detects walking, running, or cycling so you can tap to start a session",
                checked = autoStartEnabled,
                onCheckedChange = { enabled ->
                    autoStartEnabled = enabled
                    UserPrefs.setAutoStartEnabled(context, enabled)
                    if (enabled) ActivityRecognitionReceiver.enable(context)
                    else ActivityRecognitionReceiver.disable(context)
                }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Voice Announcements", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice", style = MaterialTheme.typography.bodyMedium)
                if (ttsVoices.isEmpty()) {
                    Text(
                        "Loading available voices…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        ttsVoices.forEach { voice ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        selectedVoiceName = voice.name
                                        UserPrefs.setTtsVoiceName(context, voice.name)
                                        ttsEngine?.voice = voice
                                        ttsEngine?.speak(
                                            "This is how announcements will sound.",
                                            TextToSpeech.QUEUE_FLUSH, null, "voice_preview"
                                        )
                                    }
                                    .padding(horizontal = 4.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = voice.name == selectedVoiceName,
                                    onClick = null
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(TtsVoices.label(voice), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Text(
                    "Used for lap splits, goal, and interval workout announcements during a ride.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Heart Rate Monitor", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hrDeviceName != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (hrConnected) Icons.Rounded.Bluetooth else Icons.AutoMirrored.Rounded.BluetoothSearching,
                            contentDescription = null,
                            tint = if (hrConnected) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(hrDeviceName!!, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (hrConnected) "Connected" else "Not connected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        TextButton(onClick = {
                            UserPrefs.setHrDevice(context, null, null)
                            hrDeviceName = null
                            HeartRateManager.disconnect()
                        }) { Text("Forget") }
                    }
                } else {
                    Text(
                        "No device paired. Tap Scan to find nearby BLE heart rate monitors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                }
                OutlinedButton(
                    onClick = {
                        if (PermissionHelper.hasBlePermissions(context)) {
                            HeartRateManager.startScan(context)
                            showHrScanDialog = true
                        } else {
                            bleLauncher.launch(PermissionHelper.blePermissions())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.BluetoothSearching, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Scan for devices")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Data", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                        backupLauncher.launch("Ryde_backup_$stamp.db")
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Rounded.Backup, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Backup")
                }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/octet-stream", "*/*")) },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Rounded.Restore, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore")
                }
            }

            Text(
                "Backup saves all rides to a file. Restore replaces all data and restarts the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Diagnostics", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        runCatching {
                            batteryOptLauncher.launch(PermissionHelper.batteryOptimizationIntent(context))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    enabled = !batteryOptExempt
                ) {
                    Icon(Icons.Rounded.BatteryAlert, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        if (batteryOptExempt) "Battery optimization disabled for Ryde"
                        else "Disable battery optimization"
                    )
                }
                Text(
                    "Some phones aggressively kill background apps to save power, which can stop " +
                        "GPS tracking mid-ride. Disabling this for Ryde helps prevent that.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Keep device awake while recording", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Holds a wake lock during the activity so GPS keeps sampling with the screen " +
                        "off. Uses more battery — turn off per activity if you don't need it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
                NotificationToggleRow(
                    title = "Cycling",
                    subtitle = "",
                    checked = wakeLockCycling,
                    onCheckedChange = {
                        wakeLockCycling = it
                        UserPrefs.setWakeLockEnabled(context, ActivityType.CYCLING, it)
                    }
                )
                NotificationToggleRow(
                    title = "Running",
                    subtitle = "",
                    checked = wakeLockRunning,
                    onCheckedChange = {
                        wakeLockRunning = it
                        UserPrefs.setWakeLockEnabled(context, ActivityType.RUNNING, it)
                    }
                )
                NotificationToggleRow(
                    title = "Walking",
                    subtitle = "",
                    checked = wakeLockWalking,
                    onCheckedChange = {
                        wakeLockWalking = it
                        UserPrefs.setWakeLockEnabled(context, ActivityType.WALKING, it)
                    }
                )
            }

            OutlinedButton(
                onClick = { UpdateManager.checkAsync() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                enabled = updateCheckState != UpdateManager.CheckState.CHECKING,
            ) {
                if (updateCheckState == UpdateManager.CheckState.CHECKING) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.SystemUpdate, contentDescription = null)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(when {
                    updateCheckState == UpdateManager.CheckState.CHECKING -> "Checking…"
                    updateInfo != null -> "Update available: ${updateInfo!!.version}"
                    updateCheckState == UpdateManager.CheckState.UP_TO_DATE -> "Up to date"
                    updateCheckState == UpdateManager.CheckState.ERROR -> "Check failed — tap to retry"
                    else -> "Check for updates"
                })
            }

            OutlinedButton(
                onClick = { showLogsDialog = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(16.dp)
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null)
                Spacer(modifier = Modifier.width(12.dp))
                Text("View Application Logs")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Debug", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Speak text", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = debugSpeechText,
                    onValueChange = { debugSpeechText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Text") },
                    minLines = 2
                )
                OutlinedButton(
                    onClick = {
                        ttsEngine?.speak(debugSpeechText, TextToSpeech.QUEUE_FLUSH, null, "debug_speak")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    enabled = ttsEngine != null && debugSpeechText.isNotBlank()
                ) {
                    Icon(Icons.Rounded.RecordVoiceOver, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Speak")
                }
                Text(
                    "Uses the selected voice above to test what an announcement would sound like.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun HrScanDialog(
    devices: List<android.bluetooth.BluetoothDevice>,
    onDeviceSelected: (android.bluetooth.BluetoothDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select heart rate monitor") },
        text = {
            if (devices.isEmpty()) {
                Text("Scanning for nearby devices…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    devices.forEach { device ->
                        TextButton(
                            onClick = { onDeviceSelected(device) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(device.name ?: "Unknown device",
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(device.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun LogsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf("Loading...") }

    LaunchedEffect(Unit) {
        logText = FileLogger.getLogs(context)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Scaffold(
                topBar = {
                    @OptIn(ExperimentalMaterial3Api::class)
                    TopAppBar(
                        title = { Text("Application Logs") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                FileLogger.clearLogs(context)
                                logText = "Logs cleared."
                            }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "Clear logs")
                            }
                        }
                    )
                }
            ) { padding ->
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = logText,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun NotificationToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotEmpty()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitToggle(useLbs: Boolean, onToggle: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = !useLbs,
            onClick = { if (useLbs) onToggle(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text("kg") }
        )
        SegmentedButton(
            selected = useLbs,
            onClick = { if (!useLbs) onToggle(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text("lbs") }
        )
    }
}
