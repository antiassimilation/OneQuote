package com.example.onequote.data.util

import android.graphics.Color
import kotlin.math.pow
import kotlin.math.roundToInt

object StyleParsers {
    private val rgbaRegex = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")

    fun parseRgbaOrNull(raw: String): Int? {
        val text = raw.trim()
        if (!rgbaRegex.matches(text)) return null
        val parts = text.split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        if (values.any { it !in 0..255 }) return null
        return Color.argb(values[3], values[0], values[1], values[2])
    }

    /**
     * 百分比语义：100% = 18sp；0% 表示缩小一倍（9sp）；200% 表示放大一倍（36sp）。
     */
    fun percentToBaseTextSp(percent: Int): Float {
        val safe = percent.coerceIn(0, 200)
        // 使用指数映射确保三个锚点严格成立：0%->0.5x，100%->1x，200%->2x。
        val scale = 2.0.pow((safe - 100) / 100.0).toFloat()
        return 18f * scale
    }

    /**
     * 根据组件尺寸与文本长度计算展示字号，仅用于显示，不回写配置。
     */
    fun adaptiveWidgetQuoteSp(
        baseSp: Float,
        spanX: Int,
        spanY: Int,
        quoteLength: Int
    ): Float {
        val areaScale = ((spanX.coerceAtLeast(1) * spanY.coerceAtLeast(1)) / 6f)
            .coerceIn(0.75f, 1.45f)
        val lengthScale = when {
            quoteLength > 48 -> 0.72f
            quoteLength > 36 -> 0.82f
            quoteLength > 24 -> 0.92f
            else -> 1f
        }
        return (baseSp * areaScale * lengthScale).coerceIn(10f, 42f)
    }

    fun levelToShadow(level: Int): Triple<Float, Float, Float> {
        val safe = level.coerceIn(0, 10)
        val radius = (safe * 0.6f)
        val offset = (safe * 0.4f)
        return Triple(radius, offset, offset)
    }

    fun levelToCornerDp(level: Int): Float {
        return (level.coerceIn(0, 10) * 3f)
    }

    fun asVerticalText(content: String): String =
        content.toCharArray().joinToString("\n")

    fun cooldownRemainSeconds(lastAt: Long, now: Long, cooldownMs: Long = 5_000L): Int {
        val remain = (cooldownMs - (now - lastAt)).coerceAtLeast(0L)
        return (remain / 1000.0).roundToInt()
    }
}

