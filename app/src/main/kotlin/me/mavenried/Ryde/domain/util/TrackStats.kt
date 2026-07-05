package me.mavenried.Ryde.domain.util

import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.domain.model.LocationPoint
import kotlin.math.*

data class LapSplit(
    val lapNumber: Int,
    val durationMs: Long,
    val avgSpeedKmh: Double
)

object TrackStats {

    private const val EARTH_RADIUS_KM = 6371.0
    private const val ACCURACY_THRESHOLD_M = 25f
    // GPS altitude jitter on consumer devices is typically ±5–15 m.
    // A gate below ~8 m accumulates significant false gain on flat ground.
    private const val ELEVATION_NOISE_GATE_M = 8.0
    // DEM-corrected altitude is far less noisy than raw GPS, so a much smaller gate suffices.
    private const val ELEVATION_NOISE_GATE_DEM_M = 1.5

    private const val DEFAULT_WEIGHT_KG = 70.0

    // Physics-based cycling power model (rolling resistance + gravity + aerodynamic drag),
    // converted to metabolic cost via gross efficiency. See estimatedCaloriesKcal.
    private const val GRAVITY_MS2 = 9.80665
    private const val ROLLING_RESISTANCE_COEFF = 0.005 // road tires on pavement
    private const val AIR_DENSITY_KG_M3 = 1.225 // sea level
    private const val CYCLING_DRAG_AREA_M2 = 0.4 // CdA, upright road cycling posture
    private const val CYCLING_GROSS_EFFICIENCY = 0.24 // fraction of metabolic energy -> mechanical work
    private const val JOULES_PER_KCAL = 4184.0

    private const val MAX_PLAUSIBLE_SEGMENT_SPEED_MS = 30.0 // ~108 km/h; beyond this, treat as a GPS jump
    private const val MIN_SEGMENT_DIST_M = 1.0
    private const val MAX_SEGMENT_GAP_SEC = 30.0 // matches the auto-pause gap threshold elsewhere
    private const val MAX_GRADE = 0.25

    fun filterPoints(points: List<LocationPoint>): List<LocationPoint> =
        points.filter { it.accuracy <= ACCURACY_THRESHOLD_M }

    fun totalDistanceKm(points: List<LocationPoint>): Double {
        val filtered = filterPoints(points)
        if (filtered.size < 2) return 0.0
        return filtered.zipWithNext().sumOf { (a, b) -> haversineKm(a.lat, a.lng, b.lat, b.lng) }
    }

    fun elevationGainM(points: List<LocationPoint>, demCorrected: Boolean = false): Double {
        val filtered = filterPoints(points)
        if (filtered.size < 2) return 0.0
        val gate = if (demCorrected) ELEVATION_NOISE_GATE_DEM_M else ELEVATION_NOISE_GATE_M
        return filtered.zipWithNext()
            .sumOf { (a, b) -> max(0.0, b.altitude - a.altitude - gate) }
    }

    fun avgPaceMinPerKm(distanceKm: Double, durationMs: Long): Double {
        if (distanceKm <= 0.0 || durationMs <= 0) return 0.0
        return (durationMs / 60_000.0) / distanceKm
    }

    fun avgSpeedKmh(distanceKm: Double, durationMs: Long): Double {
        if (durationMs <= 0) return 0.0
        return distanceKm / (durationMs / 3_600_000.0)
    }

    fun topSpeedKmh(points: List<LocationPoint>): Double =
        (points.maxOfOrNull { it.speed } ?: 0f) * 3.6

    /** Moving time: sum of intervals between consecutive points where the gap is < 30 s (excludes auto-pause gaps). */
    fun movingTimeSec(points: List<LocationPoint>): Long {
        if (points.size < 2) return 0L
        return points.zipWithNext().sumOf { (a, b) ->
            val gap = b.timestamp - a.timestamp
            if (gap in 1L..30_000L) gap else 0L
        } / 1000L
    }

    /** Stopped time = total duration minus moving time derived from point timestamps. */
    fun stoppedTimeSec(totalDurationMs: Long, points: List<LocationPoint>): Long =
        ((totalDurationMs / 1000L) - movingTimeSec(points)).coerceAtLeast(0L)

