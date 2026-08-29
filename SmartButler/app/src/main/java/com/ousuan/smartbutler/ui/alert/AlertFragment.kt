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
import com.ousuan.smartbutler.model.MascotLook
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.ExpenseAnalyzer
import com.ousuan.smartbutler.util.MascotManager
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

    /** 当前是否有余额预警（决定小鸥表情） */
    private var warningState = false

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

        // 小鸥：分层渲染，跟随全局换装，并根据预警状态切换表情
        MascotManager.observe(mascotListener)
        refreshMascot()

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

            // 余额预警：余额 < 月预算 20% → 红色警告，小鸥切换为「提醒」表情
            val budgetTotal = BudgetPrefs.total(requireContext())
            warningState = budgetTotal > 0 && allSummary.balance < budgetTotal * 0.2
            refreshMascot()
            if (warningState) {
                binding.tvBalanceWarn.visibility = View.VISIBLE
                binding.tvBalanceWarn.text =
                    "⚠ 余额预警：当前余额 ${fmtMoney(allSummary.balance)} 元，" +
                    "已低于月预算（${fmtMoney(budgetTotal)} 元）的 20%，请留意支出！"
                binding.tvMascotSay.text = "小鸥提醒你：钱袋子告急啦！"
                binding.tvMascotSaySub.text = "减少非必要支出，守住预算底线"
            } else {
                binding.tvBalanceWarn.visibility = View.GONE
                binding.tvMascotSay.text = "小鸥帮你盯紧钱包"
                binding.tvMascotSaySub.text = "设置月度预算，消费更安心"
            }

            // 分类汇总：色点 + 类别名 + 金额(占比)
            binding.catContainer.removeAllViews()
            val cats = ExpenseAnalyzer.categoryAmounts(range)
                .entries.sortedByDescending { it.value }
            val catTotal = cats.sumOf { it.value }.coerceAtLeast(0.01)
            if (cats.isEmpty()) {
                binding.catContainer.addView(simpleRow("该范围暂无记录"))
            } else {
                cats.forEach { (cat, amount) ->
                    val row = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(6), 0, dp(6))
                    }
                    // 圆点
                    val dot = View(requireContext()).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(Categories.color(cat))
                        }
                        layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
                    }
                    // 类别名（占剩余宽度）
                    val tvName = TextView(requireContext()).apply {
                        text = cat
                        textSize = 13f
                        setTextColor(requireContext().getColor(R.color.text_primary))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { marginStart = dp(8) }
                    }
                    // 占比（彩色加粗）+ 金额
                    val pct = (amount / catTotal * 100).toInt()
                    val rightWrap = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val tvPct = TextView(requireContext()).apply {
                        text = "$pct%"
                        textSize = 11f
                        setTextColor(Categories.color(cat))
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    val tvAmt = TextView(requireContext()).apply {
                        text = "  ¥${fmtMoney(amount)}"
                        textSize = 13f
                        setTextColor(requireContext().getColor(R.color.text_primary))
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                    rightWrap.addView(tvPct)
                    rightWrap.addView(tvAmt)
                    row.addView(dot)
                    row.addView(tvName)
                    row.addView(rightWrap)
                    binding.catContainer.addView(row)
                }
            }

            // IF 线模拟列表：日期·类别 + 金额 + IF 按钮（chip 风格）
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

    /** 刷新预警页小鸥：分层渲染 + 按预警状态切换表情（预警→提醒，正常→默认） */
    private fun refreshMascot() {
        val base = MascotManager.currentLook()
        val rendered = if (warningState) {
            base.copy(faceId = "face_alert")
        } else {
            base.copy(faceId = "face_default")
        }
        _binding?.imgMascotAlert?.let { MascotManager.applyLookTo(it, rendered) }
    }

    /** 全局换装监听（随页面销毁注销） */
    private val mascotListener: (MascotLook) -> Unit = { refreshMascot() }

    /** 单条 IF 行：日期 + 类别(色点) + 金额 + IF 按钮（chip 风格） */
    private fun ifRow(r: Transaction, balance: Double): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        // 左侧信息：日期 + 类别行（带色点）+ 金额
        val leftWrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        // 日期 · 类别 行
        val catRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Categories.color(r.category))
            }
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
        }
        val tvDateCat = TextView(ctx).apply {
            text = "${r.date}  ${r.category}"
            textSize = 12f
            setTextColor(ctx.getColor(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(6) }
        }
        catRow.addView(dot)
        catRow.addView(tvDateCat)

        val tvAmount = TextView(ctx).apply {
            text = fmtMoney(r.amount)
            textSize = 14f
            setTextColor(ctx.getColor(if (r.type == "收入") R.color.income else R.color.expense))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        leftWrap.addView(catRow)
        leftWrap.addView(tvAmount)

        // IF 按钮（chip 风格：浅橙背景 + 橙色边框 + 橙字）
        val btn = TextView(ctx).apply {
            text = "IF ?"
            textSize = 12f
            setTextColor(0xFFC2571B.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_if_button)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            isClickable = true
            isFocusable = true
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
        row.addView(leftWrap)
        row.addView(btn)
        return row
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        MascotManager.removeObserver(mascotListener)
        super.onDestroyView()
        _binding = null
    }
}
