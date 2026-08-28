package com.ousuan.smartbutler.ui.alert

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.BudgetPrefs
import com.ousuan.smartbutler.data.Transaction
import com.ousuan.smartbutler.databinding.FragmentAlertBinding
import com.ousuan.smartbutler.util.ExpenseAnalyzer
import com.ousuan.smartbutler.util.fmtMoney
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 功能③ 预警 + IF 线 + 年月日视图切换。
 * 日/月/年三视图汇总、余额预警（低于月预算 20% 红色警告）、IF 线模拟。
 */
class AlertFragment : Fragment() {

    private var _binding: FragmentAlertBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy {
        (requireActivity().application as SmartButlerApp).repository
    }

    private val monthFmt = DateTimeFormatter.ofPattern("yyyy-MM")
    private var viewType = 0 // 0=日 1=月 2=年
    private var cursor = LocalDate.now()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlertBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toggleView.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                viewType = when (checkedId) {
                    R.id.btn_day -> 0
                    R.id.btn_month -> 1
                    else -> 2
                }
                loadData()
            }
        }
        binding.btnPrev.setOnClickListener { moveCursor(-1) }
        binding.btnNext.setOnClickListener { moveCursor(1) }
        loadData()
    }

    /**
     * Fragment 重新可见时重新加载数据：
     * - onResume：初次显示、或 Activity 从后台回到前台
     * - onHiddenChanged：MainActivity 用 show/hide 切换底部 Tab，
     *   hide/show 不会触发 onResume，必须在此回调里刷新，否则录完新消费切回本页数据不更新
     */
    override fun onResume() {
        super.onResume()
        loadData()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) loadData()
    }

    private fun moveCursor(delta: Int) {
        cursor = when (viewType) {
            0 -> cursor.plusDays(delta.toLong())
            1 -> cursor.plusMonths(delta.toLong())
            else -> cursor.plusYears(delta.toLong())
        }
        loadData()
    }

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val all = repository.getAll()
            val range = when (viewType) {
                0 -> repository.getByDate(cursor.toString())
                1 -> repository.getByMonth(cursor.format(monthFmt))
                else -> repository.getByYear(cursor.year.toString())
            }
            binding.tvRange.text = when (viewType) {
                0 -> cursor.toString()
                1 -> cursor.format(monthFmt)
                else -> cursor.year.toString()
            }

            // 汇总
            val allSummary = ExpenseAnalyzer.summarize(all)
            val rangeSummary = ExpenseAnalyzer.summarize(range)
            binding.tvSummary.text =
                "范围支出 ${fmtMoney(rangeSummary.expense)} 元 · 范围收入 ${fmtMoney(rangeSummary.income)} 元\n" +
                "今日余额（全部记录）${fmtMoney(allSummary.balance)} 元"

            // 余额预警：余额 < 月预算 20% → 红色警告
            val budgetTotal = BudgetPrefs.total(requireContext())
            if (budgetTotal > 0 && allSummary.balance < budgetTotal * 0.2) {
                binding.tvBalanceWarn.visibility = View.VISIBLE
                binding.tvBalanceWarn.text =
                    "⚠ 余额预警：当前余额 ${fmtMoney(allSummary.balance)} 元，" +
                    "已低于月预算（${fmtMoney(budgetTotal)} 元）的 20%，请留意支出！"
            } else {
                binding.tvBalanceWarn.visibility = View.GONE
            }

            // 分类汇总
            binding.catContainer.removeAllViews()
            val cats = ExpenseAnalyzer.categoryAmounts(range)
                .entries.sortedByDescending { it.value }
            if (cats.isEmpty()) {
                binding.catContainer.addView(simpleRow("该范围暂无记录"))
            } else {
                cats.forEach { (cat, amount) ->
                    binding.catContainer.addView(simpleRow("· $cat：${fmtMoney(amount)} 元"))
                }
            }

            // IF 线模拟列表
            binding.ifContainer.removeAllViews()
            if (range.isEmpty()) {
                binding.ifContainer.addView(simpleRow("暂无记录可模拟 IF 线"))
            } else {
                range.forEach { r -> binding.ifContainer.addView(ifRow(r, allSummary.balance)) }
            }
        }
    }

    private fun simpleRow(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    }

    /** 单条 IF 行：点按钮弹窗模拟"没有这笔消费"后的余额 */
    private fun ifRow(r: Transaction, balance: Double): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(
            TextView(requireContext()).apply {
                text = "${r.date} ${r.category} ${fmtMoney(r.amount)}"
                textSize = 14f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        val btn = Button(requireContext()).apply {
            text = "IF"
            setOnClickListener {
                // 支出：没有这笔则余额 += 金额；收入：没有这笔则余额 -= 金额
                val simulated = if (r.type == "收入") balance - r.amount else balance + r.amount
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("IF 线模拟")
                    .setMessage(
                        "如果当时没有这笔消费（${r.category} ${fmtMoney(r.amount)} 元），\n" +
                        "今天余额会是 ${fmtMoney(simulated)} 元。"
                    )
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
        row.addView(btn)
        return row
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