    /**
     * Per-segment energy estimate using real speed and grade between consecutive points,
     * rather than a flat kcal/kg/km rate.
     *
     * Cycling: physics-based power model (rolling resistance + gravity + aerodynamic drag)
     * over combined rider+bike mass, converted to metabolic cost via gross efficiency —
     * the ~0.24 efficiency constant conveniently makes kcal ≈ kJ of mechanical work, the
     * standard cycling-computer rule of thumb.
     *
     * Running/walking: ACSM grade-adjusted VO2 equations (rider weight only).
     */
    fun estimatedCaloriesKcal(
        points: List<LocationPoint>,
        activityType: ActivityType,
        riderWeightKg: Double = DEFAULT_WEIGHT_KG,
        bikeWeightKg: Double = 0.0
    ): Double {
        val filtered = filterPoints(points)
        if (filtered.size < 2) return 0.0
        return filtered.zipWithNext()
            .sumOf { (a, b) -> segmentCaloriesKcal(a, b, activityType, riderWeightKg, bikeWeightKg) }
    }

    /**
     * Energy cost of a single a->b segment. Exposed separately so a live tracking session
     * can accumulate calories incrementally per new point instead of re-summing the whole
     * ride on every update.
     */
    fun segmentCaloriesKcal(
        a: LocationPoint,
        b: LocationPoint,
        activityType: ActivityType,
        riderWeightKg: Double = DEFAULT_WEIGHT_KG,
        bikeWeightKg: Double = 0.0
    ): Double {
        val dtSec = (b.timestamp - a.timestamp) / 1000.0
        if (dtSec <= 0.0 || dtSec > MAX_SEGMENT_GAP_SEC) return 0.0

        val distM = haversineKm(a.lat, a.lng, b.lat, b.lng) * 1000.0
        if (distM < MIN_SEGMENT_DIST_M) return 0.0

        val speedMs = distM / dtSec
        if (speedMs > MAX_PLAUSIBLE_SEGMENT_SPEED_MS) return 0.0

        val grade = ((b.altitude - a.altitude) / distM).coerceIn(-MAX_GRADE, MAX_GRADE)

        return when (activityType) {
            ActivityType.CYCLING -> {
                val mass = riderWeightKg + bikeWeightKg
                val pRolling = ROLLING_RESISTANCE_COEFF * mass * GRAVITY_MS2 * speedMs
                val pGravity = mass * GRAVITY_MS2 * speedMs * grade
                val pAero = 0.5 * AIR_DENSITY_KG_M3 * CYCLING_DRAG_AREA_M2 * speedMs.pow(3)
                val pTotal = max(0.0, pRolling + pGravity + pAero)
                (pTotal * dtSec) / CYCLING_GROSS_EFFICIENCY / JOULES_PER_KCAL
            }
            ActivityType.RUNNING, ActivityType.WALKING -> {
                val speedMPerMin = speedMs * 60.0
                val vo2 = if (activityType == ActivityType.RUNNING) {
                    0.2 * speedMPerMin + 0.9 * speedMPerMin * grade + 3.5
                } else {
                    0.1 * speedMPerMin + 1.8 * speedMPerMin * grade + 3.5
                }.coerceAtLeast(3.5)
                val kcalPerMin = vo2 * riderWeightKg * 5.0 / 1000.0
                kcalPerMin * (dtSec / 60.0)
            }
        }
    }

    /**
     * Computes per-km lap splits from raw GPS points.
     * Each split covers exactly 1 km of cumulative distance (using haversine between filtered points).
     */
    fun computeLapSplits(points: List<LocationPoint>): List<LapSplit> {
        val filtered = filterPoints(points)
        if (filtered.size < 2) return emptyList()

        val splits = mutableListOf<LapSplit>()
        var lapStart = 0
        var cumulativeKm = 0.0
        var lapKm = 0.0

        for (i in 1 until filtered.size) {
            val seg = haversineKm(
                filtered[i - 1].lat, filtered[i - 1].lng,
                filtered[i].lat, filtered[i].lng
            )
            lapKm += seg
            cumulativeKm += seg

            if (lapKm >= 1.0) {
                val lapDurationMs = filtered[i].timestamp - filtered[lapStart].timestamp
                val speedKmh = if (lapDurationMs > 0) 1.0 / (lapDurationMs / 3_600_000.0) else 0.0
                splits.add(LapSplit(splits.size + 1, lapDurationMs, speedKmh))
                lapStart = i
                lapKm = 0.0
            }
        }
        return splits
    }

    private fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        return EARTH_RADIUS_KM * 2 * asin(sqrt(a))
    }
}
