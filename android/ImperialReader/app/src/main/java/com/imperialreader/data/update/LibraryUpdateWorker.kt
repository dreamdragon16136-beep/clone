package com.imperialreader.data.update

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imperialreader.util.app
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class LibraryUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val prefs = applicationContext.app.prefs
        if (!prefs.refreshOnStart || prefs.libraryUpdateIntervalMinutes <= 0L) return Result.success()
        return try {
            val failures = AtomicInteger()
            applicationContext.app.repo.updateLibrary(onFailure = { _, _ -> failures.incrementAndGet() })
            if (failures.get() > 0) {
                throw IOException("Could not update ${failures.get()} library entries")
            }
            prefs.libraryUpdatedAt = System.currentTimeMillis()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Automatic library update failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "LibraryUpdateWorker"
        private const val MAX_ATTEMPTS = 3
    }
}

object LibraryUpdateScheduler {
    private const val UNIQUE_NAME = "imperial_reader_automatic_library_update"

    fun onAppStart(context: Context) {
        val prefs = context.applicationContext.app.prefs
        onSettingChanged(context, prefs.refreshOnStart, prefs.libraryUpdateIntervalMinutes)
    }

    fun onSettingChanged(context: Context, enabled: Boolean, intervalMinutes: Long) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        if (!enabled || intervalMinutes <= 0L) {
            workManager.cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(intervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        workManager.enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
