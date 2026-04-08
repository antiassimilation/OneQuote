package com.example.onequote.data.util

import android.graphics.Color
import com.example.onequote.data.model.ShadowPreset
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

    fun clampQuoteFontSp(input: Int): Int = input.coerceIn(6, 20)

    fun clampAuthorFontSp(input: Int): Int = input.coerceIn(3, 10)

    /**
     * 将百分比映射到基础字号（sp）。
     * 约定：每 +100% 字号翻倍。
     * - 0% -> 9sp
     * - 100% -> 18sp
     * - 200% -> 36sp
     */
    fun percentToBaseTextSp(percent: Int): Float {
        val scaleSteps = percent / 100f
        return 9f * Math.pow(2.0, scaleSteps.toDouble()).toFloat()
    }

    /**
     * 阴影预设采样：
     * None < Normal < Bold < Bold-Light（最深）
     */
    fun shadowSpec(preset: ShadowPreset): ShadowSpec {
        return when (preset) {
            ShadowPreset.NONE -> ShadowSpec(radius = 0f, dx = 0f, dy = 0f, alpha = 0f)
            ShadowPreset.NORMAL -> ShadowSpec(radius = 1.6f, dx = 0.8f, dy = 0.8f, alpha = 0.38f)
            ShadowPreset.BOLD -> ShadowSpec(radius = 2.4f, dx = 1.1f, dy = 1.1f, alpha = 0.56f)
            ShadowPreset.BOLD_LIGHT -> ShadowSpec(radius = 3.1f, dx = 1.4f, dy = 1.4f, alpha = 0.74f)
        }
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

data class ShadowSpec(
    val radius: Float,
    val dx: Float,
    val dy: Float,
    val alpha: Float
)

