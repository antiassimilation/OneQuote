package com.example.onequote.data.util

import android.content.Context

/**
 * 运行期短期标记存储：仅用于跨进程/跨入口传递轻量提示，不存放用户敏感数据。
 */
object RuntimeFlagStore {
    private const val PREFS_NAME = "onequote_runtime_flags"
    private const val KEY_NEED_AUTOSTART_GUIDE_AFTER_WIDGET_REFRESH = "need_autostart_guide_after_widget_refresh"
    private const val KEY_AUTOSTART_GUIDE_HAS_BEEN_SHOWN = "autostart_guide_has_been_shown"

    fun markNeedAutoStartGuide(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_AUTOSTART_GUIDE_HAS_BEEN_SHOWN, false)) return
        if (prefs.getBoolean(KEY_NEED_AUTOSTART_GUIDE_AFTER_WIDGET_REFRESH, false)) return
        prefs.edit().putBoolean(KEY_NEED_AUTOSTART_GUIDE_AFTER_WIDGET_REFRESH, true).apply()
    }

    fun consumeNeedAutoStartGuide(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val shouldShow = prefs.getBoolean(KEY_NEED_AUTOSTART_GUIDE_AFTER_WIDGET_REFRESH, false)
        if (shouldShow) {
            prefs.edit()
                .putBoolean(KEY_NEED_AUTOSTART_GUIDE_AFTER_WIDGET_REFRESH, false)
                .putBoolean(KEY_AUTOSTART_GUIDE_HAS_BEEN_SHOWN, true)
                .apply()
        }
        return shouldShow
    }
}
