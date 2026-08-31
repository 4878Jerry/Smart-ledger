package com.ousuan.smartbutler.ui.home

import android.graphics.Canvas
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.databinding.FragmentListBinding
import com.ousuan.smartbutler.data.Transaction
import com.ousuan.smartbutler.util.MascotManager
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 列表视图：消费明细（按日期倒序，Flow 自动刷新），支持左滑删除 / 长按删除 / 点击编辑 */
class RecordListFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy {
        (requireActivity().application as SmartButlerApp).repository
    }

    private lateinit var adapter: RecordAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = RecordAdapter(
            onIfClick = null,
            onItemClick = { showEditDialog(it) },
            onLongClick = { confirmDelete(it) }
        )
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        setupSwipeDelete()

        // 空状态小鸥：分层渲染当前形象
        MascotManager.applyLookTo(binding.imgMascotList)

        viewLifecycleOwner.lifecycleScope.launch {
            repository.allTransactions.collect { records ->
                adapter.submit(records)
                binding.llEmpty.visibility =
                    if (records.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    /**
     * 左滑删除：滑动时内容层平移露出红色删除背景（垃圾桶图标），
     * 松手弹出确认对话框，确认后删除记录并 Toast 提示。
     */
    private fun setupSwipeDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position >= adapter.itemCount) {
                    adapter.notifyDataSetChanged()
                    return
                }
                val t = adapter.getItem(position)
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("删除记录")
                    .setMessage("确定要删除这条记录吗？")
                    .setPositiveButton("删除") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                repository.delete(t)
                                // Flow 会自动推送新列表刷新
                                Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(requireContext(), e.message ?: "删除失败", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .setNegativeButton("取消") { _, _ -> restore(position) }
                    .setOnCancelListener { restore(position) }
                    .show()
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                // 只移动内容层，露出底层的红色删除背景；不调用 super 避免整个 itemView 一起平移
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val fg = viewHolder.itemView.findViewById<View>(R.id.foreground)
                    fg.translationX = dX.coerceIn(-fg.width.toFloat(), 0f)
                    viewHolder.itemView.findViewById<View>(R.id.delete_bg).alpha =
                        (abs(dX) / fg.width).coerceIn(0f, 1f)
                    return
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.findViewById<View>(R.id.foreground).translationX = 0f
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.recyclerView)
    }

    /** 取消删除时恢复被滑开的行 */
    private fun restore(position: Int) {
        if (position in 0 until adapter.itemCount) {
            adapter.notifyItemChanged(position)
        }
    }

    /** 点击列表项 → 编辑记录（复用记账弹窗，预填原数据，保存走 DAO update） */
    private fun showEditDialog(t: Transaction) {
        AddTransactionDialog().show(requireContext(), repository, viewLifecycleOwner.lifecycleScope, t)
    }

    /** 长按列表项 → 删除确认对话框（与左滑删除同款交互） */
    private fun confirmDelete(t: Transaction) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("删除记录")
            .setMessage("确定要删除这条记录吗？")
            .setPositiveButton("删除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        repository.delete(t)
                        // Flow 会自动推送新列表刷新（图表/统计同样随 Flow 更新）
                        Toast.makeText(requireContext(), "已删除", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), e.message ?: "删除失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
