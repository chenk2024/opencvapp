package com.tobacco.detection.testing

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import com.tobacco.detection.data.BenchmarkDataset
import com.tobacco.detection.data.BenchmarkSummary
import com.tobacco.detection.data.DetectionResult
import com.tobacco.detection.data.GroundTruth
import com.tobacco.detection.data.ProcessingConfig
import com.tobacco.detection.processing.TobaccoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * 基准测试器
 * 用于运行基准测试并生成评估报告
 */
class BenchmarkTester(
    private val context: Context,
    private val processor: TobaccoProcessor
) {
    companion object {
        private const val TAG = "BenchmarkTester"
    }
    
    private val benchmarkResults = mutableListOf<BenchmarkResult>()
    
    data class BenchmarkResult(
        val datasetId: String,
        val tobaccoIndex: Int,
        val measuredWidthMm: Double,
        val measuredLengthMm: Double,
        val groundTruthWidthMm: Double,
        val groundTruthLengthMm: Double,
        val widthErrorMm: Double,
        val lengthErrorMm: Double,
        val processingTimeMs: Long,
        val success: Boolean
    )
    
    /**
     * 从JSON文件加载基准测试数据集
     */
    fun loadBenchmarkDatasetFromJson(jsonPath: String): BenchmarkDataset? {
        return try {
            val jsonString = File(jsonPath).readText()
            val json = JSONObject(jsonString)
            
            val id = json.getString("id")
            val name = json.getString("name")
            val imagePath = json.getString("image_path")
            
            val groundTruthsArray = json.getJSONArray("ground_truths")
            val groundTruths = mutableListOf<GroundTruth>()
            
            for (i in 0 until groundTruthsArray.length()) {
                val gt = groundTruthsArray.getJSONObject(i)
                groundTruths.add(
                    GroundTruth(
                        tobaccoIndex = gt.getInt("tobacco_index"),
                        widthMm = gt.getDouble("width_mm"),
                        lengthMm = gt.getDouble("length_mm"),
                        notes = gt.optString("notes", "")
                    )
                )
            }
            
            BenchmarkDataset(id, name, imagePath, groundTruths)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load benchmark dataset", e)
            null
        }
    }
    
    /**
     * 运行单个基准测试
     */
    suspend fun runBenchmark(dataset: BenchmarkDataset): Boolean = withContext(Dispatchers.Default) {
        try {
            val bitmap = BitmapFactory.decodeFile(dataset.imagePath)
            if (bitmap == null) {
                Log.e(TAG, "Failed to load image: ${dataset.imagePath}")
                return@withContext false
            }
            
            // 提取真值
            val groundTruthWidths = dataset.groundTruths.map { it.widthMm }
            val groundTruthLengths = dataset.groundTruths.map { it.lengthMm }
            
            // 运行检测
            val startTime = System.currentTimeMillis()
            val result = processor.processImage(bitmap, groundTruthWidths, groundTruthLengths)
            val processingTime = System.currentTimeMillis() - startTime
            
            // 记录结果
            for ((index, info) in result.individualWidths.withIndex()) {
                if (index < dataset.groundTruths.size) {
                    val gt = dataset.groundTruths[index]
                    val widthError = abs(info - gt.widthMm)
                    val lengthError = if (index < result.individualLengths.size) {
                        abs(result.individualLengths[index] - gt.lengthMm)
                    } else 0.0
                    
                    benchmarkResults.add(
                        BenchmarkResult(
                            datasetId = dataset.id,
                            tobaccoIndex = index,
                            measuredWidthMm = info,
                            measuredLengthMm = result.individualLengths.getOrElse(index) { 0.0 },
                            groundTruthWidthMm = gt.widthMm,
                            groundTruthLengthMm = gt.lengthMm,
                            widthErrorMm = widthError,
                            lengthErrorMm = lengthError,
                            processingTimeMs = processingTime / result.tobaccoCount.coerceAtLeast(1),
                            success = widthError <= 0.01 && lengthError <= 0.01
                        )
                    )
                }
            }
            
            bitmap.recycle()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Benchmark failed", e)
            false
        }
    }
    
    /**
     * 运行多个基准测试
     */
    suspend fun runAllBenchmarks(datasets: List<BenchmarkDataset>): Map<String, Any> = withContext(Dispatchers.Default) {
        benchmarkResults.clear()
        
        for (dataset in datasets) {
            runBenchmark(dataset)
        }
        
        generateSummary()
    }
    
    /**
     * 生成基准测试汇总报告
     */
    fun generateSummary(): Map<String, Any> {
        if (benchmarkResults.isEmpty()) {
            return emptyMap()
        }
        
        val widthErrors = benchmarkResults.map { it.widthErrorMm }
        val lengthErrors = benchmarkResults.map { it.lengthErrorMm }
        
        // MAE
        val widthMAE = widthErrors.average()
        val lengthMAE = lengthErrors.average()
        
        // RMSE
        val widthRMSE = kotlin.math.sqrt(widthErrors.map { it * it }.average())
        val lengthRMSE = kotlin.math.sqrt(lengthErrors.map { it * it }.average())
        
        // 95分位
        val sortedWidthErrors = widthErrors.sorted()
        val sortedLengthErrors = lengthErrors.sorted()
        val width95 = sortedWidthErrors[(sortedWidthErrors.size * 0.95).toInt().coerceIn(0, sortedWidthErrors.size - 1)]
        val length95 = sortedLengthErrors[(sortedLengthErrors.size * 0.95).toInt().coerceIn(0, sortedLengthErrors.size - 1)]
        
        // 成功率
        val successCount = benchmarkResults.count { it.success }
        val successRate = successCount.toDouble() / benchmarkResults.size
        
        // 平均处理时间
        val avgTime = benchmarkResults.map { it.processingTimeMs }.average()
        
        return mapOf(
            "total_samples" to benchmarkResults.size,
            "width_mae" to widthMAE,
            "width_rmse" to widthRMSE,
            "width_95_percentile" to width95,
            "length_mae" to lengthMAE,
            "length_rmse" to lengthRMSE,
            "length_95_percentile" to length95,
            "success_rate" to successRate,
            "avg_processing_time_ms" to avgTime
        )
    }
    
    /**
     * 导出详细结果到CSV
     */
    fun exportToCSV(filePath: String): Boolean {
        return try {
            val csv = buildString {
                appendLine("dataset_id,tobacco_index,measured_width_mm,measured_length_mm,ground_truth_width_mm,ground_truth_length_mm,width_error_mm,length_error_mm,processing_time_ms,success")
                for (result in benchmarkResults) {
                    appendLine("${result.datasetId},${result.tobaccoIndex},${result.measuredWidthMm},${result.measuredLengthMm},${result.groundTruthWidthMm},${result.groundTruthLengthMm},${result.widthErrorMm},${result.lengthErrorMm},${result.processingTimeMs},${result.success}")
                }
            }
            File(filePath).writeText(csv)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export CSV", e)
            false
        }
    }
    
    /**
     * 生成JSON格式的汇总报告
     */
    fun generateJSONReport(): String {
        val summary = generateSummary()
        val json = JSONObject()
        
        for ((key, value) in summary) {
            json.put(key, value)
        }
        
        // 添加详细结果
        val resultsArray = JSONArray()
        for (result in benchmarkResults) {
            val resultJson = JSONObject().apply {
                put("dataset_id", result.datasetId)
                put("tobacco_index", result.tobaccoIndex)
                put("measured_width_mm", result.measuredWidthMm)
                put("measured_length_mm", result.measuredLengthMm)
                put("ground_truth_width_mm", result.groundTruthWidthMm)
                put("ground_truth_length_mm", result.groundTruthLengthMm)
                put("width_error_mm", result.widthErrorMm)
                put("length_error_mm", result.lengthErrorMm)
                put("processing_time_ms", result.processingTimeMs)
                put("success", result.success)
            }
            resultsArray.put(resultJson)
        }
        
        json.put("detailed_results", resultsArray)
        
        return json.toString(2)
    }
}
