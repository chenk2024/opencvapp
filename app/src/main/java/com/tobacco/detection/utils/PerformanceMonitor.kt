package com.tobacco.detection.utils

import android.util.Log

/**
 * 性能监测工具类
 * 用于监测检测性能，确保满足要求
 */
class PerformanceMonitor {

    companion object {
        private const val TAG = "PerformanceMonitor"
        
        // 性能要求
        const val MAX_DETECTION_TIME_MS = 15 * 60 * 1000L // 15分钟
        const val TARGET_DETECTION_TIME_MS = 5000L // 目标5秒内完成
    }

    private var startTime: Long = 0
    private var endTime: Long = 0
    
    // 统计信息
    private var totalDetections: Int = 0
    private var successfulDetections: Int = 0
    private var failedDetections: Int = 0
    private var totalTimeMs: Long = 0
    private var minTimeMs: Long = Long.MAX_VALUE
    private var maxTimeMs: Long = 0
    
    // 连续运行监测
    private var sessionStartTime: Long = 0
    private var isSessionActive: Boolean = false

    /**
     * 开始检测计时
     */
    fun startDetection() {
        startTime = System.currentTimeMillis()
    }

    /**
     * 结束检测计时
     */
    fun endDetection(success: Boolean) {
        endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        totalDetections++
        if (success) {
            successfulDetections++
        } else {
            failedDetections++
        }
        
        totalTimeMs += duration
        minTimeMs = minOf(minTimeMs, duration)
        maxTimeMs = maxOf(maxTimeMs, duration)
        
        // 检查是否满足性能要求
        if (duration > MAX_DETECTION_TIME_MS) {
            Log.w(TAG, "Detection time exceeds maximum: ${duration}ms > ${MAX_DETECTION_TIME_MS}ms")
        }
    }

    /**
     * 开始连续运行监测
     */
    fun startSession() {
        sessionStartTime = System.currentTimeMillis()
        isSessionActive = true
        Log.i(TAG, "Performance monitoring session started")
    }

    /**
     * 结束连续运行监测
     */
    fun endSession() {
        if (isSessionActive) {
            val sessionDuration = System.currentTimeMillis() - sessionStartTime
            val hours = sessionDuration / (1000 * 60 * 60)
            Log.i(TAG, "Performance monitoring session ended. Duration: ${hours} hours")
            isSessionActive = false
        }
    }

    /**
     * 获取当前检测耗时（毫秒）
     */
    fun getCurrentDetectionTime(): Long {
        return if (startTime > 0 && endTime == 0L) {
            System.currentTimeMillis() - startTime
        } else {
            endTime - startTime
        }
    }

    /**
     * 是否正在检测
     */
    fun isDetecting(): Boolean {
        return startTime > 0 && endTime == 0L
    }

    /**
     * 获取平均检测时间（毫秒）
     */
    fun getAverageDetectionTime(): Long {
        return if (totalDetections > 0) {
            totalTimeMs / totalDetections
        } else {
            0
        }
    }

    /**
     * 获取检测成功率
     */
    fun getSuccessRate(): Double {
        return if (totalDetections > 0) {
            successfulDetections.toDouble() / totalDetections * 100
        } else {
            0.0
        }
    }

    /**
     * 是否满足性能要求
     */
    fun meetsPerformanceRequirements(): Boolean {
        val avgTime = getAverageDetectionTime()
        val successRate = getSuccessRate()
        
        return avgTime <= TARGET_DETECTION_TIME_MS && successRate >= 100.0
    }

    /**
     * 获取性能报告
     */
    fun getPerformanceReport(): String {
        return buildString {
            appendLine("=== 性能监测报告 ===")
            appendLine("总检测次数: $totalDetections")
            appendLine("成功次数: $successfulDetections")
            appendLine("失败次数: $failedDetections")
            appendLine("成功率: ${String.format("%.2f", getSuccessRate())}%")
            appendLine("平均检测时间: ${getAverageDetectionTime()}ms")
            appendLine("最短检测时间: ${if (minTimeMs == Long.MAX_VALUE) 0 else minTimeMs}ms")
            appendLine("最长检测时间: ${if (maxTimeMs == 0L) 0 else maxTimeMs}ms")
            appendLine("满足性能要求: ${if (meetsPerformanceRequirements()) "是" else "否"}")
            
            if (isSessionActive) {
                val sessionDuration = System.currentTimeMillis() - sessionStartTime
                val hours = sessionDuration / (1000 * 60 * 60)
                val minutes = (sessionDuration % (1000 * 60 * 60)) / (1000 * 60)
                appendLine("连续运行时间: ${hours}小时${minutes}分钟")
            }
        }
    }

    /**
     * 重置统计信息
     */
    fun reset() {
        totalDetections = 0
        successfulDetections = 0
        failedDetections = 0
        totalTimeMs = 0
        minTimeMs = Long.MAX_VALUE
        maxTimeMs = 0
        startTime = 0
        endTime = 0
    }

    /**
     * 检查精度是否满足要求（≤ 0.01mm）
     * @param stdDeviation 标准差
     * @return 是否满足精度要求
     */
    fun checkPrecisionRequirement(stdDeviation: Double): Boolean {
        return stdDeviation <= 0.01
    }
}
