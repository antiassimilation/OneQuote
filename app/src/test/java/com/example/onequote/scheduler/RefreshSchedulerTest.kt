package com.example.onequote.scheduler

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshSchedulerTest {

    @Test
    fun `normalizeMinutes 限制在一到六十分钟之间`() {
        assertEquals(1, RefreshScheduler.normalizeMinutes(0))
        assertEquals(1, RefreshScheduler.normalizeMinutes(1))
        assertEquals(15, RefreshScheduler.normalizeMinutes(15))
        assertEquals(60, RefreshScheduler.normalizeMinutes(90))
    }

    @Test
    fun `usesOneTimeSchedule 在十五分钟以下返回 true`() {
        assertEquals(true, RefreshScheduler.usesOneTimeSchedule(1))
        assertEquals(true, RefreshScheduler.usesOneTimeSchedule(14))
        assertEquals(false, RefreshScheduler.usesOneTimeSchedule(15))
        assertEquals(false, RefreshScheduler.usesOneTimeSchedule(60))
    }
}
