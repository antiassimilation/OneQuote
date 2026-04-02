package com.example.onequote.data.util

import android.graphics.Color
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

    fun levelToTextSp(level: Int): Float {
        val safe = level.coerceIn(0, 10)
        return (12 + safe * 2).toFloat()
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

