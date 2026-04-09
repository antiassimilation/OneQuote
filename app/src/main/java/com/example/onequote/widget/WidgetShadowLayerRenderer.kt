package com.example.onequote.widget

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.example.onequote.R
import com.example.onequote.data.model.ShadowPreset

/**
 * 小组件阴影层渲染器：
 * - 统一管理多层阴影 TextView 的映射与绘制参数；
 * - 统一处理层级偏移、透明度递减与可见性。
 */
object WidgetShadowLayerRenderer {

    val quoteShadowIds: IntArray = intArrayOf(
        R.id.widgetQuoteShadow1,
        R.id.widgetQuoteShadow2,
        R.id.widgetQuoteShadow3,
        R.id.widgetQuoteShadow4,
        R.id.widgetQuoteShadow5,
        R.id.widgetQuoteShadow6
    )

    val quoteVerticalShadowIds: IntArray = intArrayOf(
        R.id.widgetQuoteVerticalShadow1,
        R.id.widgetQuoteVerticalShadow2,
        R.id.widgetQuoteVerticalShadow3,
        R.id.widgetQuoteVerticalShadow4,
        R.id.widgetQuoteVerticalShadow5,
        R.id.widgetQuoteVerticalShadow6
    )

    val authorShadowIds: IntArray = intArrayOf(
        R.id.widgetAuthorShadow1,
        R.id.widgetAuthorShadow2,
        R.id.widgetAuthorShadow3,
        R.id.widgetAuthorShadow4,
        R.id.widgetAuthorShadow5,
        R.id.widgetAuthorShadow6
    )

    val authorVerticalShadowIds: IntArray = intArrayOf(
        R.id.widgetAuthorVerticalShadow1,
        R.id.widgetAuthorVerticalShadow2,
        R.id.widgetAuthorVerticalShadow3,
        R.id.widgetAuthorVerticalShadow4,
        R.id.widgetAuthorVerticalShadow5,
        R.id.widgetAuthorVerticalShadow6
    )

    /**
     * 6层阴影位移（dp）：0.5/1/1.5/2/2.5/3。
     * 使用 XY 双向同幅偏移，形成更连续的柔和扩散。
     */
    private val layerOffsetsDp = floatArrayOf(0.5f, 1f, 1.5f, 2f, 2.5f, 3f)

    fun bindText(views: RemoteViews, ids: IntArray, text: String) {
        ids.forEach { views.setTextViewText(it, text) }
    }

    fun bindTextSize(views: RemoteViews, ids: IntArray, sizeSp: Float) {
        ids.forEach { views.setTextViewTextSize(it, TypedValue.COMPLEX_UNIT_SP, sizeSp) }
    }

    fun bindMaxLines(views: RemoteViews, ids: IntArray, maxLines: Int) {
        ids.forEach { views.setInt(it, "setMaxLines", maxLines) }
    }

    fun setVisibility(views: RemoteViews, ids: IntArray, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        ids.forEach { views.setViewVisibility(it, target) }
    }

    /** 应用阴影层样式；当正文完全透明时强制隐藏所有阴影层。 */
    fun apply(
        context: Context,
        views: RemoteViews,
        preset: ShadowPreset,
        hideAll: Boolean,
        quoteVisible: Boolean,
        authorVisible: Boolean
    ) {
        val profile = profileForPreset(preset)
        applyToGroup(context, views, quoteShadowIds, profile, hideAll || !quoteVisible)
        applyToGroup(context, views, quoteVerticalShadowIds, profile, hideAll || !quoteVisible)
        applyToGroup(context, views, authorShadowIds, profile, hideAll || !authorVisible)
        applyToGroup(context, views, authorVerticalShadowIds, profile, hideAll || !authorVisible)
    }

    private fun applyToGroup(
        context: Context,
        views: RemoteViews,
        ids: IntArray,
        profile: LayerProfile,
        hideAll: Boolean
    ) {
        ids.forEachIndexed { index, id ->
            val showLayer = !hideAll && index < profile.enabledLayers
            views.setViewVisibility(id, if (showLayer) View.VISIBLE else View.GONE)
            if (!showLayer) return@forEachIndexed

            val alphaInt = (profile.alphaDescending[index] * 255f).toInt().coerceIn(0, 255)
            views.setTextColor(id, Color.argb(alphaInt, 0, 0, 0))
            val offsetPx = dpToPx(context, layerOffsetsDp[index])

            // 关键修复：使用 translation 做位移，避免 padding 改变可用排版宽度，
            // 导致英文长文本在阴影层与主文本之间产生不同断行/截断路径。
            views.setFloat(id, "setTranslationX", offsetPx)
            views.setFloat(id, "setTranslationY", offsetPx)
        }
    }

    private fun profileForPreset(preset: ShadowPreset): LayerProfile {
        return when (preset) {
            ShadowPreset.NONE -> LayerProfile(
                enabledLayers = 0,
                alphaDescending = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
            )

            ShadowPreset.NORMAL -> LayerProfile(
                enabledLayers = 3,
                alphaDescending = floatArrayOf(0.48f, 0.40f, 0.32f, 0f, 0f, 0f)
            )

            ShadowPreset.BOLD -> LayerProfile(
                enabledLayers = 4,
                alphaDescending = floatArrayOf(0.62f, 0.54f, 0.46f, 0.38f, 0f, 0f)
            )

            ShadowPreset.BOLD_LIGHT -> LayerProfile(
                enabledLayers = 5,
                alphaDescending = floatArrayOf(0.78f, 0.69f, 0.60f, 0.51f, 0.42f, 0f)
            )
        }
    }

    private fun dpToPx(context: Context, dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }

    private data class LayerProfile(
        val enabledLayers: Int,
        val alphaDescending: FloatArray
    )
}
