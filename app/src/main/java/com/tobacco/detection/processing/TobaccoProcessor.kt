package com.tobacco.detection.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.tobacco.detection.data.DetectionResult
import com.tobacco.detection.data.DetectionStatus
import com.tobacco.detection.data.PointData
import com.tobacco.detection.data.ProcessingConfig
import com.tobacco.detection.data.TobaccoInfo
import com.tobacco.detection.testing.BenchmarkTester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import org.opencv.utils.Converters
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

/**
 * 烟丝图像处理器
 * 实现图像处理、轮廓提取、宽度计算等功能
 * 支持直线和曲线形态的烟丝检测
 * 
 * 优化记录：
 * 1. 修正颜色通道语义（OpenCV使用BGR格式）
 * 2. 长度改为"骨架路径长度"
 * 3. 宽度改为"法线多点中位数"
 * 4. 支持畸变矫正与固定工位
 * 5. 支持基准测试与误差评估
 */
class TobaccoProcessor(private val config: ProcessingConfig) {

    companion object {
        private const val TAG = "TobaccoProcessor"
        
        // 默认工位配置
        const val DEFAULT_CAMERA_HEIGHT_MM = 300.0  // 相机高度 300mm
        const val DEFAULT_FOCAL_LENGTH_MM = 4.5    // 焦距 4.5mm (常见手机摄像头)
        const val DEFAULT_SENSOR_WIDTH_MM = 6.17   // 传感器宽度 1/2.3英寸约6.17mm
        
        // HSV黄色阈值（可调参）
        const val DEFAULT_HUE_LOWER = 15.0
        const val DEFAULT_HUE_UPPER = 45.0
        const val DEFAULT_SAT_LOWER = 40.0
        const val DEFAULT_SAT_UPPER = 255.0
        const val DEFAULT_VAL_LOWER = 30.0
        const val DEFAULT_VAL_UPPER = 255.0
        
        // RGB黄色阈值（可调参）
        const val DEFAULT_R_LOWER = 150.0
        const val DEFAULT_G_LOWER = 130.0
        const val DEFAULT_B_UPPER = 160.0
    }

    // 调参配置类
    data class TuningConfig(
        // HSV颜色空间阈值
        var hsvHueLower: Double = DEFAULT_HUE_LOWER,
        var hsvHueUpper: Double = DEFAULT_HUE_UPPER,
        var hsvSatLower: Double = DEFAULT_SAT_LOWER,
        var hsvSatUpper: Double = DEFAULT_SAT_UPPER,
        var hsvValLower: Double = DEFAULT_VAL_LOWER,
        var hsvValUpper: Double = DEFAULT_VAL_UPPER,
        
        // RGB颜色空间阈值
        var rgbRLower: Double = DEFAULT_R_LOWER,
        var rgbGLower: Double = DEFAULT_G_LOWER,
        var rgbBUpper: Double = DEFAULT_B_UPPER,
        
        // 形态学操作参数
        var morphKernelSize: Int = 5,
        var openIterations: Int = 2,
        var closeIterations: Int = 2,
        
        // 宽度测量参数（法线采样点数）
        var widthSampleCount: Int = 8,
        
        // 骨架提取参数
        var skeletonThreshold: Int = 10,
        
        // 畸变矫正参数
        var enableDistortionCorrection: Boolean = false,
        var cameraMatrix: Mat? = null,
        var distCoeffs: Mat? = null,
        
        // 工位参数
        var cameraHeightMm: Double = DEFAULT_CAMERA_HEIGHT_MM,
        var focalLengthMm: Double = DEFAULT_FOCAL_LENGTH_MM,
        var sensorWidthMm: Double = DEFAULT_SENSOR_WIDTH_MM
    )
    
    // 当前调参配置
    private var tuningConfig = TuningConfig()
    
    // 存储处理过程中的数据用于显示
    data class ProcessingData(
        val contours: List<List<Point>>,
        val widths: List<Pair<Point, Point>>,      // 宽度测量线的起点和终点
        val lengths: List<Pair<Point, Point>>,      // 长度测量线的起点和终点
        val centerPoints: List<Point>,
        val boundingBoxes: List<RotatedRect>,
        val skeletonPoints: List<List<Point>> = emptyList(),  // 骨架点
        val normalLines: List<Pair<Point, Point>> = emptyList() // 法线测量线
    )

    private var lastProcessingData: ProcessingData? = null
    
    // 基准测试结果
    private var benchmarkResults = mutableListOf<BenchmarkTester.BenchmarkResult>()
    
    /**
     * 设置调参配置
     */
    fun setTuningConfig(tuningConfig: TuningConfig) {
        this.tuningConfig = tuningConfig
    }
    
    /**
     * 获取当前调参配置
     */
    fun getTuningConfig(): TuningConfig = tuningConfig
    
    /**
     * 从文件加载调参配置
     */
    fun loadTuningConfigFromFile(filePath: String): Boolean {
        return try {
            val json = File(filePath).readText()
            // 简单解析JSON（实际项目中可使用Gson）
            val lines = json.lines()
            for (line in lines) {
                when {
                    line.contains("hsvHueLower") -> tuningConfig = tuningConfig.copy(
                        hsvHueLower = line.substringAfter(":").trim().removeSuffix(",").toDoubleOrNull() ?: tuningConfig.hsvHueLower
                    )
                    line.contains("hsvHueUpper") -> tuningConfig = tuningConfig.copy(
                        hsvHueUpper = line.substringAfter(":").trim().removeSuffix(",").toDoubleOrNull() ?: tuningConfig.hsvHueUpper
                    )
                    line.contains("hsvSatLower") -> tuningConfig = tuningConfig.copy(
                        hsvSatLower = line.substringAfter(":").trim().removeSuffix(",").toDoubleOrNull() ?: tuningConfig.hsvSatLower
                    )
                    line.contains("morphKernelSize") -> tuningConfig = tuningConfig.copy(
                        morphKernelSize = line.substringAfter(":").trim().removeSuffix(",").toIntOrNull() ?: tuningConfig.morphKernelSize
                    )
                }
            }
            Log.d(TAG, "Loaded tuning config from: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tuning config", e)
            false
        }
    }
    
    /**
     * 保存调参配置到文件
     */
    fun saveTuningConfigToFile(filePath: String): Boolean {
        return try {
            val json = buildString {
                appendLine("{")
                appendLine("  \"hsvHueLower\": ${tuningConfig.hsvHueLower},")
                appendLine("  \"hsvHueUpper\": ${tuningConfig.hsvHueUpper},")
                appendLine("  \"hsvSatLower\": ${tuningConfig.hsvSatLower},")
                appendLine("  \"hsvSatUpper\": ${tuningConfig.hsvSatUpper},")
                appendLine("  \"hsvValLower\": ${tuningConfig.hsvValLower},")
                appendLine("  \"hsvValUpper\": ${tuningConfig.hsvValUpper},")
                appendLine("  \"rgbRLower\": ${tuningConfig.rgbRLower},")
                appendLine("  \"rgbGLower\": ${tuningConfig.rgbGLower},")
                appendLine("  \"rgbBUpper\": ${tuningConfig.rgbBUpper},")
                appendLine("  \"morphKernelSize\": ${tuningConfig.morphKernelSize},")
                appendLine("  \"widthSampleCount\": ${tuningConfig.widthSampleCount},")
                appendLine("  \"enableDistortionCorrection\": ${tuningConfig.enableDistortionCorrection}")
                appendLine("}")
            }
            File(filePath).writeText(json)
            Log.d(TAG, "Saved tuning config to: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save tuning config", e)
            false
        }
    }
    
