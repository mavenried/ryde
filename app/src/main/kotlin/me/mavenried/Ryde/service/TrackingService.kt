package me.mavenried.Ryde.service

import android.app.NotificationManager
import android.content.Intent
import android.location.Location
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.*
import me.mavenried.Ryde.data.db.AppDatabase
import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.domain.model.IntervalWorkout
import me.mavenried.Ryde.domain.model.LocationPoint
import me.mavenried.Ryde.domain.model.Route
import me.mavenried.Ryde.domain.model.StepType
import me.mavenried.Ryde.domain.repository.RouteRepository
import me.mavenried.Ryde.domain.util.TrackStats
import me.mavenried.Ryde.util.FileLogger
import me.mavenried.Ryde.util.ReverseGeocoder
import me.mavenried.Ryde.util.UserPrefs
import me.mavenried.Ryde.service.HeartRateManager
import me.mavenried.Ryde.widget.RydeWidget
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.UUID

sealed class TrackingState {
    data object Idle : TrackingState()
    data class Active(
        val activityType: ActivityType,
        val elapsedMs: Long,
        val distanceKm: Double,
        val currentPace: Double,
        val currentSpeed: Double,
        val calories: Double,
        val points: List<LocationPoint>,
        val isPaused: Boolean = false,
        val isAutoPaused: Boolean = false,
        val lapCount: Int = 0,
        val goalDistanceKm: Double? = null,
        val goalDurationMs: Long? = null,
        val goalReached: Boolean = false,
        val currentHeartRate: Int? = null,
        val intervalStepType: StepType? = null,
        val intervalStepRemainingSec: Int = 0,
        val intervalStepNumber: Int = 0,
        val intervalTotalSteps: Int = 0,
    ) : TrackingState()
}

class TrackingService : LifecycleService() {

    inner class TrackingBinder : Binder() {
        fun getService(): TrackingService = this@TrackingService
    }

    private val binder = TrackingBinder()
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var repository: RouteRepository

    private val _state = MutableStateFlow<TrackingState>(TrackingState.Idle)
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    companion object {
        private val _globalState = MutableStateFlow<TrackingState>(TrackingState.Idle)
        val globalState: StateFlow<TrackingState> = _globalState.asStateFlow()
    }

    private var startTimeMs = 0L
    private var totalPausedMs = 0L
    private var pausedAtMs = 0L
    private var isPaused = false
    private var isAutoPaused = false
    private var currentActivityType = ActivityType.RUNNING
    private var lastSpeedMs = 0f
    private val pointBuffer = mutableListOf<LocationPoint>()
    private val pendingDbPoints = mutableListOf<LocationPoint>()
    private var runningCalories = 0.0
    private var currentRouteId = ""
    private var timerJob: Job? = null

    // Goal tracking
    private var goalDistanceKm: Double? = null
    private var goalDurationMs: Long? = null
    private var goalReached = false

    // Lap / audio cue tracking
    private var lastAnnouncedKm = 0
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Interval workout
    private var intervalWorkout: IntervalWorkout? = null
    private var intervalStepIndex = 0
    private var intervalStepRemainingMs = 0L

