package com.tobacco.detection.data

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * 检测结果数据模型
 */
data class DetectionResult(
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),
    
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @SerializedName("tobacco_count")
    val tobaccoCount: Int = 0,
    
    @SerializedName("average_width_mm")
    val averageWidthMm: Double = 0.0,
    
    @SerializedName("min_width_mm")
    val minWidthMm: Double = 0.0,
    
    @SerializedName("max_width_mm")
    val maxWidthMm: Double = 0.0,
    
    @SerializedName("std_deviation")
    val stdDeviation: Double = 0.0,
    
    @SerializedName("detection_time_ms")
    val detectionTimeMs: Long = 0,
    
    @SerializedName("individual_widths")
    val individualWidths: List<Double> = emptyList(),
    
    @SerializedName("image_path")
    val imagePath: String? = null,
    
    @SerializedName("uploaded")
    val uploaded: Boolean = false,
    
    @SerializedName("status")
    val status: DetectionStatus = DetectionStatus.SUCCESS
)

/**
 * 检测状态枚举
 */
enum class DetectionStatus {
    @SerializedName("success")
    SUCCESS,
    
    @SerializedName("failed")
    FAILED,
    
    @SerializedName("processing")
    PROCESSING,
    
    @SerializedName("cancelled")
    CANCELLED
}

/**
 * 单根烟丝的检测数据
 */
data class TobaccoInfo(
    val index: Int,
    val widthPixels: Double,
    val widthMm: Double,
    val contourPoints: List<PointData> = emptyList(),
    val centerLine: List<PointData> = emptyList()
)

/**
 * 坐标点数据
 */
data class PointData(
    val x: Double,
    val y: Double
)

/**
 * 图像处理配置
 */
data class ProcessingConfig(
    // 像素与毫米的转换比例（需要通过校准确定）
    var pixelToMmRatio: Double = 0.01, // 默认值，实际需要校准
    
    // 高斯模糊核大小（必须为奇数）
    val gaussianBlurSize: Int = 5,
    
    // 双边滤波参数
    val bilateralDiameter: Int = 9,
    val bilateralSigmaColor: Double = 75.0,
    val bilateralSigmaSpace: Double = 75.0,
    
    // 二值化参数
    val binaryThreshold: Int = 127,
    
    // 轮廓提取参数
    val minContourArea: Double = 100.0,
    val maxContourArea: Double = 50000.0,
    
    // Douglas-Peucker算法参数
    val douglasPeuckerEpsilon: Double = 2.0,
    
    // 形态学操作核大小
    val morphKernelSize: Int = 3
)

/**
 * API响应结果
 */
data class ApiResponse(
    @SerializedName("success")
    val success: Boolean,
    
    @SerializedName("message")
    val message: String,
    
    @SerializedName("data")
    val data: Any? = null
)

/**
 * 应用设置
 */
data class AppSettings(
    var apiUrl: String = "http://192.168.1.100:8080/api/detection",
    var autoCapture: Boolean = false,
    var captureDelaySeconds: Int = 3,
    var pixelToMmRatio: Double = 0.01,
    var expectedTobaccoCount: Int = 30,
    var saveImages: Boolean = true,
    var showOverlay: Boolean = true
)
