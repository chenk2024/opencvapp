package com.tobacco.detection.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 相机管理器
 * 负责相机的初始化、图像捕获等功能
 */
class CameraManager(private val context: Context) {

    companion object {
        private const val TAG = "CameraManager"
    }

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)

    /**
     * 初始化相机
     */
    fun initializeCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onInitialized: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // 绑定相机用例
                bindCameraUseCases(lifecycleOwner, previewView)
                
                onInitialized()
            } catch (e: Exception) {
                Log.e(TAG, "Camera initialization failed", e)
                onError(e)
            }
        }, mainExecutor)
    }

    /**
     * 绑定相机用例
     */
    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = cameraProvider ?: return

        // 预览用例
        val preview = Preview.Builder()
            .setTargetResolution(android.util.Size(3264, 2448))
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        // 图像捕获用例
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetResolution(android.util.Size(3264, 2448))
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()

        // 选择后置相机
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        try {
            // 解除所有绑定
            provider.unbindAll()

            // 绑定用例到相机
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
            throw e
        }
    }

    /**
     * 捕获图像
     */
    suspend fun captureImage(): Bitmap? = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture ?: run {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        capture.takePicture(
            mainExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val bitmap = imageProxyToBitmap(imageProxy)
                        imageProxy.close()
                        continuation.resume(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to convert image to bitmap", e)
                        imageProxy.close()
                        continuation.resume(null)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Image capture failed", exception)
                    continuation.resume(null)
                }
            }
        )
    }

    /**
     * 将ImageProxy转换为Bitmap
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        if (bytes.isEmpty()) {
            return null
        }

        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        if (bitmap == null) {
            return null
        }

        // 根据旋转角度旋转图像
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            try {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                // 如果旋转后生成了新图，释放原图
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                }
                bitmap = rotatedBitmap
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rotate bitmap", e)
                return bitmap
            }
        }

        return bitmap
    }

    /**
     * 设置闪光灯模式
     * @param flashMode 闪光灯模式：FLASH_MODE_AUTO, FLASH_MODE_ON, FLASH_MODE_OFF
     */
    fun setFlashMode(flashMode: Int) {
        // 设置拍照时的闪光灯模式
        imageCapture?.flashMode = flashMode

        // 控制手电筒/LED灯立即亮起
        val torchOn = when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> true
            ImageCapture.FLASH_MODE_OFF -> false
            else -> false // 自动模式下不点亮手电筒
        }

        try {
            camera?.cameraControl?.enableTorch(torchOn)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set torch mode", e)
        }
    }

    /**
     * 开启/关闭自动对焦
     */
    fun enableAutoFocus(enable: Boolean, previewView: PreviewView? = null) {
        camera?.let { cam ->
            if (enable) {
                try {
                    val meteringPointFactory = previewView?.meteringPointFactory
                    if (meteringPointFactory != null) {
                        cam.cameraControl.startFocusAndMetering(
                            FocusMeteringAction.Builder(
                                meteringPointFactory.createPoint(0.5f, 0.5f)
                            ).build()
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Auto focus failed", e)
                }
            }
        }
    }

    /**
     * 释放相机资源
     */
    fun release() {
        cameraProvider?.unbindAll()
        camera = null
        imageCapture = null
        cameraProvider = null
    }

    /**
     * 缩放
     */
    fun zoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }
}
