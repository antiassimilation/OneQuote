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

    fun clampQuoteFontSp(input: Int): Int = input.coerceIn(12, 25)

    fun clampAuthorFontSp(input: Int): Int = input.coerceIn(12, 20)

    /**
     * 阴影预设参数。
     * 供 Compose 预览卡片与小组件保持一致的视觉梯度。
     */
    fun shadowSpec(preset: ShadowPreset): ShadowSpec {
        return when (preset) {
            ShadowPreset.NONE -> ShadowSpec(radius = 0f, dx = 0f, dy = 0f, alpha = 0f)
            ShadowPreset.NORMAL -> ShadowSpec(radius = 2.4f, dx = 1.1f, dy = 1.1f, alpha = 0.56f)
            ShadowPreset.BOLD -> ShadowSpec(radius = 3.1f, dx = 1.4f, dy = 1.4f, alpha = 0.74f)
            ShadowPreset.BOLD_LIGHT -> ShadowSpec(radius = 3.8f, dx = 1.8f, dy = 1.8f, alpha = 0.88f)
        }
    }

    fun levelToCornerDp(level: Int): Float {
        return (level.coerceIn(0, 10) * 3f)
    }

    /** 将普通文本按字符拆为竖排渲染文本。 */
    fun asVerticalText(content: String): String =
        content.toCharArray().joinToString("\n")

    /** 计算手动刷新剩余冷却秒数，结果下限为 0。 */
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
