package com.example.onequote

import com.example.onequote.data.util.StyleParsers
import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun font_percent_mapping_behave_as_expected() {
        assertEquals(9f, StyleParsers.percentToBaseTextSp(0), 0.001f)
        assertEquals(18f, StyleParsers.percentToBaseTextSp(100), 0.001f)
        assertEquals(36f, StyleParsers.percentToBaseTextSp(200), 0.001f)

        assertEquals(0f, StyleParsers.levelToCornerDp(-3), 0.001f)
        assertEquals(15f, StyleParsers.levelToCornerDp(5), 0.001f)
        assertEquals(30f, StyleParsers.levelToCornerDp(99), 0.001f)
    }

    @Test
    fun vertical_text_and_cooldown_behave_as_expected() {
        assertEquals("一\n言", StyleParsers.asVerticalText("一言"))

        assertEquals(5, StyleParsers.cooldownRemainSeconds(lastAt = 1000L, now = 1000L, cooldownMs = 5000L))
        assertEquals(4, StyleParsers.cooldownRemainSeconds(lastAt = 1000L, now = 2500L, cooldownMs = 5000L))
        assertEquals(0, StyleParsers.cooldownRemainSeconds(lastAt = 1000L, now = 8000L, cooldownMs = 5000L))
    }
}
