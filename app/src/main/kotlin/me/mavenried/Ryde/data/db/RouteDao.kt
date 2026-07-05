package me.mavenried.Ryde.data.db

import androidx.room.*
import me.mavenried.Ryde.data.model.RouteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(route: RouteEntity)

    @Query("SELECT * FROM routes ORDER BY startTime DESC")
    fun getAllRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes ORDER BY startTime DESC")
    suspend fun getAllRoutesOnce(): List<RouteEntity>

    @Query("SELECT * FROM routes WHERE id = :id")
    suspend fun getRouteById(id: String): RouteEntity?

    @Query("SELECT * FROM routes WHERE completed = 0 ORDER BY startTime DESC LIMIT 1")
    suspend fun getLastIncompleteRoute(): RouteEntity?

    @Query("UPDATE routes SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("UPDATE routes SET category = :category WHERE id = :id")
    suspend fun updateCategory(id: String, category: String)

    @Query("UPDATE routes SET elevationGainM = :elevationGainM, calories = :calories, elevationCorrected = 1 WHERE id = :id")
    suspend fun applyElevationCorrection(id: String, elevationGainM: Double, calories: Double)

    @Delete
    suspend fun delete(route: RouteEntity)

    @Query("DELETE FROM routes")
    suspend fun deleteAll()
}
