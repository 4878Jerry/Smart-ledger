package com.ousuan.smartbutler.ui.home

import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.data.Transaction
import com.ousuan.smartbutler.databinding.ItemRecordBinding
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.fmtMoney

/**
 * 消费记录列表适配器。
 * 传入 onIfClick 时显示每行「IF」按钮（预警页使用），首页传 null 隐藏。
 */
class RecordAdapter(
    private val onIfClick: ((Transaction) -> Unit)? = null
) : RecyclerView.Adapter<RecordAdapter.VH>() {

    private val items = mutableListOf<Transaction>()

    fun submit(list: List<Transaction>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemRecordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    /** 供左滑删除回调获取当前项 */
    fun getItem(position: Int): Transaction = items[position]

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        val b = holder.binding
        // 重置左滑位移（复用 item / 删除取消后恢复原位）
        b.foreground.translationX = 0f

        b.tvCategory.text = if (t.payee.isBlank()) t.category else "${t.category} · ${t.payee}"
        b.tvNote.text = t.note
        b.tvDate.text = t.date
        b.tvAmount.text = "%s%s".format(
            if (t.type == "收入") "+" else "-", fmtMoney(t.amount)
        )
        b.tvAmount.setTextColor(
            ContextCompat.getColor(
                b.root.context,
                if (t.type == "收入") R.color.income else R.color.expense
            )
        )
        b.dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Categories.color(t.category))
        }

        onIfClick?.let { cb ->
            b.btnIf.visibility = View.VISIBLE
            b.btnIf.setOnClickListener { cb(t) }
        } ?: run {
            b.btnIf.visibility = View.GONE
        }
    }
}
