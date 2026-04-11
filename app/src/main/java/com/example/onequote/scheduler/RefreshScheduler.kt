package com.example.onequote.scheduler

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.onequote.data.util.AppDebugLogger
import com.example.onequote.worker.QuoteRefreshWorker
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    private const val UNIQUE_PERIODIC_WORK_NAME = "onequote_auto_refresh_periodic"
    private const val UNIQUE_ONE_TIME_WORK_NAME = "onequote_auto_refresh_onetime"
    private const val TAG = "RefreshScheduler"
    private const val MIN_REFRESH_MINUTES = 1
    private const val MAX_REFRESH_MINUTES = 60
    private const val PERIODIC_WORK_MINUTES = 15

    internal fun normalizeMinutes(minutes: Int): Int = minutes.coerceIn(MIN_REFRESH_MINUTES, MAX_REFRESH_MINUTES)

    internal fun usesOneTimeSchedule(minutes: Int): Boolean = normalizeMinutes(minutes) < PERIODIC_WORK_MINUTES

    fun schedule(context: Context, minutes: Int) {
        val safeMinutes = normalizeMinutes(minutes)
        val workManager = WorkManager.getInstance(context)
        AppDebugLogger.log(TAG, "schedule minutes=$safeMinutes oneTime=${usesOneTimeSchedule(safeMinutes)}")

        if (usesOneTimeSchedule(safeMinutes)) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
            val request = OneTimeWorkRequestBuilder<QuoteRefreshWorker>()
                .setInitialDelay(safeMinutes.toLong(), TimeUnit.MINUTES)
                .build()
            workManager.enqueueUniqueWork(
                UNIQUE_ONE_TIME_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            return
        }

        workManager.cancelUniqueWork(UNIQUE_ONE_TIME_WORK_NAME)
        val request = PeriodicWorkRequestBuilder<QuoteRefreshWorker>(safeMinutes.toLong(), TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancel(context: Context) {
        AppDebugLogger.log(TAG, "cancel")
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC_WORK_NAME)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_ONE_TIME_WORK_NAME)
    }
}

