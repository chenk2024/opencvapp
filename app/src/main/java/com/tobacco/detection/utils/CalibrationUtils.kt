package com.tobacco.detection.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * 校准工具类
 * 用于校准像素与实际距离的比例
 */
class CalibrationUtils(private val context: Context) {

    companion object {
        private const val TAG = "CalibrationUtils"
        
        // 标准校准块的宽度（毫米）
        const val STANDARD_BLOCK_WIDTH_MM = 1.0
    }

    /**
     * 使用已知宽度的参照物进行校准
     * @param bitmap 包含参照物的图像
     * @param knownWidthMm 参照物的已知宽度（毫米）
     * @return 像素与毫米的比例 (mm/px)
     */
    suspend fun calibrate(bitmap: android.graphics.Bitmap, knownWidthMm: Double): Double = withContext(Dispatchers.Default) {
        try {
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)
            
            // 预处理
            val grayMat = Mat()
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)
            
            // 高斯模糊
            Imgproc.GaussianBlur(grayMat, grayMat, Size(5.0, 5.0), 0.0)
            
            // 二值化
            val binaryMat = Mat()
            Imgproc.threshold(grayMat, binaryMat, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
            
            // 轮廓提取
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(binaryMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            
            // 找最大的轮廓（应该是校准块）
            var maxArea = 0.0
            var calibrationContour: MatOfPoint? = null
            
            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area > maxArea && area > 1000) {
                    maxArea = area
                    calibrationContour = contour
                }
            }
            
            val pixelRatio = if (calibrationContour != null) {
                // 使用边界框估算宽度
                val boundingRect = Imgproc.boundingRect(calibrationContour)
                val pixelWidth = boundingRect.width.toDouble()
                
                // 计算比例
                knownWidthMm / pixelWidth
            } else {
                Log.w(TAG, "No calibration object found")
                0.01 // 默认值
            }
            
            // 释放资源
            srcMat.release()
            grayMat.release()
            binaryMat.release()
            hierarchy.release()
            
            pixelRatio
        } catch (e: Exception) {
            Log.e(TAG, "Calibration failed", e)
            0.01 // 返回默认值
        }
    }

    /**
     * 使用棋盘格进行相机标定（更精确的方法）
     * @param images 多个角度拍摄的棋盘格图像
     * @return 相机内参矩阵
     */
    suspend fun cameraCalibration(images: List<android.graphics.Bitmap>): Mat? = withContext(Dispatchers.Default) {
        try {
            // 棋盘格参数
            val boardSize = Size(9.0, 6.0) // 9x6角点
            val objectPoints = mutableListOf<Point3>()
            val imagePoints = mutableListOf<Point>()
            
            // 生成3D坐标
            for (i in 0 until boardSize.height.toInt()) {
                for (j in 0 until boardSize.width.toInt()) {
                    objectPoints.add(Point3(j.toDouble(), i.toDouble(), 0.0))
                }
            }
            
            // 临时变量
            val corners = MatOfPoint2f()
            val grayMat = Mat()
            var imageSize: Size? = null
            
            for (bitmap in images) {
                val srcMat = Mat()
                Utils.bitmapToMat(bitmap, srcMat)
                Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)
                
                // 保存图像尺寸
                if (imageSize == null) {
                    imageSize = Size(bitmap.width.toDouble(), bitmap.height.toDouble())
                }
                
                // 找角点
                val found = Calib3d.findChessboardCorners(
                    grayMat, 
                    boardSize, 
                    corners,
                    Calib3d.CALIB_CB_ADAPTIVE_THRESH + Calib3d.CALIB_CB_NORMALIZE_IMAGE
                )
                
                if (found) {
                    // 亚像素精度
                    Imgproc.cornerSubPix(
                        grayMat, 
                        corners, 
                        Size(11.0, 11.0), 
                        Size(-1.0, -1.0),
                        TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 30, 0.001)
                    )
                    imagePoints.addAll(corners.toList())
                }
                
                srcMat.release()
            }
            
            grayMat.release()
            
            if (imagePoints.isEmpty() || imageSize == null) {
                Log.w(TAG, "No chessboard corners found")
                return@withContext null
            }
            
            // 标定
            val cameraMatrix = Mat(3, 3, CvType.CV_64F)
            val distCoeffs = Mat(5, 1, CvType.CV_64F)
            val rvecs = mutableListOf<Mat>()
            val tvecs = mutableListOf<Mat>()
            
            // 重复objectPoints以匹配imagePoints
            val allObjectPoints = mutableListOf<Point3>()
            repeat(imagePoints.size / objectPoints.size) {
                allObjectPoints.addAll(objectPoints)
            }
            
            val result = Calib3d.calibrateCamera(
                listOf(MatOfPoint3f(*allObjectPoints.toTypedArray())),
                listOf(MatOfPoint2f(*imagePoints.toTypedArray())),
                imageSize,
                cameraMatrix,
                distCoeffs,
                rvecs,
                tvecs
            )
            
            if (result < 0) {
                Log.w(TAG, "Calibration failed")
                return@withContext null
            }
            
            cameraMatrix
        } catch (e: Exception) {
            Log.e(TAG, "Camera calibration failed", e)
            null
        }
    }

    /**
     * 验证校准结果
     * @param pixelRatio 像素比例
     * @param measuredWidthMm 测量宽度
     * @param actualWidthMm 实际宽度
     * @return 误差百分比
     */
    fun verifyCalibration(pixelRatio: Double, measuredWidthMm: Double, actualWidthMm: Double): Double {
        val error = abs(measuredWidthMm - actualWidthMm) / actualWidthMm * 100
        return error
    }
}