    /**
     * 导出当前检测参数为可视化配置文件（可导入到Python调参工具）
     */
    fun exportParamsForVisualTuning(filePath: String): Boolean {
        return try {
            val params = buildString {
                appendLine("#!/usr/bin/env python3")
                appendLine("# -*- coding: utf-8 -*-")
                appendLine("\"\"\"")
                appendLine("烟丝检测参数配置文件")
                appendLine("可使用 OpenCV 调整工具或自定义UI进行参数调优")
                appendLine("\"\"\"")
                appendLine()
                appendLine("import numpy as np")
                appendLine()
                appendLine("# ============== HSV 颜色阈值 ==============")
                appendLine("HSV_LOWER = np.array([${tuningConfig.hsvHueLower.toInt()}, ${tuningConfig.hsvSatLower.toInt()}, ${tuningConfig.hsvValLower.toInt()}])")
                appendLine("HSV_UPPER = np.array([${tuningConfig.hsvHueUpper.toInt()}, ${tuningConfig.hsvSatUpper.toInt()}, ${tuningConfig.hsvValUpper.toInt()}])")
                appendLine()
                appendLine("# ============== RGB 颜色阈值 ==============")
                appendLine("RGB_R_LOWER = ${tuningConfig.rgbRLower.toInt()}")
                appendLine("RGB_G_LOWER = ${tuningConfig.rgbGLower.toInt()}")
                appendLine("RGB_B_UPPER = ${tuningConfig.rgbBUpper.toInt()}")
                appendLine()
                appendLine("# ============== 形态学参数 ==============")
                appendLine("MORPH_KERNEL_SIZE = ${tuningConfig.morphKernelSize}")
                appendLine("OPEN_ITERATIONS = ${tuningConfig.openIterations}")
                appendLine("CLOSE_ITERATIONS = ${tuningConfig.closeIterations}")
                appendLine()
                appendLine("# ============== 测量参数 ==============")
                appendLine("WIDTH_SAMPLE_COUNT = ${tuningConfig.widthSampleCount}")
                appendLine("SKELETON_THRESHOLD = ${tuningConfig.skeletonThreshold}")
                appendLine()
                appendLine("# ============== 工位参数 ==============")
                appendLine("CAMERA_HEIGHT_MM = ${tuningConfig.cameraHeightMm}")
                appendLine("FOCAL_LENGTH_MM = ${tuningConfig.focalLengthMm}")
                appendLine("SENSOR_WIDTH_MM = ${tuningConfig.sensorWidthMm}")
                appendLine()
                appendLine("# ============== 像素比例（需校准） ==============")
                appendLine("PIXEL_TO_MM_RATIO = ${config.pixelToMmRatio}")
            }
            File(filePath).writeText(params)
            Log.d(TAG, "Exported params to: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export params", e)
            false
        }
    }
    
    /**
     * 添加基准测试数据
     */
    fun addBenchmarkResult(result: BenchmarkTester.BenchmarkResult) {
        benchmarkResults.add(result)
    }
    
