package me.mavenried.Ryde.data.db

import androidx.room.*
import me.mavenried.Ryde.data.model.LocationPointEntity

@Dao
interface LocationPointDao {
    @Insert
    suspend fun insertAll(points: List<LocationPointEntity>)

    @Query("SELECT * FROM location_points WHERE routeId = :routeId ORDER BY timestamp ASC")
    suspend fun getPointsForRoute(routeId: String): List<LocationPointEntity>

    @Update
    suspend fun updateAll(points: List<LocationPointEntity>)
}
