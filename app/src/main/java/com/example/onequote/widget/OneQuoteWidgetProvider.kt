package com.example.onequote.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import com.example.onequote.data.model.TextAlignMode
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
        AppDebugLogger.log(TAG, "on_update ids=${appWidgetIds.joinToString(prefix = "[", postfix = "]")}")
        refreshAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        AppDebugLogger.log(TAG, "on_receive action=${intent.action}")
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

        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, OneQuoteWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            AppDebugLogger.log(TAG, "refresh_all ids=${ids.joinToString(prefix = "[", postfix = "]")}")
            if (ids.isEmpty()) {
                AppDebugLogger.log(TAG, "refresh_all_skipped reason=no_widget_instance")
                return
            }

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
                    val isVertical = style.layoutMode == LayoutMode.VERTICAL
                    val authorText = quote?.author?.takeIf { it.isNotBlank() }?.let { "— $it" } ?: ""
                    val authorVerticalDisplay = if (isVertical) StyleParsers.asVerticalText(authorText) else authorText

                    // 使用绝对字号配置：不再沿用旧比例语义。
                    val baseQuoteSp = StyleParsers.clampQuoteFontSp(style.quoteFontSp).toFloat()
                    val baseAuthorSp = StyleParsers.clampAuthorFontSp(style.authorFontSp).toFloat()

                    views.setViewVisibility(R.id.horizontalContainer, if (isVertical) View.GONE else View.VISIBLE)
                    views.setViewVisibility(R.id.verticalContainer, if (isVertical) View.VISIBLE else View.GONE)

                    val layoutPlan = resolveTextLayoutPlan(
                        manager = manager,
                        widgetId = id,
                        isVertical = isVertical,
                        quoteText = quoteText,
                        hasAuthor = authorText.isNotBlank(),
                        baseQuoteSp = baseQuoteSp,
                        baseAuthorSp = baseAuthorSp
                    )

                    // 背景与圆角映射：使用 bitmap 承载动态圆角背景，避免未保存态影响桌面。
                    applyBackgroundStyle(context, manager, id, views, style.backgroundRgba, style.cornerRadiusLevel)

                    // 对齐映射：仅横排正文字段生效，行为与应用内预览一致。
                    if (!isVertical) {
                        val horizontalGravity = alignToGravity(style.textAlignMode)
                        views.setInt(
                            R.id.widgetQuote,
                            "setGravity",
                            horizontalGravity
                        )
                        WidgetShadowLayerRenderer.quoteShadowIds.forEach { shadowId ->
                            views.setInt(shadowId, "setGravity", horizontalGravity)
                        }
                    }

                    views.setTextViewText(R.id.widgetQuote, quoteText)
                    WidgetShadowLayerRenderer.bindText(views, WidgetShadowLayerRenderer.quoteShadowIds, quoteText)
                    views.setTextViewText(R.id.widgetAuthor, authorText)
                    WidgetShadowLayerRenderer.bindText(views, WidgetShadowLayerRenderer.authorShadowIds, authorText)
                    views.setTextViewText(R.id.widgetQuoteVertical, quoteText)
                    WidgetShadowLayerRenderer.bindText(views, WidgetShadowLayerRenderer.quoteVerticalShadowIds, quoteText)
                    views.setTextViewText(R.id.widgetAuthorVertical, authorVerticalDisplay)
                    WidgetShadowLayerRenderer.bindText(
                        views,
                        WidgetShadowLayerRenderer.authorVerticalShadowIds,
                        authorVerticalDisplay
                    )

                    views.setViewVisibility(R.id.widgetAuthor, if (authorText.isBlank()) View.GONE else View.VISIBLE)
                    WidgetShadowLayerRenderer.setVisibility(views, WidgetShadowLayerRenderer.authorShadowIds, authorText.isNotBlank())
                    views.setViewVisibility(R.id.widgetAuthorVertical, if (authorText.isBlank()) View.GONE else View.VISIBLE)
                    WidgetShadowLayerRenderer.setVisibility(
                        views,
                        WidgetShadowLayerRenderer.authorVerticalShadowIds,
                        authorText.isNotBlank()
                    )

                    val quoteColor = StyleParsers.parseRgbaOrNull(style.textRgba) ?: 0xFFFFFFFF.toInt()
                    val authorColor = StyleParsers.parseRgbaOrNull(style.authorRgba) ?: 0xFFDDDDDD.toInt()
                    views.setTextColor(R.id.widgetQuote, quoteColor)
                    views.setTextColor(R.id.widgetQuoteVertical, quoteColor)
                    views.setTextColor(R.id.widgetAuthor, authorColor)
                    views.setTextColor(R.id.widgetAuthorVertical, authorColor)

                    val quoteTextHidden = (quoteColor ushr 24) == 0
                    val hideShadowLayers = quoteTextHidden || style.shadowPreset == com.example.onequote.data.model.ShadowPreset.NONE

                    views.setTextViewTextSize(
                        R.id.widgetQuote,
                        TypedValue.COMPLEX_UNIT_SP,
                        layoutPlan.quoteSp
                    )
                    WidgetShadowLayerRenderer.bindTextSize(views, WidgetShadowLayerRenderer.quoteShadowIds, layoutPlan.quoteSp)
                    views.setTextViewTextSize(
                        R.id.widgetQuoteVertical,
                        TypedValue.COMPLEX_UNIT_SP,
                        layoutPlan.quoteSp
                    )
                    WidgetShadowLayerRenderer.bindTextSize(
                        views,
                        WidgetShadowLayerRenderer.quoteVerticalShadowIds,
                        layoutPlan.quoteSp
                    )
                    views.setTextViewTextSize(R.id.widgetAuthor, TypedValue.COMPLEX_UNIT_SP, layoutPlan.authorSp)
                    WidgetShadowLayerRenderer.bindTextSize(
                        views,
                        WidgetShadowLayerRenderer.authorShadowIds,
                        layoutPlan.authorSp
                    )
                    views.setTextViewTextSize(R.id.widgetAuthorVertical, TypedValue.COMPLEX_UNIT_SP, layoutPlan.authorSp)
                    WidgetShadowLayerRenderer.bindTextSize(
                        views,
                        WidgetShadowLayerRenderer.authorVerticalShadowIds,
                        layoutPlan.authorSp
                    )

                    views.setInt(R.id.widgetQuote, "setMaxLines", layoutPlan.quoteMaxLines)
                    views.setInt(R.id.widgetQuoteVertical, "setMaxLines", layoutPlan.quoteMaxLines)
                    views.setInt(R.id.widgetAuthor, "setMaxLines", layoutPlan.authorMaxLines)
                    views.setInt(R.id.widgetAuthorVertical, "setMaxLines", layoutPlan.authorMaxLines)
                    WidgetShadowLayerRenderer.bindMaxLines(views, WidgetShadowLayerRenderer.quoteShadowIds, layoutPlan.quoteMaxLines)
                    WidgetShadowLayerRenderer.bindMaxLines(
                        views,
                        WidgetShadowLayerRenderer.quoteVerticalShadowIds,
                        layoutPlan.quoteMaxLines
                    )
                    WidgetShadowLayerRenderer.bindMaxLines(views, WidgetShadowLayerRenderer.authorShadowIds, layoutPlan.authorMaxLines)
                    WidgetShadowLayerRenderer.bindMaxLines(
                        views,
                        WidgetShadowLayerRenderer.authorVerticalShadowIds,
                        layoutPlan.authorMaxLines
                    )

                    WidgetShadowLayerRenderer.apply(
                        context = context,
                        views = views,
                        preset = style.shadowPreset,
                        hideAll = hideShadowLayers,
                        quoteVisible = true,
                        authorVisible = authorText.isNotBlank()
                    )

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

                    runCatching {
                        manager.updateAppWidget(id, views)
                    }.onSuccess {
                        AppDebugLogger.log(TAG, "update_widget_success id=$id")
                    }.onFailure {
                        AppDebugLogger.log(TAG, "update_widget_failed id=$id error=${it.message}")
                    }
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
            AppDebugLogger.log(
                TAG,
                "tap_received widgetId=$widgetId delta=$deltaFromLastTap window=$DOUBLE_TAP_WINDOW_MS single=${settings.singleClickAction} double=${settings.doubleClickAction}"
            )

            val isDoubleTap = (deltaFromLastTap in 1..DOUBLE_TAP_WINDOW_MS) && prevWidgetId == widgetId
            if (isDoubleTap) {
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
                AppDebugLogger.log(TAG, "tap_not_double widgetId=$widgetId delta=$deltaFromLastTap lateBy=$lateBy")
            }

            val tapToken = Random.nextInt(100_000, 999_999)
            synchronized(tapLock) {
                pendingSingleJob?.cancel()
                lastTapEventTimeMs = now
                lastTapWidgetId = widgetId
                lastTapToken = tapToken
            }
            AppDebugLogger.log(TAG, "tap_classified=PENDING_SINGLE widgetId=$widgetId token=$tapToken")

            val singleJob = tapScope.launch {
                // 非阻塞式单击提交：不会占用当前广播处理链，减少双击竞争。
                delay(DOUBLE_TAP_WINDOW_MS + SINGLE_COMMIT_GRACE_MS)
                val (latestToken, latestWidget) = synchronized(tapLock) {
                    Pair(lastTapToken, lastTapWidgetId)
                }
                val shouldRunSingle = latestToken == tapToken && latestWidget == widgetId
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
                AppDebugLogger.log(TAG, "run_widget_action_blocked reason=action_mutex action=$action last=$last delta=$delta")
                return false
            }

            // 2) 刷新专用节流：避免短时重复刷新造成多入口叠加与API压力。
            if (action == WidgetClickAction.REFRESH && shouldBlockRefreshDispatch(nowElapsed)) {
                val lastRefreshAt = synchronized(actionLock) { lastRefreshDispatchAtElapsedMs }
                val delta = (nowElapsed - lastRefreshAt).coerceAtLeast(0L)
                AppDebugLogger.log(TAG, "run_widget_action_blocked reason=refresh_throttle action=$action delta=$delta")
                return false
            }

            markActionDispatched(action, nowElapsed)
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
         * 将设置中的文本对齐映射到 TextView gravity，仅用于横排正文。
         */
        private fun alignToGravity(mode: TextAlignMode): Int {
            val horizontal = when (mode) {
                TextAlignMode.LEFT -> Gravity.START
                TextAlignMode.CENTER -> Gravity.CENTER_HORIZONTAL
                TextAlignMode.RIGHT -> Gravity.END
            }
            return horizontal or Gravity.CENTER_VERTICAL
        }

        /**
         * 文本一次性自适应计划：
         * - 根据组件可用尺寸和文本长度缩放字号；
         * - 同步给主文本和阴影层，避免错位。
         */
        private fun resolveTextLayoutPlan(
            manager: AppWidgetManager,
            widgetId: Int,
            isVertical: Boolean,
            quoteText: String,
            hasAuthor: Boolean,
            baseQuoteSp: Float,
            baseAuthorSp: Float
        ): WidgetTextLayoutPlan {
            val options = manager.getAppWidgetOptions(widgetId)
            val widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).coerceAtLeast(180)
            val heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0).coerceAtLeast(110)

            val quoteLength = quoteText.length
            val compactByLength = when {
                quoteLength >= 120 -> 4f
                quoteLength >= 90 -> 3f
                quoteLength >= 65 -> 2f
                quoteLength >= 45 -> 1f
                else -> 0f
            }

            val compactByArea = when {
                widthDp <= 220 || heightDp <= 115 -> 2f
                widthDp <= 260 || heightDp <= 130 -> 1f
                else -> 0f
            }

            val verticalPenalty = if (isVertical) 1f else 0f
            val quoteSp = (baseQuoteSp - compactByLength - compactByArea - verticalPenalty).coerceIn(12f, 25f)
            val authorSp = (baseAuthorSp - compactByArea).coerceIn(12f, 20f)

            val quoteMaxLines = when {
                isVertical -> 24
                heightDp <= 115 -> 4
                heightDp <= 145 -> 5
                else -> 6
            }
            val authorMaxLines = if (hasAuthor) 2 else 1

            return WidgetTextLayoutPlan(
                quoteSp = quoteSp,
                authorSp = authorSp,
                quoteMaxLines = quoteMaxLines,
                authorMaxLines = authorMaxLines
            )
        }

        /**
         * 小组件背景映射：
         * 1) 优先使用圆角位图背景，兼容动态颜色与动态圆角
         * 2) 若位图失败，降级为 root 纯色背景，确保小组件可见性
         */
        private fun applyBackgroundStyle(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int,
            views: RemoteViews,
            backgroundRgba: String,
            cornerLevel: Int
        ) {
            val colorInt = StyleParsers.parseRgbaOrNull(backgroundRgba) ?: return
            val options = manager.getAppWidgetOptions(widgetId)
            val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)

            val density = context.resources.displayMetrics.density
            val widthPx = (minWidthDp * density).toInt().coerceAtLeast((220 * density).toInt())
            val heightPx = (minHeightDp * density).toInt().coerceAtLeast((110 * density).toInt())
            val radiusPx = StyleParsers.levelToCornerDp(cornerLevel) * density

            runCatching {
                val bitmap = buildRoundedRectBitmap(widthPx, heightPx, colorInt, radiusPx)
                views.setImageViewBitmap(R.id.widgetBackground, bitmap)
                views.setViewVisibility(R.id.widgetBackground, View.VISIBLE)
                views.setInt(R.id.widgetRoot, "setBackgroundColor", 0x00000000)
            }.onFailure {
                AppDebugLogger.log(TAG, "apply_background_bitmap_failed id=$widgetId error=${it.message}")
                views.setViewVisibility(R.id.widgetBackground, View.GONE)
                views.setInt(R.id.widgetRoot, "setBackgroundColor", colorInt)
            }
        }

        /** 构建圆角纯色位图，供 RemoteViews 背景渲染。 */
        private fun buildRoundedRectBitmap(
            widthPx: Int,
            heightPx: Int,
            colorInt: Int,
            radiusPx: Float
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = colorInt
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
                radiusPx,
                radiusPx,
                paint
            )
            return bitmap
        }

        private data class WidgetTextLayoutPlan(
            val quoteSp: Float,
            val authorSp: Float,
            val quoteMaxLines: Int,
            val authorMaxLines: Int
        )

    }
}
