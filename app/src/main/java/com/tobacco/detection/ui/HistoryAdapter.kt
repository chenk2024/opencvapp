package com.tobacco.detection.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tobacco.detection.R
import com.tobacco.detection.data.DetectionResult
import com.tobacco.detection.data.DetectionStatus
import com.tobacco.detection.databinding.ItemHistoryBinding
import java.text.SimpleDateFormat
import java.util.*

/**
 * 历史记录列表适配器
 */
class HistoryAdapter(
    private val results: List<DetectionResult>,
    private val onItemClick: (DetectionResult) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    class ViewHolder(val binding: ItemHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val result = results[position]
        val context = holder.binding.root.context
        
        // 设置日期时间
        holder.binding.tvDateTime.text = dateFormat.format(Date(result.timestamp))
        
        // 设置状态
        holder.binding.tvStatus.text = when (result.status) {
            DetectionStatus.SUCCESS -> context.getString(R.string.success)
            DetectionStatus.FAILED -> context.getString(R.string.failed)
            DetectionStatus.PROCESSING -> context.getString(R.string.loading)
            DetectionStatus.CANCELLED -> "已取消"
        }
        holder.binding.tvStatus.setTextColor(
            context.getColor(
                when (result.status) {
                    DetectionStatus.SUCCESS -> R.color.success
                    DetectionStatus.FAILED -> R.color.error
                    else -> R.color.warning
                }
            )
        )
        
        // 设置平均宽度
        holder.binding.tvAverageWidth.text = String.format("%.4f mm", result.averageWidthMm)
        
        // 设置烟丝数量
        holder.binding.tvTobaccoCount.text = "${result.tobaccoCount}根"
        
        // 设置最小/最大宽度
        holder.binding.tvMinWidth.text = String.format("%.4f mm", result.minWidthMm)
        holder.binding.tvMaxWidth.text = String.format("%.4f mm", result.maxWidthMm)
        
        // 点击事件
        holder.binding.root.setOnClickListener {
            onItemClick(result)
        }
    }

    override fun getItemCount(): Int = results.size
}
