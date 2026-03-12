package com.tobacco.detection.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tobacco.detection.R
import com.tobacco.detection.data.DataManager
import com.tobacco.detection.data.DetectionResult
import com.tobacco.detection.databinding.ActivityHistoryBinding
import kotlinx.coroutines.launch

/**
 * 历史记录Activity
 * 显示所有检测结果
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private lateinit var dataManager: DataManager
    private lateinit var adapter: HistoryAdapter
    
    private val results = mutableListOf<DetectionResult>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        dataManager = DataManager(this)
        
        setupRecyclerView()
        setupClickListeners()
        loadHistory()
    }

    /**
     * 设置RecyclerView
     */
    private fun setupRecyclerView() {
        adapter = HistoryAdapter(results) { result ->
            // 点击某条记录可以查看详情（这里可以扩展）
            Toast.makeText(this, "检测时间: ${result.detectionTimeMs}ms", Toast.LENGTH_SHORT).show()
        }
        
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
    }

    /**
     * 设置点击事件
     */
    private fun setupClickListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }
        
        binding.btnExport.setOnClickListener {
            exportToExcel()
        }
    }

    /**
     * 加载历史记录
     */
    private fun loadHistory() {
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val loadedResults = dataManager.loadResults()
            
            results.clear()
            results.addAll(loadedResults.reversed()) // 最新的在前面
            
            binding.progressBar.visibility = View.GONE
            
            if (results.isEmpty()) {
                binding.emptyLayout.visibility = View.VISIBLE
                binding.recyclerView.visibility = View.GONE
            } else {
                binding.emptyLayout.visibility = View.GONE
                binding.recyclerView.visibility = View.VISIBLE
                adapter.notifyDataSetChanged()
            }
        }
    }

    /**
     * 导出到Excel
     */
    private fun exportToExcel() {
        if (results.isEmpty()) {
            Toast.makeText(this, "没有数据可导出", Toast.LENGTH_SHORT).show()
            return
        }
        
        binding.progressBar.visibility = View.VISIBLE
        
        lifecycleScope.launch {
            val file = dataManager.exportToExcel(results)
            
            binding.progressBar.visibility = View.GONE
            
            if (file != null) {
                Toast.makeText(this@HistoryActivity, "导出成功: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                
                // 可以选择打开文件
                // val intent = Intent(Intent.ACTION_VIEW)
                // intent.setDataAndType(Uri.fromFile(file), "application/vnd.ms-excel")
                // startActivity(intent)
            } else {
                Toast.makeText(this@HistoryActivity, "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
