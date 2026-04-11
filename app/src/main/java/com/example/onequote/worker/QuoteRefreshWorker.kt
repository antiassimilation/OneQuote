package com.example.onequote.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.onequote.OneQuoteApp
import com.example.onequote.scheduler.RefreshScheduler
import com.example.onequote.widget.OneQuoteWidgetProvider

class QuoteRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as OneQuoteApp).repository
        val settings = repository.getSettings()
        val autoRefreshMinutes = settings.autoRefreshMinutes
        val usesOneTimeSchedule = RefreshScheduler.usesOneTimeSchedule(autoRefreshMinutes)
        val refreshed = repository.refreshFromEnabledSources()

        if (usesOneTimeSchedule) {
            // 1~14 分钟区间使用单次任务自循环调度，以兼容系统周期任务的最小间隔限制。
            RefreshScheduler.schedule(applicationContext, autoRefreshMinutes)
        }

        return if (refreshed.isSuccess) {
            OneQuoteWidgetProvider.refreshAll(applicationContext)
            Result.success()
        } else {
            // 失败原因分级：
            // - in_flight/throttled/no_enabled_source 属于非网络异常，不重试，避免后台频繁唤醒。
            // - 其他异常交由WorkManager重试策略处理。
            val reason = refreshed.exceptionOrNull()?.message.orEmpty()
            if (usesOneTimeSchedule) {
                return Result.success()
            }
            if (
                reason == "refresh_in_flight" ||
                reason == "refresh_throttled" ||
                reason == "no_enabled_source"
            ) {
                Result.success()
            } else {
                Result.retry()
            }
        }
    }
}