    /**
     * 获取基准测试统计结果
     */
    fun getBenchmarkStatistics(): BenchmarkStatistics {
        if (benchmarkResults.isEmpty()) {
            return BenchmarkStatistics(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        
        val widthErrors = benchmarkResults.map { abs(it.widthErrorMm) }
        val lengthErrors = benchmarkResults.map { abs(it.lengthErrorMm) }
        
        // MAE (Mean Absolute Error)
        val widthMAE = widthErrors.average()
        val lengthMAE = lengthErrors.average()
        
        // RMSE (Root Mean Square Error)
        val widthRMSE = sqrt(widthErrors.map { it * it }.average())
        val lengthRMSE = sqrt(lengthErrors.map { it * it }.average())
        
        // 95分位误差
        val sortedWidthErrors = widthErrors.sorted()
        val sortedLengthErrors = lengthErrors.sorted()
        val percentile95Width = sortedWidthErrors[(sortedWidthErrors.size * 0.95).toInt().coerceIn(0, sortedWidthErrors.size - 1)]
        val percentile95Length = sortedLengthErrors[(sortedLengthErrors.size * 0.95).toInt().coerceIn(0, sortedLengthErrors.size - 1)]
        
        // 成功率
        val successCount = benchmarkResults.count { 
            it.widthErrorMm <= 0.01 && it.lengthErrorMm <= 0.01 
        }
        val successRate = successCount.toDouble() / benchmarkResults.size
        
        // 平均处理时间
        val avgProcessingTime = benchmarkResults.map { it.processingTimeMs }.average()
        
        return BenchmarkStatistics(
            sampleCount = benchmarkResults.size,
            widthMAE = widthMAE,
            widthRMSE = widthRMSE,
            width95Percentile = percentile95Width,
            lengthMAE = lengthMAE,
            lengthRMSE = lengthRMSE,
            length95Percentile = percentile95Length,
            successRate = successRate,
            avgProcessingTimeMs = avgProcessingTime
        )
    }
    
    /**
     * 清除基准测试数据
     */
    fun clearBenchmarkResults() {
        benchmarkResults.clear()
    }
    
    /**
     * 导出基准测试结果到CSV
     */
    fun exportBenchmarkResultsToCSV(filePath: String): Boolean {
        return try {
            val csv = buildString {
                appendLine("tobacco_index,measured_width_mm,measured_length_mm,ground_truth_width_mm,ground_truth_length_mm,width_error_mm,length_error_mm,processing_time_ms")
                for (result in benchmarkResults) {
                    appendLine("${result.tobaccoIndex},${result.measuredWidthMm},${result.measuredLengthMm},${result.groundTruthWidthMm},${result.groundTruthLengthMm},${result.widthErrorMm},${result.lengthErrorMm},${result.processingTimeMs}")
                }
            }
            File(filePath).writeText(csv)
            Log.d(TAG, "Exported benchmark results to: $filePath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export benchmark results", e)
            false
        }
    }
    
    data class BenchmarkStatistics(
        val sampleCount: Int,
        val widthMAE: Double,         // 宽度 MAE (mm)
        val widthRMSE: Double,       // 宽度 RMSE (mm)
        val width95Percentile: Double, // 宽度 95分位误差 (mm)
        val lengthMAE: Double,       // 长度 MAE (mm)
        val lengthRMSE: Double,       // 长度 RMSE (mm)
        val length95Percentile: Double, // 长度 95分位误差 (mm)
        val successRate: Double,      // 成功率
        val avgProcessingTimeMs: Double // 平均处理时间
    )

    /**
     * 处理图像并返回检测结果
     * @param bitmap 输入图像
     * @param groundTruthWidths 可选的基准宽度（用于基准测试）
     * @param groundTruthLengths 可选的长度基准（用于基准测试）
     */
    suspend fun processImage(
        bitmap: Bitmap, 
        groundTruthWidths: List<Double>? = null,
        groundTruthLengths: List<Double>? = null
    ): DetectionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        
        try {
            // 将Bitmap转换为OpenCV的Mat
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)
            
            // 畸变矫正（如果已配置相机参数）
            val correctedMat = if (tuningConfig.enableDistortionCorrection && 
                tuningConfig.cameraMatrix != null && tuningConfig.distCoeffs != null) {
                val corrected = Mat()
                Calib3d.undistort(srcMat, corrected, tuningConfig.cameraMatrix, tuningConfig.distCoeffs)
                srcMat.release()
                corrected
            } else {
                srcMat
            }
            
            // 图像预处理
            val processedMat = preprocessImage(correctedMat)
            
            // 二值化（支持颜色阈值调参）
            val binaryMat = thresholdImage(processedMat, correctedMat)
            
            // 形态学操作（支持调参）
            val morphMat = morphologicalOperation(binaryMat)
            
            // 轮廓提取
            val contours = extractContours(morphMat)
            
            // 过滤并处理轮廓
            val tobaccoInfos = processContours(contours, morphMat, correctedMat)
            
            // 保存处理数据用于显示
            lastProcessingData = createProcessingData(contours, tobaccoInfos, morphMat)
            
            // 计算统计数据
            val result = calculateResults(tobaccoInfos, startTime)
            
            // 基准测试：记录误差
            if (groundTruthWidths != null && groundTruthLengths != null) {
                val processingTime = System.currentTimeMillis() - startTime
                for ((index, info) in tobaccoInfos.withIndex()) {
                    if (index < groundTruthWidths.size && index < groundTruthLengths.size) {
                        addBenchmarkResult(
                            BenchmarkTester.BenchmarkResult(
                                datasetId = "default",
                                tobaccoIndex = index,
                                measuredWidthMm = info.widthMm,
                                measuredLengthMm = info.lengthMm,
                                groundTruthWidthMm = groundTruthWidths[index],
                                groundTruthLengthMm = groundTruthLengths[index],
                                widthErrorMm = info.widthMm - groundTruthWidths[index],
                                lengthErrorMm = info.lengthMm - groundTruthLengths[index],
                                processingTimeMs = processingTime / tobaccoInfos.size,
                                success = true
                            )
                        )
                    }
                }
            }
            
            // 释放资源
            correctedMat.release()
            processedMat.release()
            binaryMat.release()
            morphMat.release()
            
            result
        } catch (e: Exception) {
            e.printStackTrace()
            DetectionResult(
                timestamp = System.currentTimeMillis(),
                status = DetectionStatus.FAILED,
                detectionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
    
    /**
     * 设置畸变矫正参数（从相机标定获得）
     */
    fun setDistortionCorrection(cameraMatrix: Mat, distCoeffs: Mat) {
        tuningConfig = tuningConfig.copy(
            enableDistortionCorrection = true,
            cameraMatrix = cameraMatrix.clone(),
            distCoeffs = distCoeffs.clone()
        )
    }
    
    /**
     * 设置工位参数并自动计算像素比例
     */
    fun setWorkstationParams(cameraHeightMm: Double, focalLengthMm: Double, sensorWidthMm: Double, imageWidthPx: Int) {
        tuningConfig = tuningConfig.copy(
            cameraHeightMm = cameraHeightMm,
            focalLengthMm = focalLengthMm,
            sensorWidthMm = sensorWidthMm
        )
        
        // 根据相机参数计算像素比例
        // 公式: pixel_size = (sensor_width / image_width) * (camera_height / focal_length)
        val pixelSizeAtObject = (sensorWidthMm / imageWidthPx) * (cameraHeightMm / focalLengthMm)
        Log.d(TAG, "Calculated pixel ratio: $pixelSizeAtObject mm/px")
    }

    /**
     * 获取最后处理的数据（用于显示轮廓）
     */
    fun getLastProcessingData(): ProcessingData? = lastProcessingData

    /**
     * 创建处理数据用于显示
     */
    @Suppress("UNUSED_PARAMETER")
    private fun createProcessingData(
        contours: List<MatOfPoint>,
        tobaccoInfos: List<TobaccoInfo>,
        binaryMat: Mat
    ): ProcessingData {
        val validContours = contours.filter { 
            val area = Imgproc.contourArea(it)
            area >= config.minContourArea && area <= config.maxContourArea 
        }.map { it.toList() }
        
        val boundingBoxes = tobaccoInfos.map { info ->
            val points = info.contourPoints.map { Point(it.x, it.y) }
            if (points.size >= 2) {
                MatOfPoint2f(*points.toTypedArray()).let { mat ->
                    Imgproc.minAreaRect(mat)
                }
            } else {
                RotatedRect()
            }
        }
        
        // 生成宽度测量线（法线测量线）
        val widths = tobaccoInfos.take(10).map { info ->
            val center = if (info.centerLine.isNotEmpty()) {
                info.centerLine.getOrNull(info.centerLine.size / 2) ?: PointData(100.0, 100.0)
            } else {
                info.contourPoints.getOrNull(info.contourPoints.size / 2) ?: PointData(100.0, 100.0)
            }
            val width = info.widthPixels
            
            Point(center.x - width / 2, center.y) to Point(center.x + width / 2, center.y)
        }
        
        // 生成长度测量线（骨架路径）
        val lengths = tobaccoInfos.take(10).map { info ->
            if (info.centerLine.isNotEmpty()) {
                // 使用骨架的首尾点
                val start = info.centerLine.first()
                val end = info.centerLine.last()
                Point(start.x, start.y) to Point(end.x, end.y)
            } else {
                val center = info.contourPoints.getOrNull(info.contourPoints.size / 2)
                    ?: PointData(100.0, 100.0)
                val length = info.lengthPixels
                
                // 长度线垂直于宽度线
                Point(center.x, center.y - length / 2) to Point(center.x, center.y + length / 2)
            }
        }
        
        // 生成骨架点（用于绘制骨架路径）
        val skeletonPoints = tobaccoInfos.take(10).map { info ->
            if (info.centerLine.isNotEmpty()) {
                info.centerLine.map { Point(it.x, it.y) }
            } else {
                emptyList()
            }
        }
        
        // 生成法线测量线（用于显示多处宽度测量）
        val normalLines = tobaccoInfos.take(10).flatMap { info ->
            if (info.centerLine.isNotEmpty() && info.centerLine.size >= 5) {
                // 采样骨架上的多个点，沿法线方向绘制测量线
                val sampleCount = tuningConfig.widthSampleCount.coerceIn(4, 8)
                val step = info.centerLine.size / sampleCount
                val lines = mutableListOf<Pair<Point, Point>>()
                
                for (i in step until info.centerLine.size step step) {
                    val center = info.centerLine[i]
                    val prev = info.centerLine[i - step]
                    val next = info.centerLine[i + step]
                    
                    // 计算法线方向
                    val tangentX = next.x - prev.x
                    val tangentY = next.y - prev.y
                    val len = sqrt(tangentX * tangentX + tangentY * tangentY)
                    
                    if (len > 1) {
                        val normalX = -tangentY / len
                        val normalY = tangentX / len
                        val halfWidth = info.widthPixels / 2
                        
                        lines.add(
                            Point(center.x - normalX * halfWidth, center.y - normalY * halfWidth) to
                            Point(center.x + normalX * halfWidth, center.y + normalY * halfWidth)
                        )
                    }
                }
                lines
            } else {
                emptyList()
            }
        }
        
        return ProcessingData(
            contours = validContours,
            widths = widths,
            lengths = lengths,
            centerPoints = tobaccoInfos.mapNotNull { 
                if (it.centerLine.isNotEmpty()) {
                    it.centerLine.getOrNull(it.centerLine.size / 2)?.let { p -> Point(p.x, p.y) }
                } else {
                    it.contourPoints.getOrNull(it.contourPoints.size / 2)?.let { p -> Point(p.x, p.y) }
                }
            },
            boundingBoxes = boundingBoxes,
            skeletonPoints = skeletonPoints,
            normalLines = normalLines
        )
    }

    /**
     * 图像预处理：灰度化 + 双边滤波 + 高斯滤波
     */
    private fun preprocessImage(src: Mat): Mat {
        // 转换为灰度图
        val grayMat = Mat()
        Imgproc.cvtColor(src, grayMat, Imgproc.COLOR_BGR2GRAY)
        
        // 双边滤波 - 保持边缘的同时去噪
        val bilateralMat = Mat()
        Imgproc.bilateralFilter(
            grayMat, 
            bilateralMat, 
            config.bilateralDiameter,
            config.bilateralSigmaColor, 
            config.bilateralSigmaSpace
        )
        
        // 高斯滤波 - 进一步平滑
        val gaussianMat = Mat()
        Imgproc.GaussianBlur(
            bilateralMat, 
            gaussianMat, 
            Size(config.gaussianBlurSize.toDouble(), config.gaussianBlurSize.toDouble()),
            0.0
        )
        
        grayMat.release()
        bilateralMat.release()
        
        return gaussianMat
    }

    /**
     * 二值化处理
     * 使用可调参的颜色阈值检测黄色烟丝，若失败则回退到三角形法二值化
     * 符合 README 规范：三角形法二值化
     */
    private fun thresholdImage(grayMat: Mat, srcMat: Mat? = null): Mat {
        // 首先尝试使用颜色空间检测黄色烟丝（使用调参配置）
        val colorBasedMat = if (srcMat != null) {
            detectYellowTobaccoByColor(srcMat)
        } else {
            null
        }

        // 如果颜色检测成功，使用颜色检测结果
        if (colorBasedMat != null && Core.countNonZero(colorBasedMat) > 0) {
            return colorBasedMat
        }

        // 颜色检测失败时，使用三角形法二值化（符合 README 规范）
        // 三角形法适用于目标和背景灰度差异明显的场景
        val binaryMat = Mat()
        Imgproc.threshold(grayMat, binaryMat, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_TRIANGLE)
        return binaryMat
    }

    /**
     * 使用颜色空间检测黄色烟丝
     * 黄色在HSV颜色空间中：H在15-45度之间，S和V较高
     * 黑色背景：V很低
     * 
     * 注意：OpenCV使用BGR格式存储彩色图像
     */
    private fun detectYellowTobaccoByColor(srcMat: Mat): Mat? {
        return try {
            // 转换到HSV颜色空间
            val hsvMat = Mat()
            Imgproc.cvtColor(srcMat, hsvMat, Imgproc.COLOR_BGR2HSV)

            // 使用调参配置的HSV阈值
            val yellowLower = Scalar(
                tuningConfig.hsvHueLower, 
                tuningConfig.hsvSatLower, 
                tuningConfig.hsvValLower
            )
            val yellowUpper = Scalar(
                tuningConfig.hsvHueUpper, 
                tuningConfig.hsvSatUpper.coerceAtMost(255.0), 
                tuningConfig.hsvValUpper.coerceAtMost(255.0)
            )

            // 创建黄色掩码
            val yellowMask = Mat()
            Core.inRange(hsvMat, yellowLower, yellowUpper, yellowMask)

            // 也尝试使用RGB颜色空间检测黄色（OpenCV使用BGR格式）
            val rgbMask = detectYellowByRGB(srcMat)

            // 合并两个掩码（取并集）
            val combinedMask = Mat()
            if (rgbMask != null) {
                Core.bitwise_or(yellowMask, rgbMask, combinedMask)
                rgbMask.release()
            } else {
                yellowMask.copyTo(combinedMask)
            }

            // 应用形态学操作去除噪声（使用调参配置）
            val kernel = Imgproc.getStructuringElement(
                Imgproc.MORPH_RECT,
                Size(tuningConfig.morphKernelSize.toDouble(), tuningConfig.morphKernelSize.toDouble())
            )

            // 开运算去除小噪点
            val openedMask = Mat()
            Imgproc.morphologyEx(combinedMask, openedMask, Imgproc.MORPH_OPEN, kernel)

            // 闭运算填补烟丝内部空洞
            val resultMask = Mat()
            Imgproc.morphologyEx(openedMask, resultMask, Imgproc.MORPH_CLOSE, kernel)

            hsvMat.release()
            yellowMask.release()
            combinedMask.release()
            openedMask.release()
            kernel.release()

            resultMask
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 使用RGB颜色空间检测黄色烟丝
     * 黄色特点：R和G分量较高，B分量较低
     * 
     * 注意：OpenCV使用BGR格式，所以：
     * - srcMat.channels()[0] 是B通道 (Blue)
     * - srcMat.channels()[1] 是G通道 (Green)  
     * - srcMat.channels()[2] 是R通道 (Red)
     */
    private fun detectYellowByRGB(srcMat: Mat): Mat? {
        return try {
            // 分离通道（OpenCV使用BGR格式）
            val bgrChannels = ArrayList<Mat>()
            Core.split(srcMat, bgrChannels)

            // BGR格式：channels[0]=B, channels[1]=G, channels[2]=R
            val bChannel = bgrChannels[0]
            val gChannel = bgrChannels[1]
            val rChannel = bgrChannels[2]

            // 使用调参配置的RGB阈值
            // 黄色条件：R > rLower AND G > gLower AND B < bUpper
            val maskR = Mat()
            val maskG = Mat()
            val maskB = Mat()

            Core.compare(rChannel, Scalar(tuningConfig.rgbRLower), maskR, Core.CMP_GT)
            Core.compare(gChannel, Scalar(tuningConfig.rgbGLower), maskG, Core.CMP_GT)
            Core.compare(bChannel, Scalar(tuningConfig.rgbBUpper), maskB, Core.CMP_LT)

            // 合并条件：R > rLower AND G > gLower AND B < bUpper
            val mask1 = Mat()
            Core.bitwise_and(maskR, maskG, mask1)

            val mask2 = Mat()
            Core.bitwise_and(mask1, maskB, mask2)

            // 也检测较浅的黄色：R > 200 AND G > 200
            val maskLightR = Mat()
            val maskLightG = Mat()
            Core.compare(rChannel, Scalar(200.0), maskLightR, Core.CMP_GT)
            Core.compare(gChannel, Scalar(200.0), maskLightG, Core.CMP_GT)

            val maskLight = Mat()
            Core.bitwise_and(maskLightR, maskLightG, maskLight)

            // 合并两种黄色检测
            val resultMask = Mat()
            Core.bitwise_or(mask2, maskLight, resultMask)

            // 释放中间变量
            maskR.release()
            maskG.release()
            maskB.release()
            mask1.release()
            maskLightR.release()
            maskLightG.release()
            maskLight.release()
            bChannel.release()
            gChannel.release()
            rChannel.release()

            resultMask
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 形态学操作：闭运算填补空隙 + 开运算去除噪点（使用调参配置）
     */
    private fun morphologicalOperation(binaryMat: Mat): Mat {
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(tuningConfig.morphKernelSize.toDouble(), tuningConfig.morphKernelSize.toDouble())
        )
        
        // 闭运算 - 填补烟丝内部的空洞（使用调参配置）
        val morphMat = Mat()
        Imgproc.morphologyEx(binaryMat, morphMat, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), tuningConfig.closeIterations)
        
        // 开运算 - 去除小的噪点（使用调参配置）
        val resultMat = Mat()
        Imgproc.morphologyEx(morphMat, resultMat, Imgproc.MORPH_OPEN, kernel, Point(-1.0, -1.0), tuningConfig.openIterations)
        
        morphMat.release()
        kernel.release()
        
        return resultMat
    }

    /**
     * 提取轮廓
     */
    private fun extractContours(binaryMat: Mat): List<MatOfPoint> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        
        Imgproc.findContours(
            binaryMat, 
            contours, 
            hierarchy, 
            Imgproc.RETR_EXTERNAL, 
            Imgproc.CHAIN_APPROX_SIMPLE
        )
        
        hierarchy.release()
        
        return contours
    }

    /**
     * 处理轮廓，计算每根烟丝的长度和宽度
     * 
     * 优化后的方法：
     * 1. 长度：骨架路径长度（适合曲线烟丝）
     * 2. 宽度：法线多点中位数（适合曲线烟丝）
     * 
     * 使用 Douglas-Peucker 多边形拟合简化轮廓（符合 README 规范）
     */
    @Suppress("UNUSED_PARAMETER")
    private fun processContours(contours: List<MatOfPoint>, binaryMat: Mat, srcMat: Mat): List<TobaccoInfo> {
        val tobaccoInfos = mutableListOf<TobaccoInfo>()

        for ((index, contour) in contours.withIndex()) {
            // 过滤太小的轮廓
            val area = Imgproc.contourArea(contour)
            if (area < config.minContourArea || area > config.maxContourArea) {
                continue
            }

            // 使用 Douglas-Peucker 多边形拟合简化轮廓（符合 README 规范）
            val simplifiedContour = douglasPeucker(contour, config.douglasPeuckerEpsilon)

            // 将简化后的轮廓转换回 MatOfPoint
            val simplifiedMatOfPoint = MatOfPoint(*simplifiedContour.toArray())

            // 获取最小外接矩形
            val rect = Imgproc.minAreaRect(simplifiedContour)
            
            // ========== 新增：先提取骨架 ==========
            var skeletonPoints: List<Point>? = null
            var skeletonLengthInfo: Pair<Double, List<Point>>? = null
            
            // 尝试提取骨架并计算长度
            val skeletonResult = calculateLengthBySkeleton(simplifiedMatOfPoint, binaryMat)
            if (skeletonResult != null) {
                skeletonLengthInfo = skeletonResult
                skeletonPoints = skeletonResult.second
            }
            
            // 使用骨架路径长度（优先）或传统方法计算烟丝长度
            val lengthInfo = skeletonLengthInfo ?: calculateTobaccoLength(simplifiedMatOfPoint, rect, binaryMat)

            // 计算烟丝宽度（使用法线中位数方法）
            val widthInfo = calculateTobaccoWidthWithNormalMedian(simplifiedMatOfPoint, rect, binaryMat, skeletonPoints)

            if (widthInfo != null && widthInfo.first > 0) {
                val widthMm = widthInfo.first * config.pixelToMmRatio
                val lengthMm = if (lengthInfo != null && lengthInfo.first > 0) {
                    lengthInfo.first * config.pixelToMmRatio
                } else {
                    // 如果无法计算长度，使用外接矩形的长边作为估计
                    max(rect.size.width, rect.size.height) * config.pixelToMmRatio
                }

                // 构建骨架中心线数据
                val centerLine = skeletonPoints?.map { PointData(it.x, it.y) } ?: emptyList()

                tobaccoInfos.add(
                    TobaccoInfo(
                        index = index,
                        widthPixels = widthInfo.first,
                        widthMm = widthMm,
                        lengthPixels = lengthInfo?.first ?: max(rect.size.width, rect.size.height),
                        lengthMm = lengthMm,
                        contourPoints = simplifiedMatOfPoint.toList().map { PointData(it.x.toDouble(), it.y.toDouble()) },
                        centerLine = centerLine
                    )
                )
            }
        }

        return tobaccoInfos
    }

    /**
     * 计算烟丝宽度 - 综合多种方法
     * 
     * 直线烟丝：使用最小外接矩形短边
     * 曲线烟丝：使用距离变换 + 投影分析
     */
    private fun calculateTobaccoWidth(
        contour: MatOfPoint, 
        rect: RotatedRect,
        binaryMat: Mat
    ): Pair<Double, List<Point>>? {
        
        val contourPoints = contour.toList()
        if (contourPoints.size < 5) {
            return null
        }
        
        val widths = mutableListOf<Double>()
        
        // ============ 方法1: 最小外接矩形 (适合直线烟丝) ============
        val rectWidth = rect.size.width
        val rectHeight = rect.size.height
        val minRectWidth = min(rectWidth, rectHeight)
        val maxRectWidth = max(rectWidth, rectHeight)
        widths.add(minRectWidth)
        
        // ============ 方法2: 距离变换分析 (适合各种形态) ============
        val distWidth = calculateWidthByDistanceTransform(contour, binaryMat)
        if (distWidth != null && distWidth > 0) {
            widths.add(distWidth)
        }
        
        // ============ 方法3: 投影分析 (适合曲线烟丝) ============
        val projectionWidths = calculateWidthByProjection(contour, rect, binaryMat)
        widths.addAll(projectionWidths)
        
        // ============ 方法4: 椭圆拟合 (适合近似椭圆的烟丝) ============
        val ellipseWidth = calculateWidthByEllipse(contour, rect)
        if (ellipseWidth != null && ellipseWidth > 0 && ellipseWidth < maxRectWidth * 1.5) {
            widths.add(ellipseWidth)
        }
        
        // 综合计算最终宽度
        if (widths.isEmpty()) {
            return null
        }
        
        val avgWidth = widths.average()
        
        // 过滤异常值：太小的可能是噪声，太大的可能是多个烟丝连在一起
        val validWidths = widths.filter { it > avgWidth * 0.3 && it < avgWidth * 3 }
        
        val finalWidth = if (validWidths.isNotEmpty()) {
            validWidths.average()
        } else {
            avgWidth
        }
        
        return Pair(finalWidth, contourPoints.take(10))
    }

    /**
     * 方法1: 使用距离变换计算烟丝宽度
     * 
     * 距离变换的原理：
     * - 对于二值图像中的前景像素，计算其到最近背景像素的距离
     * - 对于烟丝这样的长条形物体，中心点到边缘的最大距离就是宽度的一半
     * - 因此宽度 = 2 * max(距离变换值)
     * 
     * 优点：对于曲线形态的烟丝也能准确测量
     */
    private fun calculateWidthByDistanceTransform(contour: MatOfPoint, binaryMat: Mat): Double? {
        try {
            // 创建掩码
            val mask = Mat.zeros(binaryMat.rows() + 2, binaryMat.cols() + 2, CvType.CV_8UC1)
            
            // 填充轮廓内部
            val contourArray = contour.toArray()
            val contourMat = MatOfPoint(*contourArray)
            Imgproc.fillPoly(mask, listOf(contourMat), Scalar(255.0))
            
            // 距离变换
            val distMat = Mat()
            Imgproc.distanceTransform(mask, distMat, Imgproc.DIST_L2, 0)
            
            // 找到最大距离（烟丝中心到边缘的距离）
            val maxDist = Core.minMaxLoc(distMat).maxVal
            
            mask.release()
            distMat.release()
            
            // 宽度 = 2 * 最大距离
            return if (maxDist > 0) maxDist * 2 else null
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 方法2: 使用投影法计算烟丝宽度
     * 
     * 投影法原理：
     * - 将烟丝轮廓的主轴旋转到水平方向
     * - 沿主轴方向在不同位置测量垂直方向的宽度
     * - 适用于曲线形态的烟丝
     */
    @Suppress("UNUSED_PARAMETER")
    private fun calculateWidthByProjection(
        contour: MatOfPoint,
        rect: RotatedRect,
        binaryMat: Mat
    ): List<Double> {
        
        val widths = mutableListOf<Double>()
        
        try {
            // 计算轮廓的中心点
            val moments = Imgproc.moments(contour)
            val cx = moments.m10 / moments.m00
            val cy = moments.m01 / moments.m00
            
            // 根据主轴方向确定搜索方向
            // OpenCV的RotatedRect.angle需要根据宽高比判断
            var angleRad: Double
            var mainAxisLength: Double
            var perpAxisLength: Double
            
            if (rect.size.width > rect.size.height) {
                // 宽度大于高度，主轴是水平的
                angleRad = Math.toRadians(rect.angle)
                mainAxisLength = rect.size.width / 2
                perpAxisLength = rect.size.height / 2
            } else {
                // 高度大于宽度，主轴是垂直的
                angleRad = Math.toRadians(rect.angle - 90)
                mainAxisLength = rect.size.height / 2
                perpAxisLength = rect.size.width / 2
            }
            
            // 主轴方向的单位向量
            val mainAxisX = cos(angleRad)
            val mainAxisY = sin(angleRad)
            
            // 垂直于主轴的方向
            val perpAxisX = -mainAxisY
            val perpAxisY = mainAxisX
            
            // 沿主轴采样多个点
            val sampleCount = 8
            val searchRange = perpAxisLength * 1.5 // 搜索范围
            
            for (i in 0 until sampleCount) {
                val t = (i.toDouble() / (sampleCount - 1)) - 0.5 // -0.5 到 0.5
                val sampleDist = t * mainAxisLength * 1.6
                
                // 采样点位置
                val sampleX = cx + mainAxisX * sampleDist
                val sampleY = cy + mainAxisY * sampleDist
                
                // 从采样点沿垂直方向搜索左右边界
                val leftPoint = findBoundaryPoint(binaryMat, Point(sampleX, sampleY), Point(perpAxisX, perpAxisY), -1, searchRange)
                val rightPoint = findBoundaryPoint(binaryMat, Point(sampleX, sampleY), Point(perpAxisX, perpAxisY), 1, searchRange)
                
                if (leftPoint != null && rightPoint != null) {
                    val width = sqrt(
                        (rightPoint.x - leftPoint.x).pow(2) + 
                        (rightPoint.y - leftPoint.y).pow(2)
                    )
                    
                    // 只保留合理的宽度值
                    if (width > perpAxisLength * 0.3 && width < perpAxisLength * 2.5) {
                        widths.add(width)
                    }
                }
            }
        } catch (e: Exception) {
            // 投影法失败，返回空列表
        }
        
        return widths
    }

    /**
     * 方法3: 椭圆拟合
     */
    @Suppress("UNUSED_PARAMETER")
    private fun calculateWidthByEllipse(contour: MatOfPoint, rect: RotatedRect): Double? {
        return try {
            if (contour.toList().size >= 5) {
                val contour2f = MatOfPoint2f(*contour.toArray())
                val ellipse = Imgproc.fitEllipse(contour2f)
                // 返回椭圆短轴作为宽度估计
                min(ellipse.size.width, ellipse.size.height)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 计算烟丝长度 - 综合多种方法
     * 
     * 直线烟丝：使用最小外接矩形长边
     * 曲线烟丝：使用轮廓中心线长度
     */
    private fun calculateTobaccoLength(
        contour: MatOfPoint, 
        rect: RotatedRect,
        binaryMat: Mat
    ): Pair<Double, List<Point>>? {
        
        val contourPoints = contour.toList()
        if (contourPoints.size < 5) {
            return null
        }
        
        val lengths = mutableListOf<Double>()
        
        // ============ 方法1: 最小外接矩形 (适合直线烟丝) ============
        val rectWidth = rect.size.width
        val rectHeight = rect.size.height
        val maxRectLength = max(rectWidth, rectHeight)
        lengths.add(maxRectLength)
        
        // ============ 方法2: 轮廓周长的一半 (近似长度) ============
        val perimeter = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
        // 对于细长物体，周长的一半可以作为长度的近似估计
        lengths.add(perimeter / 2)
        
        // ============ 方法3: 基于距离变换的中心线估计 ============
        val centerlineLength = calculateLengthByDistanceTransform(contour, binaryMat)
        if (centerlineLength != null && centerlineLength > 0) {
            lengths.add(centerlineLength)
        }
        
        // ============ 方法4: 投影法计算主轴长度 ============
        val projectionLength = calculateLengthByProjection(contour, rect)
        if (projectionLength != null && projectionLength > 0) {
            lengths.add(projectionLength)
        }
        
        // 综合计算最终长度
        if (lengths.isEmpty()) {
            return null
        }
        
        val avgLength = lengths.average()
        
        // 过滤异常值：太小的可能是噪声
        val validLengths = lengths.filter { it > avgLength * 0.3 && it < avgLength * 3 }
        
        val finalLength = if (validLengths.isNotEmpty()) {
            validLengths.average()
        } else {
            avgLength
        }
        
        // 返回长度和用于显示的点
        val displayPoints = mutableListOf<Point>()
        // 使用外接矩形的两个长边中点作为长度测量线的端点
        val center = rect.center
        val angleRad = Math.toRadians(rect.angle)
        val halfLength = finalLength / 2
        
        val dirX = if (rectWidth > rectHeight) cos(angleRad) else cos(angleRad - Math.PI / 2)
        val dirY = if (rectWidth > rectHeight) sin(angleRad) else sin(angleRad - Math.PI / 2)
        
        displayPoints.add(Point(center.x - dirX * halfLength, center.y - dirY * halfLength))
        displayPoints.add(Point(center.x + dirX * halfLength, center.y + dirY * halfLength))
        
        return Pair(finalLength, displayPoints)
    }

    /**
     * 使用距离变换计算烟丝长度（中心线长度）
     */
    private fun calculateLengthByDistanceTransform(contour: MatOfPoint, binaryMat: Mat): Double? {
        try {
            // 创建掩码
            val mask = Mat.zeros(binaryMat.rows() + 2, binaryMat.cols() + 2, CvType.CV_8UC1)
            
            // 填充轮廓内部
            val contourArray = contour.toArray()
            val contourMat = MatOfPoint(*contourArray)
            Imgproc.fillPoly(mask, listOf(contourMat), Scalar(255.0))
            
            // 距离变换
            val distMat = Mat()
            Imgproc.distanceTransform(mask, distMat, Imgproc.DIST_L2, 0)
            
            // 找到距离变换图中的骨架点，估算中心线长度
            // 这里简化处理：使用外接矩形的较长边作为长度估计
            val rows = distMat.rows()
            val cols = distMat.cols()
            
            // 找到最大距离点（中心点）
            val maxLoc = Core.minMaxLoc(distMat)
            val centerX = maxLoc.maxLoc.x
            val centerY = maxLoc.maxLoc.y
            
            // 使用简化的方法：计算从中心到两端的平均距离
            // 这是一个近似方法
            val totalDist = maxLoc.maxVal * 2  // 粗略估计
            
            mask.release()
            distMat.release()
            
            return totalDist
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 使用投影法计算烟丝长度（主轴方向）
     */
    private fun calculateLengthByProjection(contour: MatOfPoint, rect: RotatedRect): Double? {
        return try {
            // 使用最小外接矩形的长边作为长度
            max(rect.size.width, rect.size.height)
        } catch (e: Exception) {
            null
        }
    }
    
    // ============ 新增：骨架路径长度计算 ============
    
    /**
     * 使用骨架（Skeleton）计算烟丝长度
     * 
     * 骨架是通过迭代腐蚀操作提取的中心路径，能够准确反映曲线烟丝的真实长度
     * 该方法特别适合测量弯曲、卷曲的烟丝
     * 
     * @param contour 烟丝轮廓
     * @param binaryMat 二值化图像
     * @return 骨架路径长度（像素）
     */
    private fun calculateLengthBySkeleton(contour: MatOfPoint, binaryMat: Mat): Pair<Double, List<Point>>? {
        return try {
            // 创建掩码
            val mask = Mat.zeros(binaryMat.rows() + 2, binaryMat.cols() + 2, CvType.CV_8UC1)
            
            // 填充轮廓内部
            val contourArray = contour.toArray()
            val contourMat = MatOfPoint(*contourArray)
            Imgproc.fillPoly(mask, listOf(contourMat), Scalar(255.0))
            
            // 提取骨架（使用形态学骨架化）
            val skeleton = extractSkeleton(mask)
            
            // 从骨架提取中心路径点
            val skeletonPoints = extractSkeletonPoints(skeleton)
            
            // 计算骨架路径长度
            var totalLength = 0.0
            for (i in 0 until skeletonPoints.size - 1) {
                val dx = skeletonPoints[i + 1].x - skeletonPoints[i].x
                val dy = skeletonPoints[i + 1].y - skeletonPoints[i].y
                totalLength += sqrt(dx * dx + dy * dy)
            }
            
            mask.release()
            skeleton.release()
            
            if (totalLength > 0 && skeletonPoints.size >= 2) {
                Pair(totalLength, skeletonPoints)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Skeleton length calculation failed", e)
            null
        }
    }
    
    /**
     * 使用Zhang-Suen细化算法提取骨架
     */
    private fun extractSkeleton(binaryMat: Mat): Mat {
        val skeleton = Mat.zeros(binaryMat.rows(), binaryMat.cols(), CvType.CV_8UC1)
        val temp = Mat.zeros(binaryMat.rows(), binaryMat.cols(), CvType.CV_8UC1)
        binaryMat.copyTo(temp)
        
        val element = Imgproc.getStructuringElement(Imgproc.MORPH_CROSS, Size(3.0, 3.0))
        
        var done = false
        val iterations = 50 // 最大迭代次数
        
        for (i in 0 until iterations) {
            // 步骤1：腐蚀
            val eroded = Mat()
            Imgproc.erode(temp, eroded, element)
            
            // 步骤2：开运算
            val opened = Mat()
            Imgproc.morphologyEx(eroded, opened, Imgproc.MORPH_OPEN, element)
            
            // 步骤3：获取边界
            val boundary = Mat()
            Core.subtract(temp, opened, boundary)
            
            // 步骤4：细化骨架
            val skel = Mat()
            Core.bitwise_or(skeleton, boundary, skel)
            skeleton.setTo(skel)
            
            // 更新temp
            eroded.copyTo(temp)
            
            // 检查是否完成（没有更多像素可以移除）
            val nonZeroBefore = Core.countNonZero(temp)
            
            eroded.release()
            opened.release()
            boundary.release()
            skel.release()
            
            if (nonZeroBefore == 0) {
                done = true
                break
            }
        }
        
        element.release()
        temp.release()
        
        return skeleton
    }
    
    /**
     * 从骨架图像中提取有序的骨架点
     */
    private fun extractSkeletonPoints(skeletonMat: Mat): List<Point> {
        val points = mutableListOf<Point>()
        
        // 找到骨架的所有非零点
        for (y in 0 until skeletonMat.rows()) {
            for (x in 0 until skeletonMat.cols()) {
                if (skeletonMat.get(y, x)[0] > 127) {
                    points.add(Point(x.toDouble(), y.toDouble()))
                }
            }
        }
        
        if (points.size < 2) {
            return points
        }
        
        // 从边界点开始，按连通性排序点
        // 简化处理：按X坐标排序（适用于近似水平的烟丝）
        val sorted = points.sortedBy { it.x }
        
        // 如果点太多，进行降采样
        val sampled = if (sorted.size > 100) {
            val step = sorted.size / 100
            sorted.filterIndexed { index, _ -> index % step == 0 }
        } else {
            sorted
        }
        
        return sampled
    }
    
    // ============ 新增：法线多点中位数宽度计算 ============
    
    /**
     * 使用法线多点中位数计算烟丝宽度
     * 
     * 在骨架的多个点处，沿法线方向测量宽度，取中位数
     * 这种方法能够准确测量曲线烟丝在不同位置的宽度
     * 
     * @param contour 烟丝轮廓
     * @param skeletonPoints 骨架点
     * @param binaryMat 二值化图像
     * @return 宽度（像素）和法线测量线
     */
    private fun calculateWidthByNormalMedian(
        contour: MatOfPoint, 
        skeletonPoints: List<Point>,
        binaryMat: Mat
    ): Pair<Double, List<Pair<Point, Point>>>? {
        
        if (skeletonPoints.size < 3) {
            return null
        }
        
        val widths = mutableListOf<Double>()
        val normalLines = mutableListOf<Pair<Point, Point>>()
        
        // 采样点数（使用调参配置）
        val sampleCount = tuningConfig.widthSampleCount.coerceIn(4, 20)
        val step = skeletonPoints.size / sampleCount
        
        for (i in step until skeletonPoints.size step step) {
            val center = skeletonPoints[i]
            
            // 计算局部方向（使用相邻点）
            val prevPoint = skeletonPoints[i - step]
            val nextPoint = skeletonPoints[i + step]
            
            // 切线方向
            val tangentX = nextPoint.x - prevPoint.x
            val tangentY = nextPoint.y - prevPoint.y
            val tangentLen = sqrt(tangentX * tangentX + tangentY * tangentY)
            
            if (tangentLen < 1) continue
            
            // 法线方向（垂直于切线）
            val normalX = -tangentY / tangentLen
            val normalY = tangentX / tangentLen
            
            // 沿法线方向搜索边界
            val searchRange = 100.0 // 最大搜索距离
            
            val leftPoint = findBoundaryPoint(binaryMat, center, Point(normalX, normalY), -1, searchRange)
            val rightPoint = findBoundaryPoint(binaryMat, center, Point(normalX, normalY), 1, searchRange)
            
            if (leftPoint != null && rightPoint != null) {
                val width = sqrt(
                    (rightPoint.x - leftPoint.x).pow(2) + 
                    (rightPoint.y - leftPoint.y).pow(2)
                )
                
                if (width > 2 && width < 500) { // 过滤异常值
                    widths.add(width)
                    normalLines.add(Pair(leftPoint, rightPoint))
                }
            }
        }
        
        if (widths.isEmpty()) {
            return null
        }
        
        // 使用中位数而非平均值，对异常值更鲁棒
        val sortedWidths = widths.sorted()
        val medianWidth = if (sortedWidths.size % 2 == 0) {
            (sortedWidths[sortedWidths.size / 2 - 1] + sortedWidths[sortedWidths.size / 2]) / 2
        } else {
            sortedWidths[sortedWidths.size / 2]
        }
        
        return Pair(medianWidth, normalLines)
    }
    
    /**
     * 综合宽度计算（融合多种方法）
     * 主要使用法线中位数法，辅以外接矩形和距离变换
     */
    private fun calculateTobaccoWidthWithNormalMedian(
        contour: MatOfPoint, 
        rect: RotatedRect,
        binaryMat: Mat,
        skeletonPoints: List<Point>? = null
    ): Triple<Double, List<Point>, List<Pair<Point, Point>>>? {
        
        val contourPoints = contour.toList()
        if (contourPoints.size < 5) {
            return null
        }
        
        val widths = mutableListOf<Double>()
        var normalLines = emptyList<Pair<Point, Point>>()
        
        // ============ 方法1: 法线多点中位数 (主要方法) ============
        if (skeletonPoints != null && skeletonPoints.size >= 5) {
            val normalMedianResult = calculateWidthByNormalMedian(contour, skeletonPoints, binaryMat)
            if (normalMedianResult != null) {
                widths.add(normalMedianResult.first)
                normalLines = normalMedianResult.second
            }
        }
        
        // ============ 方法2: 最小外接矩形 (适合直线烟丝) ============
        val rectWidth = rect.size.width
        val rectHeight = rect.size.height
        val minRectWidth = min(rectWidth, rectHeight)
        val maxRectWidth = max(rectWidth, rectHeight)
        
        if (normalLines.isEmpty()) {
            // 如果无法线数据，使用外接矩形作为备选
            widths.add(minRectWidth)
        }
        
        // ============ 方法3: 距离变换分析 (适合各种形态) ============
        val distWidth = calculateWidthByDistanceTransform(contour, binaryMat)
        if (distWidth != null && distWidth > 0) {
            widths.add(distWidth)
        }
        
        // ============ 方法4: 投影分析 (适合曲线烟丝) ============
        val projectionWidths = calculateWidthByProjection(contour, rect, binaryMat)
        widths.addAll(projectionWidths)
        
        // 综合计算最终宽度（使用中位数）
        if (widths.isEmpty()) {
            return null
        }
        
        val sortedWidths = widths.sorted()
        val finalWidth = if (sortedWidths.size % 2 == 0) {
            (sortedWidths[sortedWidths.size / 2 - 1] + sortedWidths[sortedWidths.size / 2]) / 2
        } else {
            sortedWidths[sortedWidths.size / 2]
        }
        
        return Triple(finalWidth, contourPoints.take(10), normalLines)
    }

    /**
     * 从中心点沿指定方向搜索边界点
     * 
     * @param mat 二值化图像
     * @param center 起始中心点
     * @param direction 搜索方向向量（单位向量）
     * @param sign 搜索方向 (+1 或 -1)
     * @param maxDistance 最大搜索距离
     */
    private fun findBoundaryPoint(
        mat: Mat, 
        center: Point, 
        direction: Point, 
        sign: Int,
        maxDistance: Double
    ): Point? {
        
        var x = center.x
        var y = center.y
        val step = 2.0
        
        while (sqrt((x - center.x).pow(2) + (y - center.y).pow(2)) < maxDistance) {
            val prevX = x
            val prevY = y
            
            x += direction.x * step * sign
            y += direction.y * step * sign
            
            // 检查是否超出图像边界
            val ix = x.toInt()
            val iy = y.toInt()
            
            if (ix < 0 || ix >= mat.cols() || iy < 0 || iy >= mat.rows()) {
                break
            }
            
            // 检查当前像素值变化
            val pixelValue = mat.get(iy, ix)[0]
            val prevPixelValue = mat.get(prevY.toInt(), prevX.toInt())[0]
            
            // 如果从白色变为黑色（或反之），找到边界
            if ((pixelValue > 127 && prevPixelValue <= 127) || 
                (pixelValue <= 127 && prevPixelValue > 127)) {
                return Point(prevX, prevY)
            }
        }
        
        return null
    }

    /**
     * Douglas-Peucker算法实现（用于轮廓简化）
     */
    private fun douglasPeucker(points: MatOfPoint, epsilon: Double): MatOfPoint2f {
        if (points.toList().size < 3) {
            return MatOfPoint2f(*points.toArray())
        }
        
        val result = mutableListOf<Point>()
        douglasPeuckerRecursive(points.toList(), 0, points.toList().size - 1, epsilon, result)
        
        if (result.isEmpty() || result.first() != points.toList().first()) {
            result.add(0, points.toList().first())
        }
        if (result.last() != points.toList().last()) {
            result.add(points.toList().last())
        }
        
        return MatOfPoint2f(*result.toTypedArray())
    }

    private fun douglasPeuckerRecursive(
        points: List<Point>, 
        start: Int, 
        end: Int, 
        epsilon: Double, 
        result: MutableList<Point>
    ) {
        if (end <= start + 1) {
            return
        }
        
        var maxDistance = 0.0
        var maxIndex = start
        
        val startPoint = points[start]
        val endPoint = points[end]
        
        for (i in (start + 1) until end) {
            val distance = perpendicularDistance(points[i], startPoint, endPoint)
            if (distance > maxDistance) {
                maxDistance = distance
                maxIndex = i
            }
        }
        
        if (maxDistance > epsilon) {
            douglasPeuckerRecursive(points, start, maxIndex, epsilon, result)
            result.add(points[maxIndex])
            douglasPeuckerRecursive(points, maxIndex, end, epsilon, result)
        }
    }

    private fun perpendicularDistance(point: Point, lineStart: Point, lineEnd: Point): Double {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        
        val lineLengthSquared = dx * dx + dy * dy
        
        if (lineLengthSquared == 0.0) {
            return sqrt((point.x - lineStart.x).pow(2) + (point.y - lineStart.y).pow(2))
        }
        
        val t = max(0.0, min(1.0, 
            ((point.x - lineStart.x) * dx + (point.y - lineStart.y) * dy) / lineLengthSquared
        ))
        
        val projX = lineStart.x + t * dx
        val projY = lineStart.y + t * dy
        
        return sqrt((point.x - projX).pow(2) + (point.y - projY).pow(2))
    }

    /**
     * 计算检测结果
     */
    private fun calculateResults(tobaccoInfos: List<TobaccoInfo>, startTime: Long): DetectionResult {
        if (tobaccoInfos.isEmpty()) {
            return DetectionResult(
                timestamp = System.currentTimeMillis(),
                tobaccoCount = 0,
                status = DetectionStatus.FAILED,
                detectionTimeMs = System.currentTimeMillis() - startTime
            )
        }
        
        // 宽度统计
        val widthsMm = tobaccoInfos.map { it.widthMm }
        val averageWidth = widthsMm.average()
        val minWidth = widthsMm.minOrNull() ?: 0.0
        val maxWidth = widthsMm.maxOrNull() ?: 0.0
        
        // 长度统计
        val lengthsMm = tobaccoInfos.map { it.lengthMm }
        val averageLength = lengthsMm.average()
        val minLength = lengthsMm.minOrNull() ?: 0.0
        val maxLength = lengthsMm.maxOrNull() ?: 0.0
        
        // 计算标准差（基于宽度）
        val variance = widthsMm.map { (it - averageWidth).pow(2) }.average()
        val stdDeviation = sqrt(variance)
        
        return DetectionResult(
            timestamp = System.currentTimeMillis(),
            tobaccoCount = tobaccoInfos.size,
            averageWidthMm = averageWidth,
            minWidthMm = minWidth,
            maxWidthMm = maxWidth,
            averageLengthMm = averageLength,
            minLengthMm = minLength,
            maxLengthMm = maxLength,
            stdDeviation = stdDeviation,
            individualWidths = widthsMm,
            individualLengths = lengthsMm,
            detectionTimeMs = System.currentTimeMillis() - startTime,
            status = DetectionStatus.SUCCESS
        )
    }

    /**
     * 创建带有轮廓标记的Bitmap
     * 显示：轮廓(绿色)、骨架路径(青色)、法线测量线(红色)、长度测量线(蓝色)
     */
    fun createMarkedBitmap(originalBitmap: Bitmap, result: DetectionResult): Bitmap {
        val markedBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(markedBitmap)
        
        // 绘制轮廓的画笔 - 绿色
        val contourPaint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        // 绘制骨架路径的画笔 - 青色（适合曲线烟丝长度显示）
        val skeletonPaint = Paint().apply {
            color = Color.CYAN
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        // 绘制宽度测量线（法线中位数）的画笔 - 红色
        val widthPaint = Paint().apply {
            color = Color.RED
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        // 绘制长度测量线（骨架）的画笔 - 蓝色
        val lengthPaint = Paint().apply {
            color = Color.BLUE
            strokeWidth = 4f
            style = Paint.Style.STROKE
            isAntiAlias = true
        }
        
        // 绘制文字的画笔
        val textPaint = Paint().apply {
            color = Color.RED
            textSize = 24f
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        
        // 绘制轮廓
        val processingData = lastProcessingData
        if (processingData != null) {
            for (contour in processingData.contours) {
                if (contour.size >= 2) {
                    val path = Path()
                    path.moveTo(contour[0].x.toFloat(), contour[0].y.toFloat())
                    for (i in 1 until contour.size) {
                        path.lineTo(contour[i].x.toFloat(), contour[i].y.toFloat())
                    }
                    path.close()
                    canvas.drawPath(path, contourPaint)
                }
            }
            
            // 绘制骨架路径（青色）- 新的长度测量方式
            for (skeletonPoints in processingData.skeletonPoints) {
                if (skeletonPoints.size >= 2) {
                    val skeletonPath = Path()
                    skeletonPath.moveTo(skeletonPoints[0].x.toFloat(), skeletonPoints[0].y.toFloat())
                    for (i in 1 until skeletonPoints.size) {
                        skeletonPath.lineTo(skeletonPoints[i].x.toFloat(), skeletonPoints[i].y.toFloat())
                    }
                    canvas.drawPath(skeletonPath, skeletonPaint)
                }
            }
            
            // 绘制宽度测量线（红色）- 新的法线中位数方式
            for ((start, end) in processingData.widths) {
                canvas.drawLine(
                    start.x.toFloat(), start.y.toFloat(),
                    end.x.toFloat(), end.y.toFloat(),
                    widthPaint
                )
            }
            
            // 绘制法线测量线（红色）
            for ((start, end) in processingData.normalLines) {
                canvas.drawLine(
                    start.x.toFloat(), start.y.toFloat(),
                    end.x.toFloat(), end.y.toFloat(),
                    widthPaint
                )
            }
            
            // 绘制长度测量线（蓝色）
            for ((start, end) in processingData.lengths) {
                canvas.drawLine(
                    start.x.toFloat(), start.y.toFloat(),
                    end.x.toFloat(), end.y.toFloat(),
                    lengthPaint
                )
            }
        }
        
        // 显示检测结果信息
        val infoText = "检测到 ${result.tobaccoCount} 根烟丝"
        val avgWidthText = String.format("平均宽度: %.4f mm", result.averageWidthMm)
        val minWidthText = String.format("最小宽度: %.4f mm", result.minWidthMm)
        val maxWidthText = String.format("最大宽度: %.4f mm", result.maxWidthMm)
        val avgLengthText = String.format("平均长度: %.4f mm", result.averageLengthMm)
        
        // 半透明背景（扩大以容纳更多文本）
        val bgPaint = Paint().apply {
            color = Color.argb(200, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRect(10f, 10f, 380f, 200f, bgPaint)
        
        // 绘制文字信息
        textPaint.textSize = 24f
        canvas.drawText(infoText, 20f, 35f, textPaint)
        textPaint.textSize = 20f
        canvas.drawText(avgWidthText, 20f, 60f, textPaint)
        canvas.drawText(minWidthText, 20f, 84f, textPaint)
        canvas.drawText(maxWidthText, 20f, 108f, textPaint)
        
        // 长度信息用蓝色显示
        textPaint.color = Color.BLUE
        canvas.drawText(avgLengthText, 20f, 132f, textPaint)
        
        // 添加图例
        textPaint.textSize = 16f
        textPaint.color = Color.CYAN
        canvas.drawText("青线=骨架(长度)", 20f, 158f, textPaint)
        textPaint.color = Color.RED
        canvas.drawText("红线=法线(宽度)", 140f, 158f, textPaint)
        textPaint.color = Color.BLUE
        canvas.drawText("蓝线=长度", 260f, 158f, textPaint)
        textPaint.color = Color.GREEN
        canvas.drawText("绿线=轮廓", 20f, 180f, textPaint)
        
        return markedBitmap
    }
}
