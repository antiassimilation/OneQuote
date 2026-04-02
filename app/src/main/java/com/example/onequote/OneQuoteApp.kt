package com.example.onequote

import android.app.Application
import com.example.onequote.data.network.QuoteApiClient
import com.example.onequote.data.repo.QuoteRepository
import com.example.onequote.data.store.AppSettingsStore

class OneQuoteApp : Application() {
    val repository: QuoteRepository by lazy {
        QuoteRepository(
            store = AppSettingsStore(this),
            apiClient = QuoteApiClient()
        )
    }
}

