package com.tobacco.detection.data

import android.content.Context
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.poi.ss.usermodel.*
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 数据管理器
 * 负责保存检测结果、上传数据、导出Excel等功能
 */
class DataManager(private val context: Context) {

    companion object {
        private const val TAG = "DataManager"
        private const val JSON_FILE_NAME = "detection_results.json"
        private const val SETTINGS_FILE_NAME = "app_settings.json"
        private const val MEDIA_TYPE_JSON = "application/json; charset=utf-8"
    }

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val fileNameFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    /**
     * 保存检测结果到JSON文件
     */
    suspend fun saveResult(result: DetectionResult): Boolean = withContext(Dispatchers.IO) {
        try {
            val results = loadResults().toMutableList()
            results.add(result)
            
            val json = gson.toJson(results)
            val file = getResultsFile()
            file.writeText(json)
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 加载所有检测结果
     */
    suspend fun loadResults(): List<DetectionResult> = withContext(Dispatchers.IO) {
        try {
            val file = getResultsFile()
            if (!file.exists()) {
                return@withContext emptyList()
            }
            
            val json = file.readText()
            if (json.isBlank()) {
                return@withContext emptyList()
            }
            
            gson.fromJson(json, Array<DetectionResult>::class.java)?.toList() ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 导出结果到Excel文件 (使用Apache POI)
     */
    suspend fun exportToExcel(results: List<DetectionResult>): File? = withContext(Dispatchers.IO) {
        try {
            // 创建工作簿
            val workbook: Workbook = XSSFWorkbook()
            
            // 创建工作表
            val sheet: Sheet = workbook.createSheet("检测结果")
            
            // 创建表头样式
            val headerStyle = workbook.createCellStyle().apply {
                val font = workbook.createFont().apply {
                    bold = true
                    fontHeightInPoints = 12.toShort()
                }
                setFont(font)
                alignment = HorizontalAlignment.CENTER
                setFillForegroundColor(IndexedColors.LIGHT_BLUE.index)
                fillPattern = FillPatternType.SOLID_FOREGROUND
            }
            
            // 创建表头行
            val headerRow: Row = sheet.createRow(0)
            val headers = arrayOf(
                "序号", "检测时间", "烟丝数量", "平均宽度(mm)", 
                "最小宽度(mm)", "最大宽度(mm)", "标准差", "检测耗时(ms)", "状态"
            )
            
            headers.forEachIndexed { index, header ->
                val cell = headerRow.createCell(index)
                cell.setCellValue(header)
                cell.cellStyle = headerStyle
            }
            
            // 填充数据
            results.forEachIndexed { index, result ->
                val row: Row = sheet.createRow(index + 1)
                
                row.createCell(0).setCellValue((index + 1).toDouble())
                row.createCell(1).setCellValue(dateFormat.format(Date(result.timestamp)))
                row.createCell(2).setCellValue(result.tobaccoCount.toDouble())
                row.createCell(3).setCellValue(result.averageWidthMm)
                row.createCell(4).setCellValue(result.minWidthMm)
                row.createCell(5).setCellValue(result.maxWidthMm)
                row.createCell(6).setCellValue(result.stdDeviation)
                row.createCell(7).setCellValue(result.detectionTimeMs.toDouble())
                row.createCell(8).setCellValue(result.status.name)
            }
            
            // 调整列宽
            sheet.setColumnWidth(0, 8 * 256)
            sheet.setColumnWidth(1, 20 * 256)
            sheet.setColumnWidth(2, 12 * 256)
            sheet.setColumnWidth(3, 15 * 256)
            sheet.setColumnWidth(4, 15 * 256)
            sheet.setColumnWidth(5, 15 * 256)
            sheet.setColumnWidth(6, 12 * 256)
            sheet.setColumnWidth(7, 15 * 256)
            sheet.setColumnWidth(8, 10 * 256)
            
            // 保存文件
            val exportDir = getExportDirectory()
            val fileName = "烟丝检测结果_${fileNameFormat.format(Date())}.xlsx"
            val file = File(exportDir, fileName)
            
            val outputStream = FileOutputStream(file)
            workbook.write(outputStream)
            outputStream.close()
            workbook.close()
            
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 上传结果到服务器
     */
    suspend fun uploadResult(result: DetectionResult, apiUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            
            val json = gson.toJson(result)
            val requestBody = json.toRequestBody(MEDIA_TYPE_JSON.toMediaType())
            
            val request = Request.Builder()
                .url(apiUrl)
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 上传图片和结果到服务器
     */
    suspend fun uploadWithImage(result: DetectionResult, imagePath: String, apiUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient()
            
            val requestBodyBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
            
            // 添加图片
            val imageFile = File(imagePath)
            if (imageFile.exists()) {
                val imageBytes = imageFile.readBytes()
                requestBodyBuilder.addFormDataPart(
                    "image",
                    imageFile.name,
                    imageBytes.toRequestBody("image/jpeg".toMediaType())
                )
            }
            
            // 添加JSON数据
            val json = gson.toJson(result)
            requestBodyBuilder.addFormDataPart("data", json)
            
            val request = Request.Builder()
                .url(apiUrl)
                .post(requestBodyBuilder.build())
                .build()
            
            val response = client.newCall(request).execute()
            
            response.isSuccessful
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 保存图片到本地
     */
    suspend fun saveImage(bitmap: android.graphics.Bitmap): String? = withContext(Dispatchers.IO) {
        try {
            val imageDir = getImageDirectory()
            val fileName = "tobacco_${fileNameFormat.format(Date())}.jpg"
            val file = File(imageDir, fileName)
            
            val outputStream = FileOutputStream(file)
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, outputStream)
            outputStream.close()
            
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 删除检测结果
     */
    suspend fun deleteResult(resultId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val results = loadResults().toMutableList()
            val removed = results.removeAll { it.id == resultId }
            
            if (removed) {
                val json = gson.toJson(results)
                val file = getResultsFile()
                file.writeText(json)
            }
            
            removed
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 清空所有历史记录
     */
    suspend fun clearAllResults(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = getResultsFile()
            file.writeText("[]")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 保存应用设置
     */
    suspend fun saveSettings(settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(settings)
            val file = getSettingsFile()
            file.writeText(json)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 加载应用设置
     */
    suspend fun loadSettings(): AppSettings = withContext(Dispatchers.IO) {
        try {
            val file = getSettingsFile()
            if (!file.exists()) {
                return@withContext AppSettings()
            }
            
            val json = file.readText()
            if (json.isBlank()) {
                return@withContext AppSettings()
            }
            
            gson.fromJson(json, AppSettings::class.java) ?: AppSettings()
        } catch (e: Exception) {
            e.printStackTrace()
            AppSettings()
        }
    }

    // 辅助方法
    private fun getResultsFile(): File {
        val dir = getDataDirectory()
        return File(dir, JSON_FILE_NAME)
    }

    private fun getSettingsFile(): File {
        val dir = getDataDirectory()
        return File(dir, SETTINGS_FILE_NAME)
    }

    private fun getDataDirectory(): File {
        val dir = File(context.filesDir, "data")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getImageDirectory(): File {
        val dir = File(context.filesDir, "images")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getExportDirectory(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "exports")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
}
