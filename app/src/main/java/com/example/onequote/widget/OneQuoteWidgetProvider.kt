package com.example.onequote.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.widget.RemoteViews
import android.widget.Toast
import com.example.onequote.OneQuoteApp
import com.example.onequote.R
import com.example.onequote.data.model.LayoutMode
import com.example.onequote.data.util.StyleParsers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
        if (intent.action != ACTION_MANUAL_REFRESH) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val repo = (context.applicationContext as OneQuoteApp).repository
            val now = System.currentTimeMillis()
            val settings = repo.getSettings()
            val remain = StyleParsers.cooldownRemainSeconds(settings.lastManualRefreshAtMillis, now)
            if (remain > 0) {
                Toast.makeText(context, "请在${remain}秒后重试", Toast.LENGTH_SHORT).show()
                pendingResult.finish()
                return@launch
            }

            repo.markManualRefreshAt(now)
            val result = repo.refreshFromEnabledSources()
            if (result.isFailure) {
                Toast.makeText(context, "刷新失败，已使用上次内容", Toast.LENGTH_SHORT).show()
            }
            refreshAll(context)
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_MANUAL_REFRESH = "com.example.onequote.action.MANUAL_REFRESH"

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

                    views.setTextViewText(R.id.widgetQuote, quoteText)
                    views.setTextViewText(R.id.widgetAuthor, authorText)

                    StyleParsers.parseRgbaOrNull(style.textRgba)?.let {
                        views.setTextColor(R.id.widgetQuote, it)
                    }
                    StyleParsers.parseRgbaOrNull(style.authorRgba)?.let {
                        views.setTextColor(R.id.widgetAuthor, it)
                    }

                    views.setTextViewTextSize(
                        R.id.widgetQuote,
                        TypedValue.COMPLEX_UNIT_SP,
                        StyleParsers.levelToTextSp(style.fontSizeLevel)
                    )

                    val bgColor = StyleParsers.parseRgbaOrNull(style.backgroundRgba)
                    if (bgColor != null) {
                        val corner = StyleParsers.levelToCornerDp(style.cornerRadiusLevel)
                        views.setImageViewBitmap(R.id.widgetBackground, roundedBackground(bgColor, corner))
                    }

                    val clickIntent = Intent(context, OneQuoteWidgetProvider::class.java).apply {
                        action = ACTION_MANUAL_REFRESH
                    }
                    val clickPendingIntent = PendingIntent.getBroadcast(
                        context,
                        1001,
                        clickIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(R.id.widgetRoot, clickPendingIntent)
                    views.setOnClickPendingIntent(R.id.widgetRefresh, clickPendingIntent)

                    manager.updateAppWidget(id, views)
                }
            }
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

