package com.example.onequote.scheduler

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.onequote.worker.QuoteRefreshWorker
import java.util.concurrent.TimeUnit

object RefreshScheduler {
    const val UNIQUE_WORK_NAME = "onequote_auto_refresh"

    fun schedule(context: Context, minutes: Int) {
        val safeMinutes = minutes.coerceAtLeast(30)
        val request = PeriodicWorkRequestBuilder<QuoteRefreshWorker>(safeMinutes.toLong(), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

