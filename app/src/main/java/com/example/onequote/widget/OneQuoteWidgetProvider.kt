package com.example.onequote.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.example.onequote.OneQuoteApp
import com.example.onequote.R
import com.example.onequote.data.model.LayoutMode
import com.example.onequote.data.model.WidgetClickAction
import com.example.onequote.data.util.AppDebugLogger
import com.example.onequote.data.util.StyleParsers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

class OneQuoteWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        refreshAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val repo = (context.applicationContext as OneQuoteApp).repository
                when (intent.action) {
                    ACTION_MANUAL_REFRESH -> {
                        val shouldRefresh = runRefreshAction(context, repo)
                        if (shouldRefresh) refreshAll(context)
                    }

                    ACTION_WIDGET_TAP -> {
                        handleWidgetTap(context, intent, repo)
                    }

                }
            }.onFailure {
                showToastMain(context, "操作失败，请稍后重试")
            }
            pendingResult.finish()
        }
    }

    companion object {
        private const val TAG = "OneQuoteWidget"
        const val ACTION_MANUAL_REFRESH = "com.example.onequote.action.MANUAL_REFRESH"
        const val ACTION_WIDGET_TAP = "com.example.onequote.action.WIDGET_TAP"

        private const val EXTRA_APPWIDGET_ID = "extra_appwidget_id"
        private const val DOUBLE_TAP_WINDOW_MS = 350L
        private const val SINGLE_COMMIT_GRACE_MS = 80L
        private const val INVALID_WIDGET_ID = -1
        private const val RUNTIME_FLAGS_PREFS = "onequote_runtime_flags"
        private const val NEED_AUTOSTART_GUIDE_AFTER_WIDGET_REFRESH = "need_autostart_guide_after_widget_refresh"
        // 动作互斥窗口：避免边界时序下“单击动作 + 双击动作”几乎同时执行。
        private const val ACTION_MUTEX_WINDOW_MS = 260L
        // 刷新派发节流：避免短时间重复触发刷新，降低API高频调用风险。
        private const val REFRESH_DISPATCH_THROTTLE_MS = 1200L

        // 双击判定使用进程内内存态，避免DataStore持久化时序导致节拍失真。
        private val tapLock = Any()
        private val actionLock = Any()
        private val tapScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var lastTapEventTimeMs: Long = 0L
        private var lastTapWidgetId: Int = INVALID_WIDGET_ID
        private var lastTapToken: Int = 0
        private var pendingSingleJob: Job? = null
        private var lastActionAtElapsedMs: Long = 0L
        private var lastAction: WidgetClickAction? = null
        private var lastRefreshDispatchAtElapsedMs: Long = 0L
        private var currentToast: Toast? = null
        private val bgCacheLock = Any()
        private var cachedBgKey: String? = null
        private var cachedBgBitmap: Bitmap? = null

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, OneQuoteWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return

            CoroutineScope(Dispatchers.IO).launch {
                val repo = (context.applicationContext as OneQuoteApp).repository
                val settings = repo.getSettings()
                ids.forEach { id ->
                    val views = RemoteViews(context.packageName, R.layout.widget_one_quote)
                    val quote = settings.lastQuote
                    val style = settings.style

                    val quoteText = when {
                        quote == null -> "请先在应用中添加并启用来源"
                        style.layoutMode == LayoutMode.VERTICAL -> StyleParsers.asVerticalText(quote.text)
                        else -> quote.text
                    }
                    val authorText = quote?.author?.takeIf { it.isNotBlank() }?.let { "— $it" } ?: ""

                    val options = manager.getAppWidgetOptions(id)
                    val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
                    val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                    val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minWidth)
                    val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minHeight)
                    val spanX = estimateSpan(maxWidth)
                    val spanY = estimateSpan(maxHeight)
                    val baseSp = StyleParsers.percentToBaseTextSp(style.fontScalePercent)
                    val quoteSp = StyleParsers.adaptiveWidgetQuoteSp(baseSp, spanX, spanY, quote?.text?.length ?: 0)
                    val authorSp = (quoteSp * 0.55f).coerceIn(10f, 20f)

                    views.setTextViewText(R.id.widgetQuote, quoteText)
                    views.setTextViewText(R.id.widgetAuthor, authorText)
                    views.setViewVisibility(
                        R.id.widgetAuthor,
                        if (authorText.isBlank()) View.GONE else View.VISIBLE
                    )

                    StyleParsers.parseRgbaOrNull(style.textRgba)?.let {
                        views.setTextColor(R.id.widgetQuote, it)
                    }
                    StyleParsers.parseRgbaOrNull(style.authorRgba)?.let {
                        views.setTextColor(R.id.widgetAuthor, it)
                    }

                    views.setTextViewTextSize(
                        R.id.widgetQuote,
                        TypedValue.COMPLEX_UNIT_SP,
                        quoteSp
                    )
                    views.setTextViewTextSize(R.id.widgetAuthor, TypedValue.COMPLEX_UNIT_SP, authorSp)

                    val bgColor = StyleParsers.parseRgbaOrNull(style.backgroundRgba)
                    if (bgColor != null) {
                        val corner = StyleParsers.levelToCornerDp(style.cornerRadiusLevel)
                        views.setImageViewBitmap(R.id.widgetBackground, roundedBackgroundCached(bgColor, corner))
                    }

                    val clickIntent = Intent(context, OneQuoteWidgetProvider::class.java).apply {
                        action = ACTION_WIDGET_TAP
                        putExtra(EXTRA_APPWIDGET_ID, id)
                    }
                    val clickPendingIntent = PendingIntent.getBroadcast(
                        context,
                        1000 + id,
                        clickIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widgetRoot, clickPendingIntent)

                    manager.updateAppWidget(id, views)
                }
            }
        }

        private suspend fun handleWidgetTap(
            context: Context,
            intent: Intent,
            repo: com.example.onequote.data.repo.QuoteRepository
        ): Boolean {
            val widgetId = intent.getIntExtra(EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return false

            val appContext = context.applicationContext
            // 以当前接收时间作为事件时间（eventTime）。
            val now = SystemClock.elapsedRealtime()
            val settings = repo.getSettings()
            val (prevTapAt, prevWidgetId, prevToken) = synchronized(tapLock) {
                Triple(lastTapEventTimeMs, lastTapWidgetId, lastTapToken)
            }
            val deltaFromLastTap = now - prevTapAt
            Log.d(
                TAG,
                "tap_received widgetId=$widgetId now=$now lastTap=$prevTapAt delta=$deltaFromLastTap window=$DOUBLE_TAP_WINDOW_MS lastToken=$prevToken lastWidget=$prevWidgetId single=${settings.singleClickAction} double=${settings.doubleClickAction}"
            )
            AppDebugLogger.log(
                TAG,
                "tap_received widgetId=$widgetId delta=$deltaFromLastTap window=$DOUBLE_TAP_WINDOW_MS single=${settings.singleClickAction} double=${settings.doubleClickAction}"
            )

            val isDoubleTap = (deltaFromLastTap in 1..DOUBLE_TAP_WINDOW_MS) && prevWidgetId == widgetId
            if (isDoubleTap) {
                Log.d(TAG, "tap_classified=DOUBLE widgetId=$widgetId")
                AppDebugLogger.log(TAG, "tap_classified=DOUBLE widgetId=$widgetId")
                synchronized(tapLock) {
                    pendingSingleJob?.cancel()
                    pendingSingleJob = null
                    lastTapEventTimeMs = 0L
                    lastTapWidgetId = INVALID_WIDGET_ID
                    lastTapToken = 0
                }
                val shouldRefresh = runWidgetAction(appContext, settings.doubleClickAction, repo)
                if (shouldRefresh) refreshAll(appContext)
                return true
            }

            if (prevTapAt > 0L && prevWidgetId == widgetId) {
                val lateBy = (deltaFromLastTap - DOUBLE_TAP_WINDOW_MS).coerceAtLeast(0L)
                Log.d(TAG, "tap_not_double widgetId=$widgetId delta=$deltaFromLastTap lateBy=$lateBy")
                AppDebugLogger.log(TAG, "tap_not_double widgetId=$widgetId delta=$deltaFromLastTap lateBy=$lateBy")
            }

            val tapToken = Random.nextInt(100_000, 999_999)
            synchronized(tapLock) {
                pendingSingleJob?.cancel()
                lastTapEventTimeMs = now
                lastTapWidgetId = widgetId
                lastTapToken = tapToken
            }
            Log.d(TAG, "tap_classified=PENDING_SINGLE widgetId=$widgetId token=$tapToken")
            AppDebugLogger.log(TAG, "tap_classified=PENDING_SINGLE widgetId=$widgetId token=$tapToken")

            val singleJob = tapScope.launch {
                // 非阻塞式单击提交：不会占用当前广播处理链，减少双击竞争。
                delay(DOUBLE_TAP_WINDOW_MS + SINGLE_COMMIT_GRACE_MS)
                val (latestToken, latestWidget) = synchronized(tapLock) {
                    Pair(lastTapToken, lastTapWidgetId)
                }
                val shouldRunSingle = latestToken == tapToken && latestWidget == widgetId
                Log.d(
                    TAG,
                    "single_commit_check widgetId=$widgetId token=$tapToken shouldRun=$shouldRunSingle latestToken=$latestToken latestWidget=$latestWidget"
                )
                AppDebugLogger.log(
                    TAG,
                    "single_commit_check widgetId=$widgetId token=$tapToken shouldRun=$shouldRunSingle latestToken=$latestToken latestWidget=$latestWidget"
                )
                if (!shouldRunSingle) return@launch

                synchronized(tapLock) {
                    if (lastTapToken == tapToken && lastTapWidgetId == widgetId) {
                        lastTapEventTimeMs = 0L
                        lastTapWidgetId = INVALID_WIDGET_ID
                        lastTapToken = 0
                        pendingSingleJob = null
                    }
                }
                val latestSettings = repo.getSettings()
                Log.d(TAG, "tap_classified=SINGLE widgetId=$widgetId action=${latestSettings.singleClickAction}")
                AppDebugLogger.log(TAG, "tap_classified=SINGLE widgetId=$widgetId action=${latestSettings.singleClickAction}")
                val shouldRefresh = runWidgetAction(appContext, latestSettings.singleClickAction, repo)
                if (shouldRefresh) refreshAll(appContext)
            }
            synchronized(tapLock) {
                if (lastTapToken == tapToken && lastTapWidgetId == widgetId) {
                    pendingSingleJob = singleJob
                }
            }
            return true
        }

        private suspend fun runWidgetAction(
            context: Context,
            action: WidgetClickAction,
            repo: com.example.onequote.data.repo.QuoteRepository
        ): Boolean {
            val nowElapsed = SystemClock.elapsedRealtime()

            // 1) 全动作互斥：防止边界条件下两个动作在极短时间连续执行。
            if (shouldBlockByActionMutex(nowElapsed)) {
                val last = synchronized(actionLock) { lastAction }
                val lastAt = synchronized(actionLock) { lastActionAtElapsedMs }
                val delta = (nowElapsed - lastAt).coerceAtLeast(0L)
                Log.d(TAG, "run_widget_action_blocked reason=action_mutex action=$action last=$last delta=$delta")
                AppDebugLogger.log(TAG, "run_widget_action_blocked reason=action_mutex action=$action last=$last delta=$delta")
                return false
            }

            // 2) 刷新专用节流：避免短时重复刷新造成多入口叠加与API压力。
            if (action == WidgetClickAction.REFRESH && shouldBlockRefreshDispatch(nowElapsed)) {
                val lastRefreshAt = synchronized(actionLock) { lastRefreshDispatchAtElapsedMs }
                val delta = (nowElapsed - lastRefreshAt).coerceAtLeast(0L)
                Log.d(TAG, "run_widget_action_blocked reason=refresh_throttle action=$action delta=$delta")
                AppDebugLogger.log(TAG, "run_widget_action_blocked reason=refresh_throttle action=$action delta=$delta")
                return false
            }

            markActionDispatched(action, nowElapsed)
            Log.d(TAG, "run_widget_action action=$action")
            AppDebugLogger.log(TAG, "run_widget_action action=$action")
            return when (action) {
                WidgetClickAction.REFRESH -> runRefreshAction(context, repo)
                WidgetClickAction.COPY -> {
                    runCopyAction(context, repo)
                    false
                }

                WidgetClickAction.FAVORITE -> {
                    runFavoriteAction(context, repo)
                    false
                }
            }
        }

        private suspend fun runRefreshAction(
            context: Context,
            repo: com.example.onequote.data.repo.QuoteRepository
        ): Boolean {
            markNeedAutoStartGuide(context)
            val now = System.currentTimeMillis()
            val settings = repo.getSettings()
            val remain = StyleParsers.cooldownRemainSeconds(settings.lastManualRefreshAtMillis, now)
            Log.d(TAG, "run_refresh now=$now lastManual=${settings.lastManualRefreshAtMillis} remain=$remain")
            AppDebugLogger.log(TAG, "run_refresh remain=$remain")
            if (remain > 0) {
                showToastMain(context, "请在${remain}秒后重试")
                return false
            }

            repo.markManualRefreshAt(now)
            val result = repo.refreshFromEnabledSources()
            if (result.isFailure) {
                showToastMain(context, "刷新失败，已使用上次内容")
                return false
            }
            return true
        }

        private suspend fun runCopyAction(
            context: Context,
            repo: com.example.onequote.data.repo.QuoteRepository
        ) {
            val quote = repo.getSettings().lastQuote
            if (quote == null) {
                showToastMain(context, "暂无可复制内容")
                return
            }
            val author = quote.author?.takeIf { it.isNotBlank() }
            val copyText = if (author.isNullOrBlank()) quote.text else "${quote.text}\n— $author"
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.setPrimaryClip(ClipData.newPlainText("onequote", copyText))
            showToastMain(context, "已复制到剪贴板")
        }

        private suspend fun runFavoriteAction(
            context: Context,
            repo: com.example.onequote.data.repo.QuoteRepository
        ) {
            val result = repo.addFavoriteFromLastQuote()
            Log.d(TAG, "run_favorite success=${result.isSuccess}")
            AppDebugLogger.log(TAG, "run_favorite success=${result.isSuccess}")
            if (result.isSuccess) {
                showToastMain(context, "已加入收藏", Toast.LENGTH_LONG)
            } else {
                showToastMain(context, "收藏失败：暂无内容")
            }
        }

        private suspend fun showToastMain(context: Context, message: String, duration: Int = Toast.LENGTH_SHORT) {
            AppDebugLogger.log(TAG, "toast_attempt message=$message duration=$duration")
            runCatching {
                withContext(Dispatchers.Main) {
                    currentToast?.cancel()
                    currentToast = Toast.makeText(context.applicationContext, message, duration)
                    currentToast?.show()
                }
            }.onSuccess {
                AppDebugLogger.log(TAG, "toast_shown message=$message duration=$duration")
            }.onFailure {
                AppDebugLogger.log(TAG, "toast_failed message=$message error=${it.message}")
            }
        }

        /**
         * 动作互斥校验：
         * 在短窗口内已执行过任意动作，则阻止本次动作，避免“单击与双击同时生效”的边界冲突。
         */
        private fun shouldBlockByActionMutex(nowElapsed: Long): Boolean {
            synchronized(actionLock) {
                if (lastActionAtElapsedMs <= 0L) return false
                val delta = nowElapsed - lastActionAtElapsedMs
                return delta in 0..ACTION_MUTEX_WINDOW_MS
            }
        }

        /**
         * 刷新派发节流校验：
         * 仅针对刷新动作，防止短时间重复发起刷新请求。
         */
        private fun shouldBlockRefreshDispatch(nowElapsed: Long): Boolean {
            synchronized(actionLock) {
                if (lastRefreshDispatchAtElapsedMs <= 0L) return false
                val delta = nowElapsed - lastRefreshDispatchAtElapsedMs
                return delta in 0..REFRESH_DISPATCH_THROTTLE_MS
            }
        }

        /** 记录最近动作执行时间，用于互斥与刷新节流判定。 */
        private fun markActionDispatched(action: WidgetClickAction, nowElapsed: Long) {
            synchronized(actionLock) {
                lastAction = action
                lastActionAtElapsedMs = nowElapsed
                if (action == WidgetClickAction.REFRESH) {
                    lastRefreshDispatchAtElapsedMs = nowElapsed
                }
            }
        }

        /**
         * 记录“用户在小组件上触发过刷新”，供应用前台页引导自启动权限。
         * 注意：小组件广播场景不应直接拉起权限设置页，避免后台拉起限制与系统拦截。
         */
        private fun markNeedAutoStartGuide(context: Context) {
            val prefs = context.applicationContext.getSharedPreferences(RUNTIME_FLAGS_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(NEED_AUTOSTART_GUIDE_AFTER_WIDGET_REFRESH, false)) return
            prefs.edit().putBoolean(NEED_AUTOSTART_GUIDE_AFTER_WIDGET_REFRESH, true).apply()
            AppDebugLogger.log(TAG, "autostart_guide_marked_from_widget_refresh=true")
        }

        /**
         * 缓存背景位图，减少刷新时重复创建大位图带来的CPU与内存抖动。
         */
        private fun roundedBackgroundCached(color: Int, cornerDp: Float): Bitmap {
            val key = "$color|$cornerDp"
            synchronized(bgCacheLock) {
                if (cachedBgKey == key && cachedBgBitmap != null) {
                    return cachedBgBitmap as Bitmap
                }
                val next = roundedBackground(color, cornerDp)
                cachedBgKey = key
                cachedBgBitmap = next
                return next
            }
        }

        private fun estimateSpan(sizeDp: Int): Int {
            // 对应Launcher常见网格宽高，避免复杂计算与高开销。
            return (sizeDp / 70).coerceIn(1, 6)
        }

        private fun roundedBackground(color: Int, cornerDp: Float): Bitmap {
            val width = 900
            val height = 500
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
            val radius = cornerDp * 3f
            canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, paint)
            return bitmap
        }
    }
}