    override fun onCreate() {
        super.onCreate()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        repository = RouteRepository(AppDatabase.getInstance(this))
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { onNewLocation(it) }
            }
        }
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(0.95f)
                ttsReady = true
            }
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationHelper.buildTrackingNotification(this)
        startForeground(NotificationHelper.TRACKING_NOTIFICATION_ID, notification)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    fun startTracking(
        activityType: ActivityType,
        goalDistanceKm: Double? = null,
        goalDurationMs: Long? = null,
        intervalWorkout: IntervalWorkout? = null,
    ) {
        FileLogger.log(this, "Starting tracking for $activityType")
        currentActivityType = activityType
        currentRouteId = UUID.randomUUID().toString()
        startTimeMs = System.currentTimeMillis()
        totalPausedMs = 0L
        pausedAtMs = 0L
        isPaused = false
        lastSpeedMs = 0f
        pointBuffer.clear()
        pendingDbPoints.clear()
        runningCalories = 0.0
        this.goalDistanceKm = goalDistanceKm
        this.goalDurationMs = goalDurationMs
        goalReached = false
        lastAnnouncedKm = 0
        this.intervalWorkout = intervalWorkout
        intervalStepIndex = 0
        intervalStepRemainingMs = intervalWorkout?.workMs ?: 0L
        val hrMac = UserPrefs.getHrDeviceMac(this)
        if (hrMac != null) HeartRateManager.connect(this, hrMac)

        val initialRoute = Route(
            id = currentRouteId,
            activityType = currentActivityType,
            name = autoName(currentActivityType, startTimeMs),
            startTime = startTimeMs,
            endTime = startTimeMs,
            distanceKm = 0.0,
            avgPace = 0.0,
            avgSpeedKmh = 0.0,
            elevationGainM = 0.0,
            calories = 0.0,
            category = "Other",
            completed = false
        )
        lifecycleScope.launch {
            try {
                repository.saveRoute(initialRoute)
                FileLogger.log(this@TrackingService, "Initial route saved: $currentRouteId")
                RydeWidget().updateAll(this@TrackingService)
            } catch (e: Exception) {
                FileLogger.logError(this@TrackingService, "Failed to save initial route", e)
            }
        }

        requestLocationUpdates()
        startTimer()
        pushState()
    }

    fun resumeFromCrash(routeId: String, activityType: ActivityType, savedStartTime: Long) {
        FileLogger.log(this, "Resuming crashed route: $routeId")
        currentActivityType = activityType
        currentRouteId = routeId
        startTimeMs = savedStartTime
        totalPausedMs = 0L
        pausedAtMs = 0L
        isPaused = false
        isAutoPaused = false
        lastSpeedMs = 0f
        goalDistanceKm = null
        goalDurationMs = null
        goalReached = false
        lastAnnouncedKm = 0
        pointBuffer.clear()
        pendingDbPoints.clear()
        runningCalories = 0.0
        lifecycleScope.launch {
            try {
                val existing = repository.getPointsForRoute(routeId)
                pointBuffer.addAll(existing)
                lastAnnouncedKm = TrackStats.totalDistanceKm(existing).toInt()
                runningCalories = TrackStats.estimatedCaloriesKcal(
                    existing, activityType, UserPrefs.getWeightKg(this@TrackingService), UserPrefs.getBikeWeightKg(this@TrackingService)
                )
                FileLogger.log(this@TrackingService, "Loaded ${existing.size} existing points for crash resume")
            } catch (e: Exception) {
                FileLogger.logError(this@TrackingService, "Failed to load existing points on resume", e)
            }
        }
        requestLocationUpdates()
        startTimer()
        pushState()
    }

    fun pauseTracking() {
        if (isPaused) return
        if (isAutoPaused) {
            totalPausedMs += System.currentTimeMillis() - pausedAtMs
            isAutoPaused = false
        }
        isPaused = true
        pausedAtMs = System.currentTimeMillis()
        fusedClient.removeLocationUpdates(locationCallback)
        stopTimer()
        pushState()
    }

    fun resumeTracking() {
        if (!isPaused && !isAutoPaused) return
        totalPausedMs += System.currentTimeMillis() - pausedAtMs
        isPaused = false
        isAutoPaused = false
        requestLocationUpdates()
        startTimer()
        pushState()
    }

    fun discardTracking() {
        HeartRateManager.disconnect()
        fusedClient.removeLocationUpdates(locationCallback)
        stopTimer()
        val routeId = currentRouteId
        lifecycleScope.launch { repository.deleteRoute(routeId) }
        setState(TrackingState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun stopTracking(tag: String = "Other") {
        FileLogger.log(this, "Stopping tracking for $currentRouteId")
        HeartRateManager.disconnect()
        fusedClient.removeLocationUpdates(locationCallback)
        stopTimer()
        val endTime = System.currentTimeMillis()
        val durationMs = (endTime - startTimeMs - totalPausedMs).coerceAtLeast(0L)
        val distanceKm = TrackStats.totalDistanceKm(pointBuffer)
        val elevGain = TrackStats.elevationGainM(pointBuffer)
        val avgPace = if (currentActivityType != ActivityType.CYCLING)
            TrackStats.avgPaceMinPerKm(distanceKm, durationMs) else 0.0
        val avgSpeed = if (currentActivityType == ActivityType.CYCLING)
            TrackStats.avgSpeedKmh(distanceKm, durationMs) else 0.0
        val calories = runningCalories

        lifecycleScope.launch {
            val locationName = if (pointBuffer.isNotEmpty()) {
                val last = pointBuffer.last()
                ReverseGeocoder.getPlaceName(this@TrackingService, last.lat, last.lng)
            } else null

            val routeName = if (locationName != null) {
                "${autoName(currentActivityType, startTimeMs)} in $locationName"
            } else {
                autoName(currentActivityType, startTimeMs)
            }

            val route = Route(
                id = currentRouteId,
                activityType = currentActivityType,
                name = routeName,
                startTime = startTimeMs,
                endTime = endTime,
                distanceKm = distanceKm,
                avgPace = avgPace,
                avgSpeedKmh = avgSpeed,
                elevationGainM = elevGain,
                calories = calories,
                category = tag,
                completed = true
            )
            val allPoints = pointBuffer.toList()
            val routeId = currentRouteId

            repository.saveRoute(route)
            repository.savePoints(routeId, allPoints)
            RydeWidget().updateAll(this@TrackingService)
            DemCorrectionWorker.enqueue(this@TrackingService, routeId)
        }

        setState(TrackingState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = lifecycleScope.launch {
            while (true) {
                delay(1_000L)
                tickInterval()
                pushState()
                checkGoalByDuration()
            }
        }
    }

    private fun tickInterval() {
        val workout = intervalWorkout ?: return
        if (isPaused || isAutoPaused) return
        intervalStepRemainingMs -= 1_000L
        if (intervalStepRemainingMs > 0) return
        intervalStepIndex++
        if (intervalStepIndex >= workout.totalSteps) {
            tts?.speak("Workout complete", TextToSpeech.QUEUE_FLUSH, null, "interval_done")
            intervalWorkout = null
            return
        }
        intervalStepRemainingMs = workout.stepDurationMs(intervalStepIndex)
        val msg = if (workout.stepType(intervalStepIndex) == StepType.WORK) "Work" else "Rest"
        tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "interval_$intervalStepIndex")
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateDistanceMeters(5f)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun requestLocationUpdatesStationary() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2_000L)
            .setMinUpdateDistanceMeters(0f)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, locationCallback, mainLooper)
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun onNewLocation(location: Location) {
        val point = LocationPoint(
            lat = location.latitude,
            lng = location.longitude,
            altitude = location.altitude,
            timestamp = location.time,
            speed = location.speed,
            accuracy = location.accuracy,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            heartRate = HeartRateManager.heartRate.value,
        )

        if (point.accuracy <= 25f) {
            lastSpeedMs = point.speed
            if (point.speed < 0.5f && !isAutoPaused && !isPaused) {
                isAutoPaused = true
                pausedAtMs = System.currentTimeMillis()
                requestLocationUpdatesStationary()
                FileLogger.log(this, "Auto-paused (speed ${point.speed})")
            } else if (point.speed > 1.0f && isAutoPaused) {
                totalPausedMs += System.currentTimeMillis() - pausedAtMs
                isAutoPaused = false
                requestLocationUpdates()
                FileLogger.log(this, "Auto-resumed (speed ${point.speed})")
            }

            if (!isAutoPaused) {
                val prevPoint = pointBuffer.lastOrNull()
                pointBuffer.add(point)
                pendingDbPoints.add(point)
                if (prevPoint != null) {
                    runningCalories += TrackStats.segmentCaloriesKcal(
                        prevPoint, point, currentActivityType,
                        UserPrefs.getWeightKg(this), UserPrefs.getBikeWeightKg(this)
                    )
                }
                if (pendingDbPoints.size >= 10) flushPendingPoints()

                val totalKm = TrackStats.totalDistanceKm(pointBuffer)
                val isMetric = UserPrefs.isMetric(this)
                val completedUnits = if (isMetric) totalKm.toInt() else UserPrefs.kmToMi(totalKm).toInt()
                if (completedUnits > lastAnnouncedKm) {
                    lastAnnouncedKm = completedUnits
                    announceSplit(completedUnits, totalKm, isMetric)
                }
                checkGoalByDistance(totalKm)
            }
        } else {
            FileLogger.log(this, "Location rejected: accuracy ${point.accuracy}m")
        }
        pushState()
        updateNotification()
    }

    private fun announceSplit(lapNumber: Int, totalKm: Double, isMetric: Boolean) {
        if (!ttsReady) return
        val recentPoints = pointBuffer.takeLast(20)
        val recentDurationMs = if (recentPoints.size >= 2)
            recentPoints.last().timestamp - recentPoints.first().timestamp else 0L
        val recentDistKm = TrackStats.totalDistanceKm(recentPoints)

        val lapStr = if (currentActivityType == ActivityType.CYCLING) {
            val speedKmh = if (recentDurationMs > 0) recentDistKm / (recentDurationMs / 3_600_000.0) else 0.0
            if (isMetric) {
                "$lapNumber kilometer, averaging %.0f kilometers per hour".format(speedKmh)
            } else {
                "$lapNumber mile, averaging %.0f miles per hour".format(UserPrefs.kmhToMph(speedKmh))
            }
        } else {
            val pace = if (recentDistKm > 0 && recentDurationMs > 0)
                (recentDurationMs / 60_000.0) / recentDistKm else 0.0
            val displayPace = if (isMetric) pace else UserPrefs.minPerKmToMinPerMi(pace)
            val paceMin = displayPace.toInt()
            val paceSec = ((displayPace - paceMin) * 60).toInt().coerceIn(0, 59)
            val unit = if (isMetric) "kilometer" else "mile"
            "$lapNumber $unit at a pace of $paceMin ${if (paceSec == 0) "minutes" else "minutes and $paceSec seconds"} per $unit"
        }
        tts?.speak(lapStr, TextToSpeech.QUEUE_FLUSH, null, "lap_$lapNumber")
    }

    private fun checkGoalByDistance(totalKm: Double) {
        val goal = goalDistanceKm ?: return
        if (!goalReached && totalKm >= goal) {
            goalReached = true
            val msg = if (UserPrefs.isMetric(this)) {
                "Goal reached! You've completed %.1f kilometers".format(goal)
            } else {
                "Goal reached! You've completed %.1f miles".format(UserPrefs.kmToMi(goal))
            }
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "goal_distance")
        }
    }

    private fun checkGoalByDuration() {
        val goal = goalDurationMs ?: return
        if (goalReached || isPaused || isAutoPaused) return
        val now = System.currentTimeMillis()
        val elapsed = (now - startTimeMs - totalPausedMs).coerceAtLeast(0L)
        if (elapsed >= goal) {
            goalReached = true
            val totalMin = (goal / 60_000L).toInt()
            val msg = "Goal reached! You've been riding for $totalMin minute${if (totalMin == 1) "" else "s"}"
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "goal_duration")
        }
    }

    private fun flushPendingPoints() {
        if (pendingDbPoints.isEmpty()) return
        val toSave = pendingDbPoints.toList()
        val routeId = currentRouteId
        pendingDbPoints.clear()
        lifecycleScope.launch {
            try {
                repository.savePoints(routeId, toSave)
                RydeWidget().updateAll(this@TrackingService)
            } catch (e: Exception) {
                FileLogger.logError(this@TrackingService, "Failed to flush points for $routeId", e)
            }
        }
    }

    private fun setState(s: TrackingState) {
        _state.value = s
        _globalState.value = s
    }

    private fun pushState() {
        val now = System.currentTimeMillis()
        val elapsed = (if (isPaused || isAutoPaused) {
            pausedAtMs - startTimeMs - totalPausedMs
        } else {
            now - startTimeMs - totalPausedMs
        }).coerceAtLeast(0L)
        val dist = TrackStats.totalDistanceKm(pointBuffer)
        val liveSpeedKmh = lastSpeedMs * 3.6
        val workout = intervalWorkout
        setState(TrackingState.Active(
            activityType = currentActivityType,
            elapsedMs = elapsed,
            distanceKm = dist,
            currentPace = if (currentActivityType != ActivityType.CYCLING && lastSpeedMs > 0.1f)
                1000.0 / (lastSpeedMs * 60.0) else 0.0,
            currentSpeed = if (currentActivityType == ActivityType.CYCLING) liveSpeedKmh else 0.0,
            calories = runningCalories,
            points = pointBuffer.toList(),
            isPaused = isPaused || isAutoPaused,
            isAutoPaused = isAutoPaused,
            lapCount = lastAnnouncedKm,
            goalDistanceKm = goalDistanceKm,
            goalDurationMs = goalDurationMs,
            goalReached = goalReached,
            currentHeartRate = HeartRateManager.heartRate.value,
            intervalStepType = workout?.stepType(intervalStepIndex),
            intervalStepRemainingSec = (intervalStepRemainingMs / 1_000L).toInt().coerceAtLeast(0),
            intervalStepNumber = if (workout != null) intervalStepIndex + 1 else 0,
            intervalTotalSteps = workout?.totalSteps ?: 0,
        ))
    }

    private fun updateNotification() {
        val s = _state.value as? TrackingState.Active ?: return
        val notification = NotificationHelper.buildTrackingNotification(
            this, s.distanceKm, s.elapsedMs
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NotificationHelper.TRACKING_NOTIFICATION_ID, notification)
    }

    private fun autoName(type: ActivityType, startMs: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = startMs }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)

        val dayName = when (dayOfWeek) {
            Calendar.SATURDAY, Calendar.SUNDAY -> "Weekend"
            else -> "Weekday"
        }

        val timeOfDay = when {
            hour < 6 -> "Night"
            hour < 12 -> "Morning"
            hour < 17 -> "Afternoon"
            hour < 21 -> "Evening"
            else -> "Night"
        }
        val activity = when (type) {
            ActivityType.RUNNING -> "Run"
            ActivityType.CYCLING -> "Ride"
            ActivityType.WALKING -> "Walk"
        }
        return "$dayName $timeOfDay $activity"
    }
}
