package me.mavenried.Ryde.domain.repository

import me.mavenried.Ryde.data.db.AppDatabase
import me.mavenried.Ryde.data.model.LocationPointEntity
import me.mavenried.Ryde.data.model.RouteEntity
import me.mavenried.Ryde.domain.model.ActivityType
import me.mavenried.Ryde.domain.model.LocationPoint
import me.mavenried.Ryde.domain.model.Route
import me.mavenried.Ryde.domain.util.TrackStats
import me.mavenried.Ryde.util.ElevationClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

class RouteRepository(private val db: AppDatabase) {

    /**
     * Replaces raw GPS altitude with DEM (Digital Elevation Model) values looked up from
     * Open-Topo-Data, then recomputes elevation gain and calories from the corrected profile.
     * No-op if the route is incomplete, already corrected, or the DEM lookup fails (network
     * errors are surfaced via the returned Result so the caller/worker can retry later).
     */
    suspend fun correctElevation(routeId: String, riderWeightKg: Double, bikeWeightKg: Double): Result<Unit> {
        val route = db.routeDao().getRouteById(routeId)
            ?: return Result.failure(IllegalStateException("Route $routeId not found"))
        if (!route.completed || route.elevationCorrected) return Result.success(Unit)

        val entities = db.locationPointDao().getPointsForRoute(routeId)
        if (entities.size < 2) return Result.success(Unit)

        val maxQueryPoints = 400.coerceAtMost(entities.size)
        val queryIndices = (0 until maxQueryPoints)
            .map { k -> (k * (entities.size - 1).toDouble() / (maxQueryPoints - 1)).roundToInt() }
            .distinct()
        val queryCoords = queryIndices.map { entities[it].lat to entities[it].lng }

        val elevations = ElevationClient.fetchElevations(queryCoords).getOrElse {
            return Result.failure(it)
        }

        val demAltitudes = interpolateAltitudes(entities.size, queryIndices, elevations)
        val corrected = entities.mapIndexed { i, e -> e.copy(altitude = demAltitudes[i]) }
        db.locationPointDao().updateAll(corrected)

        val correctedPoints = corrected.map { it.toDomain() }
        val newGain = TrackStats.elevationGainM(correctedPoints, demCorrected = true)
        val newCalories = TrackStats.estimatedCaloriesKcal(
            correctedPoints, ActivityType.valueOf(route.activityType), riderWeightKg, bikeWeightKg
        )
        db.routeDao().applyElevationCorrection(routeId, newGain, newCalories)
        return Result.success(Unit)
    }

    /** Linear interpolation of DEM samples (taken at queryIndices) across all point indices 0 until n. */
    private fun interpolateAltitudes(n: Int, queryIndices: List<Int>, elevations: List<Double>): List<Double> {
        val result = DoubleArray(n)
        var seg = 0
        for (i in 0 until n) {
            while (seg < queryIndices.size - 2 && i > queryIndices[seg + 1]) seg++
            val i0 = queryIndices[seg]
            val e0 = elevations[seg]
            result[i] = if (seg + 1 >= queryIndices.size) {
                e0
            } else {
                val i1 = queryIndices[seg + 1]
                val e1 = elevations[seg + 1]
                if (i1 == i0) e0 else e0 + (e1 - e0) * (i - i0).toDouble() / (i1 - i0)
            }
        }
        return result.toList()
    }

    fun getAllRoutes(): Flow<List<Route>> =
        db.routeDao().getAllRoutes().map { entities -> entities.map { it.toDomain() } }

    suspend fun getAllRoutesOnce(): List<Route> =
        db.routeDao().getAllRoutesOnce().map { it.toDomain() }

    suspend fun getRouteById(id: String): Route? =
        db.routeDao().getRouteById(id)?.toDomain()

    suspend fun getLastIncompleteRoute(): Route? =
        db.routeDao().getLastIncompleteRoute()?.toDomain()

    suspend fun getPointsForRoute(id: String): List<LocationPoint> =
        db.locationPointDao().getPointsForRoute(id).map { it.toDomain() }

    suspend fun saveRoute(route: Route) {
        db.routeDao().insert(route.toEntity())
    }

    suspend fun savePoints(routeId: String, points: List<LocationPoint>) {
        if (points.isEmpty()) return
        db.locationPointDao().insertAll(points.map { it.toEntity(routeId) })
    }

    suspend fun deleteRoute(id: String) {
        val entity = db.routeDao().getRouteById(id) ?: return
        db.routeDao().delete(entity)
    }

    suspend fun renameRoute(id: String, name: String) = db.routeDao().updateName(id, name)

    suspend fun updateTag(id: String, tag: String) = db.routeDao().updateCategory(id, tag)

    suspend fun deleteAllRoutes() = db.routeDao().deleteAll()

    private fun RouteEntity.toDomain() = Route(
        id = id,
        activityType = ActivityType.valueOf(activityType),
        name = name,
        startTime = startTime,
        endTime = endTime,
        distanceKm = distanceKm,
        avgPace = avgPace,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainM = elevationGainM,
        calories = calories,
        category = category,
        completed = completed,
        elevationCorrected = elevationCorrected
    )

    private fun Route.toEntity() = RouteEntity(
        id = id,
        activityType = activityType.name,
        name = name,
        startTime = startTime,
        endTime = endTime,
        distanceKm = distanceKm,
        avgPace = avgPace,
        avgSpeedKmh = avgSpeedKmh,
        elevationGainM = elevationGainM,
        calories = calories,
        category = category,
        completed = completed,
        elevationCorrected = elevationCorrected
    )

    private fun LocationPointEntity.toDomain() = LocationPoint(
        lat = lat, lng = lng, altitude = altitude,
        timestamp = timestamp, speed = speed, accuracy = accuracy,
        bearing = bearing, heartRate = heartRate,
    )

    private fun LocationPoint.toEntity(routeId: String) = LocationPointEntity(
        routeId = routeId, lat = lat, lng = lng, altitude = altitude,
        timestamp = timestamp, speed = speed, accuracy = accuracy,
        bearing = bearing, heartRate = heartRate,
    )
}
