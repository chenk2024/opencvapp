package com.tobacco.detection.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.tobacco.detection.R
import java.io.File

/**
 * 图像对比 Activity
 * 用于显示原图和处理后的检测图
 * 上方显示原图，下方显示处理后的图片（带标记）
 */
class ImageCompareActivity : AppCompatActivity() {

    private lateinit var ivOriginal: ImageView
    private lateinit var ivProcessed: ImageView
    private lateinit var tvOriginalTitle: TextView
    private lateinit var tvProcessedTitle: TextView

    companion object {
        const val EXTRA_ORIGINAL_PATH = "extra_original_path"
        const val EXTRA_PROCESSED_PATH = "extra_processed_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_compare)

        // 初始化视图
        ivOriginal = findViewById(R.id.ivOriginal)
        ivProcessed = findViewById(R.id.ivProcessed)
        tvOriginalTitle = findViewById(R.id.tvOriginalTitle)
        tvProcessedTitle = findViewById(R.id.tvProcessedTitle)

        // 设置标题
        tvOriginalTitle.text = getString(R.string.original_image)
        tvProcessedTitle.text = getString(R.string.processed_image)

        // 获取传入的文件路径
        val originalPath = intent.getStringExtra(EXTRA_ORIGINAL_PATH)
        val processedPath = intent.getStringExtra(EXTRA_PROCESSED_PATH)

        // 加载并显示图像
        try {
            if (originalPath != null && File(originalPath).exists()) {
                val originalBitmap = BitmapFactory.decodeFile(originalPath)
                if (originalBitmap != null) {
                    ivOriginal.setImageBitmap(originalBitmap)
                }
            }

            if (processedPath != null && File(processedPath).exists()) {
                val processedBitmap = BitmapFactory.decodeFile(processedPath)
                if (processedBitmap != null) {
                    ivProcessed.setImageBitmap(processedBitmap)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "加载图像失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        // 返回按钮
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            finish()
        }
    }
}
