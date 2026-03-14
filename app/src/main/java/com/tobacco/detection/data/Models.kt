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
    
    // 宽度统计
    @SerializedName("average_width_mm")
    val averageWidthMm: Double = 0.0,
    
    @SerializedName("min_width_mm")
    val minWidthMm: Double = 0.0,
    
    @SerializedName("max_width_mm")
    val maxWidthMm: Double = 0.0,
    
    // 长度统计
    @SerializedName("average_length_mm")
    val averageLengthMm: Double = 0.0,
    
    @SerializedName("min_length_mm")
    val minLengthMm: Double = 0.0,
    
    @SerializedName("max_length_mm")
    val maxLengthMm: Double = 0.0,
    
    @SerializedName("std_deviation")
    val stdDeviation: Double = 0.0,
    
    @SerializedName("detection_time_ms")
    val detectionTimeMs: Long = 0,
    
    @SerializedName("individual_widths")
    val individualWidths: List<Double> = emptyList(),
    
    @SerializedName("individual_lengths")
    val individualLengths: List<Double> = emptyList(),
    
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
    val lengthPixels: Double = 0.0,      // 烟丝长度（像素）
    val lengthMm: Double = 0.0,            // 烟丝长度（毫米）
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
    val morphKernelSize: Int = 3,
    
    // 宽度采样点数（法线中位数）
    val widthSampleCount: Int = 8,
    
    // 骨架提取阈值
    val skeletonThreshold: Int = 10
)

/**
 * 基准测试数据集
 * 用于存放带有真值的测试图像和标注数据
 */
data class BenchmarkDataset(
    val id: String,
    val name: String,
    val imagePath: String,
    val groundTruths: List<GroundTruth>
)

/**
 * 单个烟丝的真值数据
 */
data class GroundTruth(
    val tobaccoIndex: Int,
    val widthMm: Double,
    val lengthMm: Double,
    val notes: String = ""
)

/**
 * 基准测试结果汇总
 */
data class BenchmarkSummary(
    val datasetName: String,
    val totalSamples: Int,
    val widthMAE: Double,         // 宽度平均绝对误差 (mm)
    val widthRMSE: Double,        // 宽度均方根误差 (mm)
    val width95Percentile: Double, // 宽度95分位误差 (mm)
    val lengthMAE: Double,        // 长度平均绝对误差 (mm)
    val lengthRMSE: Double,       // 长度均方根误差 (mm)
    val length95Percentile: Double, // 长度95分位误差 (mm)
    val successRate: Double,       // 成功率 (误差<=0.01mm的比例)
    val avgProcessingTimeMs: Double // 平均处理时间 (ms)
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
    var expectedTobaccoCount: Int = 10,  // 1-10根烟丝
    var saveImages: Boolean = true,
    var showOverlay: Boolean = true
)
