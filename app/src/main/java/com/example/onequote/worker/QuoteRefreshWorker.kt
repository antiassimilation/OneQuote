package com.example.onequote.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.onequote.OneQuoteApp
import com.example.onequote.widget.OneQuoteWidgetProvider

class QuoteRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as OneQuoteApp).repository
        val refreshed = repository.refreshFromEnabledSources()
        return if (refreshed.isSuccess) {
            OneQuoteWidgetProvider.refreshAll(applicationContext)
            Result.success()
        } else {
            Result.retry()
        }
    }
}

