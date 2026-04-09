package com.example.onequote.data.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量级应用内日志：
 * 1) 记录关键行为链路，便于用户导出日志定位问题
 * 2) 控制日志体积，避免无限增长导致存储开销
 */
object AppDebugLogger {
    private const val MAX_LINES = 800
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val inMemory = ArrayDeque<String>()

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${formatter.format(Date())} [$tag] $message"
        if (inMemory.size >= MAX_LINES) {
            inMemory.removeFirst()
        }
        inMemory.addLast(line)
    }

    @Synchronized
    fun dump(): String {
        if (inMemory.isEmpty()) {
            return "日志为空"
        }
        return inMemory.joinToString(separator = "\n")
    }

    @Synchronized
    fun clear() {
        inMemory.clear()
    }
}

