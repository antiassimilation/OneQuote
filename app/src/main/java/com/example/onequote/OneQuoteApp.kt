package com.example.onequote

import android.app.Application
import com.example.onequote.data.network.QuoteApiClient
import com.example.onequote.data.repo.QuoteRepository
import com.example.onequote.data.store.AppSettingsStore
import com.example.onequote.scheduler.RefreshScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class OneQuoteApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val repository: QuoteRepository by lazy {
        QuoteRepository(
            store = AppSettingsStore(this),
            apiClient = QuoteApiClient()
        )
    }

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            repository.observeSettings()
                .map { RefreshScheduler.normalizeMinutes(it.autoRefreshMinutes) }
                .distinctUntilChanged()
                .collect { minutes ->
                    // 应用级维持周期任务，避免仅依赖前台页面入口导致后台自动刷新未被调度。
                    RefreshScheduler.schedule(this@OneQuoteApp, minutes)
                }
        }
    }
}

