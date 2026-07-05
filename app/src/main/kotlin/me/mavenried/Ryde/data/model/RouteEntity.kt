package me.mavenried.Ryde.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey val id: String,
    val activityType: String,
    val name: String,
    val startTime: Long,
    val endTime: Long,
    val distanceKm: Double,
    val avgPace: Double,
    val avgSpeedKmh: Double,
    val elevationGainM: Double,
    val calories: Double = 0.0,
    val category: String = "Other",
    val completed: Boolean = true,
    val elevationCorrected: Boolean = false
)
