package com.tobacco.detection.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tobacco.detection.R
import com.tobacco.detection.camera.CameraManager
import com.tobacco.detection.data.AppSettings
import com.tobacco.detection.data.DataManager
import com.tobacco.detection.data.DetectionResult
import com.tobacco.detection.data.DetectionStatus
import com.tobacco.detection.data.ProcessingConfig
import com.tobacco.detection.databinding.ActivityMainBinding
import com.tobacco.detection.processing.TobaccoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主Activity
 * 负责欢迎界面、相机预览、图像捕获、结果显示等核心功能
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var dataManager: DataManager
    private lateinit var tobaccoProcessor: TobaccoProcessor
    
    private var currentSettings: AppSettings = AppSettings()
    private var lastCaptureBitmap: Bitmap? = null
    private var lastDetectionResult: DetectionResult? = null
    
    // 闪光灯模式
    private var flashMode = ImageCapture.FLASH_MODE_AUTO
    
    // 自动拍摄定时器
    private var autoCaptureHandler: Handler? = null
    private var autoCaptureRunnable: Runnable? = null
    private var countdownSeconds: Int = 0

    // 权限请求Launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeCamera()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
            showWelcomeLayout()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 初始化组件
        cameraManager = CameraManager(this)
        dataManager = DataManager(this)
        tobaccoProcessor = TobaccoProcessor(ProcessingConfig())
        
        // 加载设置
        loadSettings()
        
        // 设置点击事件
        setupClickListeners()
        
        // 初始显示欢迎界面
        showWelcomeLayout()
    }

    /**
     * 加载应用设置
     */
    private fun loadSettings() {
        lifecycleScope.launch {
            currentSettings = dataManager.loadSettings()
            // 更新处理器配置
            tobaccoProcessor = TobaccoProcessor(
                ProcessingConfig(pixelToMmRatio = currentSettings.pixelToMmRatio)
            )
        }
    }

    /**
     * 设置点击事件
     */
    private fun setupClickListeners() {
        // 欢迎页面按钮
        binding.btnStartDetection.setOnClickListener {
            checkCameraPermissionAndStart()
        }
        
        binding.btnViewHistory.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        
        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
        
        // 检测页面按钮
        binding.btnCapture.setOnClickListener {
            captureImage()
        }
        
        binding.btnBack.setOnClickListener {
            stopAutoCapture()
            stopCameraAndShowWelcome()
        }
        
        binding.btnFlash.setOnClickListener {
            toggleFlash()
        }
        
        // 结果页面按钮
        binding.btnSaveResult.setOnClickListener {
            saveResult()
        }
        
        binding.btnUploadResult.setOnClickListener {
            uploadResult()
        }
        
        binding.btnNewDetection.setOnClickListener {
            newDetection()
        }
    }

    /**
     * 检查相机权限并启动
     */
    private fun checkCameraPermissionAndStart() {
        when {
            ContextCompat.checkSelfPermission(
                this, 
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                initializeCamera()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    /**
     * 初始化相机
     */
    private fun initializeCamera() {
        showDetectionLayout()
        
        try {
            cameraManager.initializeCamera(
                lifecycleOwner = this,
                previewView = binding.previewView,
                onInitialized = {
                    binding.tvStatus.text = getString(R.string.click_to_capture)
                    
                    // 如果开启了自动拍摄，则启动
                    if (currentSettings.autoCapture) {
                        startAutoCapture()
                    }
                },
                onError = { e ->
                    Toast.makeText(this, "${getString(R.string.camera_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
                    showWelcomeLayout()
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "${getString(R.string.camera_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
            showWelcomeLayout()
        }
    }

    /**
     * 启动自动拍摄
     */
    private fun startAutoCapture() {
        stopAutoCapture() // 先停止之前的
        
        countdownSeconds = currentSettings.captureDelaySeconds
        
        autoCaptureHandler = Handler(Looper.getMainLooper())
        autoCaptureRunnable = object : Runnable {
            override fun run() {
                if (countdownSeconds > 0) {
                    binding.tvInstruction.text = "$countdownSeconds 秒后自动拍摄..."
                    countdownSeconds--
                    autoCaptureHandler?.postDelayed(this, 1000)
                } else {
                    binding.tvInstruction.text = getString(R.string.detecting)
                    captureImage()
                    // 拍摄完成后继续下一次自动拍摄
                    if (currentSettings.autoCapture) {
                        countdownSeconds = currentSettings.captureDelaySeconds
                        autoCaptureHandler?.postDelayed(this, 2000) // 2秒后开始下一次倒计时
                    }
                }
            }
        }
        
        // 开始倒计时
        binding.tvInstruction.text = "$countdownSeconds 秒后自动拍摄..."
        countdownSeconds--
        autoCaptureHandler?.postDelayed(autoCaptureRunnable!!, 1000)
    }

    /**
     * 停止自动拍摄
     */
    private fun stopAutoCapture() {
        autoCaptureRunnable?.let {
            autoCaptureHandler?.removeCallbacks(it)
        }
        autoCaptureHandler = null
        autoCaptureRunnable = null
    }

    /**
     * 拍摄图像
     */
    private fun captureImage() {
        binding.btnCapture.isEnabled = false
        binding.tvStatus.text = getString(R.string.detecting)
        
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                cameraManager.captureImage()
            }
            
            if (bitmap != null) {
                lastCaptureBitmap = bitmap
                processImage(bitmap)
            } else {
                Toast.makeText(this@MainActivity, R.string.camera_error, Toast.LENGTH_SHORT).show()
                binding.btnCapture.isEnabled = true
                binding.tvStatus.text = getString(R.string.click_to_capture)
                
                // 自动拍摄模式下继续倒计时
                if (currentSettings.autoCapture) {
                    startAutoCapture()
                }
            }
        }
    }

    /**
     * 处理图像
     */
    private fun processImage(bitmap: Bitmap) {
        showLoading(getString(R.string.detecting))
        
        lifecycleScope.launch {
            try {
                val result = tobaccoProcessor.processImage(bitmap)
                lastDetectionResult = result
                
                // 生成带标记的图像
                val markedBitmap = tobaccoProcessor.createMarkedBitmap(bitmap, result)
                
                // 保存带标记的图像
                launch(Dispatchers.IO) {
                    dataManager.saveImage(markedBitmap)
                }
                
                hideLoading()
                showResult(result, markedBitmap)
            } catch (e: Exception) {
                hideLoading()
                Toast.makeText(this@MainActivity, "${getString(R.string.processing_error)}: ${e.message}", Toast.LENGTH_SHORT).show()
                binding.btnCapture.isEnabled = true
                binding.tvStatus.text = getString(R.string.click_to_capture)
                
                // 自动拍摄模式下继续
                if (currentSettings.autoCapture) {
                    startAutoCapture()
                }
            }
        }
    }

    /**
     * 显示结果
     */
    private fun showResult(result: DetectionResult, markedBitmap: Bitmap? = null) {
        if (result.status == DetectionStatus.SUCCESS) {
            binding.tvResultTitle.text = getString(R.string.detection_complete)
            binding.tvResultTitle.setTextColor(getColor(R.color.success))
            
            binding.tvAverageWidth.text = String.format("%.4f mm", result.averageWidthMm)
            binding.tvTobaccoCount.text = result.tobaccoCount.toString()
            binding.tvMinWidth.text = String.format("%.4f mm", result.minWidthMm)
            binding.tvMaxWidth.text = String.format("%.4f mm", result.maxWidthMm)
            binding.tvDetectionTime.text = getString(R.string.detection_time) + ": ${String.format("%.1f", result.detectionTimeMs / 1000.0)} " + getString(R.string.seconds)
        } else {
            binding.tvResultTitle.text = getString(R.string.detection_failed)
            binding.tvResultTitle.setTextColor(getColor(R.color.error))
            
            binding.tvAverageWidth.text = "--"
            binding.tvTobaccoCount.text = "0"
            binding.tvMinWidth.text = "--"
            binding.tvMaxWidth.text = "--"
            binding.tvDetectionTime.text = ""
        }
        
        binding.resultLayout.visibility = View.VISIBLE
        
        // 自动拍摄模式下，显示结果后自动开始下一次检测
        if (currentSettings.autoCapture) {
            binding.btnSaveResult.postDelayed({
                if (binding.resultLayout.visibility == View.VISIBLE) {
                    newDetection()
                }
            }, 3000) // 3秒后自动开始新检测
        }
    }

    /**
     * 保存结果
     */
    private fun saveResult() {
        val result = lastDetectionResult ?: return
        
        lifecycleScope.launch {
            // 保存结果
            val success = dataManager.saveResult(result)
            
            if (success) {
                Toast.makeText(this@MainActivity, R.string.save_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 上传结果
     */
    private fun uploadResult() {
        val result = lastDetectionResult ?: return
        
        lifecycleScope.launch {
            showLoading("上传中...")
            
            val success = withContext(Dispatchers.IO) {
                val imagePath = lastCaptureBitmap?.let { bitmap ->
                    dataManager.saveImage(bitmap)
                }
                
                if (imagePath != null) {
                    dataManager.uploadWithImage(result, imagePath, currentSettings.apiUrl)
                } else {
                    dataManager.uploadResult(result, currentSettings.apiUrl)
                }
            }
            
            hideLoading()
            
            if (success) {
                Toast.makeText(this@MainActivity, R.string.upload_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, R.string.network_error, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 新建检测
     */
    private fun newDetection() {
        binding.resultLayout.visibility = View.GONE
        lastDetectionResult = null
        lastCaptureBitmap?.recycle()
        lastCaptureBitmap = null
        binding.btnCapture.isEnabled = true
        binding.tvStatus.text = getString(R.string.click_to_capture)
        
        // 根据设置决定是否自动拍摄
        if (currentSettings.autoCapture) {
            startAutoCapture()
        }
    }

    /**
     * 切换闪光灯
     */
    private fun toggleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        cameraManager.setFlashMode(flashMode)
        
        val flashText = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "自动"
            ImageCapture.FLASH_MODE_ON -> "开启"
            else -> "关闭"
        }
        binding.tvStatus.text = "闪光灯: $flashText"
    }

    /**
     * 显示设置对话框
     */
    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        
        // 填充当前设置值
        dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPixelRatio)?.setText(
            currentSettings.pixelToMmRatio.toString()
        )
        dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etApiUrl)?.setText(
            currentSettings.apiUrl
        )
        dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTobaccoCount)?.setText(
            currentSettings.expectedTobaccoCount.toString()
        )
        dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoCapture)?.isChecked =
            currentSettings.autoCapture
        dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSaveImage)?.isChecked =
            currentSettings.saveImages
        
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("设置")
            .setView(dialogView)
            .setPositiveButton(R.string.confirm) { _, _ ->
                // 保存设置
                val pixelRatio = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPixelRatio)?.text.toString().toDoubleOrNull() ?: 0.01
                val apiUrl = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etApiUrl)?.text.toString() ?: ""
                val tobaccoCount = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTobaccoCount)?.text.toString().toIntOrNull() ?: 30
                val autoCapture = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoCapture)?.isChecked ?: false
                val saveImage = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSaveImage)?.isChecked ?: true
                
                currentSettings = AppSettings(
                    apiUrl = apiUrl,
                    autoCapture = autoCapture,
                    pixelToMmRatio = pixelRatio,
                    expectedTobaccoCount = tobaccoCount,
                    saveImages = saveImage
                )
                
                // 保存到文件
                lifecycleScope.launch {
                    dataManager.saveSettings(currentSettings)
                    // 更新处理器配置
                    tobaccoProcessor = TobaccoProcessor(ProcessingConfig(pixelToMmRatio = currentSettings.pixelToMmRatio))
                }
                
                Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        
        dialog.show()
    }

    /**
     * 停止相机并显示欢迎界面
     */
    private fun stopCameraAndShowWelcome() {
        cameraManager.release()
        showWelcomeLayout()
    }

    /**
     * 显示欢迎界面
     */
    private fun showWelcomeLayout() {
        binding.welcomeLayout.visibility = View.VISIBLE
        binding.detectionLayout.visibility = View.GONE
        binding.resultLayout.visibility = View.GONE
        binding.loadingLayout.visibility = View.GONE
    }

    /**
     * 显示检测界面
     */
    private fun showDetectionLayout() {
        binding.welcomeLayout.visibility = View.GONE
        binding.detectionLayout.visibility = View.VISIBLE
        binding.resultLayout.visibility = View.GONE
        binding.loadingLayout.visibility = View.GONE
    }

    /**
     * 显示加载中
     */
    private fun showLoading(message: String) {
        binding.tvLoadingText.text = message
        binding.loadingLayout.visibility = View.VISIBLE
    }

    /**
     * 隐藏加载中
     */
    private fun hideLoading() {
        binding.loadingLayout.visibility = View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAutoCapture()
        cameraManager.release()
        lastCaptureBitmap?.recycle()
    }
}
