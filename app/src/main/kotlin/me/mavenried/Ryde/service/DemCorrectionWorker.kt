package me.mavenried.Ryde.service

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.mavenried.Ryde.data.db.AppDatabase
import me.mavenried.Ryde.domain.repository.RouteRepository
import me.mavenried.Ryde.util.UserPrefs
import java.util.concurrent.TimeUnit

/**
 * One-off post-ride job that replaces raw GPS altitude with DEM-corrected elevation
 * (see RouteRepository.correctElevation) and recomputes elevation gain + calories from it.
 * Requires network; WorkManager retries with backoff if the ride finished offline.
 */
class DemCorrectionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val routeId = inputData.getString(KEY_ROUTE_ID) ?: return Result.failure()
        val repository = RouteRepository(AppDatabase.getInstance(applicationContext))
        val riderWeightKg = UserPrefs.getWeightKg(applicationContext)
        val bikeWeightKg = UserPrefs.getBikeWeightKg(applicationContext)

        return repository.correctElevation(routeId, riderWeightKg, bikeWeightKg).fold(
            onSuccess = { Result.success() },
            onFailure = { if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure() }
        )
    }

    companion object {
        private const val KEY_ROUTE_ID = "routeId"
        private const val MAX_ATTEMPTS = 5

        fun enqueue(context: Context, routeId: String) {
            val request = OneTimeWorkRequestBuilder<DemCorrectionWorker>()
                .setInputData(workDataOf(KEY_ROUTE_ID to routeId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "dem_correction_$routeId",
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
