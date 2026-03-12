package com.tobacco.detection.processing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.tobacco.detection.data.DetectionResult
import com.tobacco.detection.data.DetectionStatus
import com.tobacco.detection.data.PointData
import com.tobacco.detection.data.ProcessingConfig
import com.tobacco.detection.data.TobaccoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * 烟丝图像处理器
 * 实现图像处理、轮廓提取、宽度计算等功能
 * 支持直线和曲线形态的烟丝检测
 */
class TobaccoProcessor(private val config: ProcessingConfig) {

    companion object {
        private const val TAG = "TobaccoProcessor"
    }

    // 存储处理过程中的数据用于显示
    data class ProcessingData(
        val contours: List<List<Point>>,
        val widths: List<Pair<Point, Point>>, // 测量线的起点和终点
        val centerPoints: List<Point>,
        val boundingBoxes: List<RotatedRect>
    )

    private var lastProcessingData: ProcessingData? = null

    /**
     * 处理图像并返回检测结果
     */
    suspend fun processImage(bitmap: Bitmap): DetectionResult = withContext(Dispatchers.Default) {
        val startTime = System.currentTimeMillis()
        
        try {
            // 将Bitmap转换为OpenCV的Mat
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)
            
            // 图像预处理
            val processedMat = preprocessImage(srcMat)
            
            // 二值化
            val binaryMat = thresholdImage(processedMat)
            
            // 形态学操作去除噪声
            val morphMat = morphologicalOperation(binaryMat)
            
            // 轮廓提取
            val contours = extractContours(morphMat)
            
            // 过滤并处理轮廓
            val tobaccoInfos = processContours(contours, morphMat, srcMat)
            
            // 保存处理数据用于显示
            lastProcessingData = createProcessingData(contours, tobaccoInfos, morphMat)
            
            // 计算统计数据
            val result = calculateResults(tobaccoInfos, startTime)
            
            // 释放资源
            srcMat.release()
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
     * 获取最后处理的数据（用于显示轮廓）
     */
    fun getLastProcessingData(): ProcessingData? = lastProcessingData

    /**
     * 创建处理数据用于显示
     */
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
        
        // 生成测量线
        val widths = tobaccoInfos.take(10).mapIndexed { index, info ->
            val center = info.contourPoints.getOrNull(info.contourPoints.size / 2)
                ?: PointData(100.0, 100.0)
            val width = info.widthPixels
            
            Point(center.x - width / 2, center.y) to Point(center.x + width / 2, center.y)
        }
        
        return ProcessingData(
            contours = validContours,
            widths = widths,
            centerPoints = tobaccoInfos.mapNotNull { 
                it.contourPoints.getOrNull(it.contourPoints.size / 2)?.let { p -> Point(p.x, p.y) }
            },
            boundingBoxes = boundingBoxes
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
     * 三角形法二值化
     * 使用Otsu's方法实现自动阈值选择
     */
    private fun thresholdImage(grayMat: Mat): Mat {
        val binaryMat = Mat()
        Imgproc.threshold(grayMat, binaryMat, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        return binaryMat
    }

    /**
     * 形态学操作：闭运算填补空隙 + 开运算去除噪点
     */
    private fun morphologicalOperation(binaryMat: Mat): Mat {
        val kernel = Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,
            Size(config.morphKernelSize.toDouble(), config.morphKernelSize.toDouble())
        )
        
        // 闭运算 - 填补烟丝内部的空洞
        val morphMat = Mat()
        Imgproc.morphologyEx(binaryMat, morphMat, Imgproc.MORPH_CLOSE, kernel)
        
        // 开运算 - 去除小的噪点
        val resultMat = Mat()
        Imgproc.morphologyEx(morphMat, resultMat, Imgproc.MORPH_OPEN, kernel)
        
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
     * 处理轮廓，计算每根烟丝的宽度
     * 使用多种方法综合计算：
     * 1. 最小外接矩形（适合直线烟丝）
     * 2. 距离变换分析（适合各种形态）
     * 3. 投影法（适合曲线烟丝）
     */
    private fun processContours(contours: List<MatOfPoint>, binaryMat: Mat, srcMat: Mat): List<TobaccoInfo> {
        val tobaccoInfos = mutableListOf<TobaccoInfo>()
        
        for ((index, contour) in contours.withIndex()) {
            // 过滤太小的轮廓
            val area = Imgproc.contourArea(contour)
            if (area < config.minContourArea || area > config.maxContourArea) {
                continue
            }
            
            // 获取最小外接矩形
            val rect = Imgproc.minAreaRect(MatOfPoint2f(*contour.toArray()))
            
            // 计算烟丝宽度（多种方法融合）
            val widthInfo = calculateTobaccoWidth(contour, rect, binaryMat)
            
            if (widthInfo != null && widthInfo.first > 0) {
                val widthMm = widthInfo.first * config.pixelToMmRatio
                tobaccoInfos.add(
                    TobaccoInfo(
                        index = index,
                        widthPixels = widthInfo.first,
                        widthMm = widthMm,
                        contourPoints = contour.toList().map { PointData(it.x.toDouble(), it.y.toDouble()) }
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
        
        val widthsMm = tobaccoInfos.map { it.widthMm }
        val averageWidth = widthsMm.average()
        val minWidth = widthsMm.minOrNull() ?: 0.0
        val maxWidth = widthsMm.maxOrNull() ?: 0.0
        
        // 计算标准差
        val variance = widthsMm.map { (it - averageWidth).pow(2) }.average()
        val stdDeviation = sqrt(variance)
        
        return DetectionResult(
            timestamp = System.currentTimeMillis(),
            tobaccoCount = tobaccoInfos.size,
            averageWidthMm = averageWidth,
            minWidthMm = minWidth,
            maxWidthMm = maxWidth,
            stdDeviation = stdDeviation,
            individualWidths = widthsMm,
            detectionTimeMs = System.currentTimeMillis() - startTime,
            status = DetectionStatus.SUCCESS
        )
    }

    /**
     * 创建带有轮廓标记的Bitmap
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
        
        // 绘制测量线的画笔 - 红色
        val measurePaint = Paint().apply {
            color = Color.RED
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
            
            // 绘制测量线
            for ((start, end) in processingData.widths) {
                canvas.drawLine(
                    start.x.toFloat(), start.y.toFloat(),
                    end.x.toFloat(), end.y.toFloat(),
                    measurePaint
                )
            }
        }
        
        // 显示检测结果信息
        val infoText = "检测到 ${result.tobaccoCount} 根烟丝"
        val avgText = String.format("平均宽度: %.4f mm", result.averageWidthMm)
        val minText = String.format("最小: %.4f mm", result.minWidthMm)
        val maxText = String.format("最大: %.4f mm", result.maxWidthMm)
        
        // 半透明背景
        val bgPaint = Paint().apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRect(10f, 10f, 280f, 110f, bgPaint)
        
        canvas.drawText(infoText, 20f, 35f, textPaint)
        textPaint.textSize = 20f
        canvas.drawText(avgText, 20f, 58f, textPaint)
        canvas.drawText(minText, 20f, 80f, textPaint)
        canvas.drawText(maxText, 20f, 102f, textPaint)
        
        return markedBitmap
    }
}
