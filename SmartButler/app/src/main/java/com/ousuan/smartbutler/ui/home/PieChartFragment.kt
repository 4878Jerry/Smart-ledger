package com.ousuan.smartbutler.ui.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.PercentFormatter
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.Transaction
import com.ousuan.smartbutler.databinding.FragmentPieBinding
import com.ousuan.smartbutler.ui.widget.BarChartView
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.DateUtils
import com.ousuan.smartbutler.util.ExpenseAnalyzer
import com.ousuan.smartbutler.util.fmtMoney
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * 「统计分析」页（ViewPager2 第一页）
 *
 * 设计参考：iOS 风格的完整统计面板
 * - 顶部：标题 + 副标题 + 圆形头像
 * - 卡片 1：环形饼图（中心总支出）+ 右侧自定义图例
 * - 卡片 2：近 7 天柱状图（青绿/橙/红三色，峰值红警示）
 * - 卡片 3：AI 智能建议卡（人格 + 多行建议 + 可选风险提示）
 */
class PieChartFragment : Fragment() {

    private var _binding: FragmentPieBinding? = null
    private val binding get() = _binding!!

    private val repository by lazy {
        (requireActivity().application as SmartButlerApp).repository
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPieBinding.inflate(inflater, container, false)

        // 饼图：环形 + 隐藏内置图例
        binding.pieChart.apply {
            description.isEnabled = false
            legend.isEnabled = false
            setUsePercentValues(true)
            setEntryLabelColor(R.color.text_secondary)
            setEntryLabelTextSize(11f)
            holeRadius = 55f
            transparentCircleRadius = 58f
            isDrawHoleEnabled = true
            setHoleColor(Color.WHITE)
            setTransparentCircleColor(Color.WHITE)
            setTransparentCircleAlpha(110)
            isRotationEnabled = false
            isHighlightPerTapEnabled = false
            setDrawCenterText(false)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repository.allTransactions.collect { records ->
                render(records)
            }
        }
        return binding.root
    }

    private fun render(records: List<Transaction>) {
        val monthPrefix = DateUtils.nowMonthPrefix()
        val monthRecords = records.filter { it.date.startsWith(monthPrefix) }
        val cats = ExpenseAnalyzer.categoryAmounts(monthRecords)
        val total = cats.values.sum()

        // 空状态：没有任何支出记录
        if (cats.isEmpty() || total <= 0) {
            binding.llEmpty.visibility = View.VISIBLE
            binding.scrollRoot.visibility = View.GONE
            return
        }
        binding.llEmpty.visibility = View.GONE
        binding.scrollRoot.visibility = View.VISIBLE

        renderPie(cats)
        binding.tvTotalExpense.text = "¥${fmtMoney(total)}"

        renderLegend(cats, total)
        renderBarChart(records)
        renderAiAdvice(cats, total, monthRecords)
    }

    /** 饼图：分类占比，按比例渲染，隐藏内置图例与百分比文字（用自定义图例） */
    private fun renderPie(cats: Map<String, Double>) {
        val entries = cats.entries.map { PieEntry(it.value.toFloat(), it.key) }
        val dataSet = PieDataSet(entries, "").apply {
            colors = cats.keys.map { Categories.color(it) }
            sliceSpace = 2f
            valueTextSize = 0f                 // 隐藏内置百分比（用自定义图例显示）
            valueTextColor = Color.TRANSPARENT
            setDrawValues(false)
        }
        binding.pieChart.data = PieData(dataSet).apply {
            setValueFormatter(PercentFormatter(binding.pieChart))
        }
        binding.pieChart.invalidate()
    }

    /** 右侧图例：色点 + 类别名 + 百分比 + 金额（按占比降序） */
    private fun renderLegend(cats: Map<String, Double>, total: Double) {
        binding.llLegend.removeAllViews()
        val sorted = cats.entries.sortedByDescending { it.value }
        sorted.forEach { (name, amount) ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            // 圆点（动态颜色）
            val dot = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Categories.color(name))
                }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
            }
            // 类别名（按内容宽度，紧贴右侧数值列）
            val tvName = TextView(requireContext()).apply {
                text = name
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(8)
                    marginEnd = dp(16)
                }
            }
            // 右侧：百分比（深）+ 金额（彩色加粗），整体靠左紧贴类别名
            val pct = if (total > 0) (amount / total * 100).toInt() else 0
            val right = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.START
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val tvPct = TextView(requireContext()).apply {
                text = "$pct%"
                setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                textSize = 11f
            }
            val tvAmt = TextView(requireContext()).apply {
                text = "¥${fmtMoney(amount)}"
                setTextColor(Categories.color(name))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            right.addView(tvPct)
            right.addView(tvAmt)
            row.addView(dot)
            row.addView(tvName)
            row.addView(right)
            binding.llLegend.addView(row)
        }
    }

    /** 近 7 天柱状图：青绿为主，≥ 平均值的柱子用橙，最高柱用红（峰值） */
    private fun renderBarChart(records: List<Transaction>) {
        val today = LocalDate.now()
        val last7 = (0..6).map { offset ->
            val date = today.minusDays((6 - offset).toLong()).toString()
            val dayLabel = today.minusDays((6 - offset).toLong())
                .dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.SIMPLIFIED_CHINESE)
            date to dayLabel
        }
        val amounts = last7.map { (date, _) ->
            records.filter { it.date == date && it.type == "支出" }.sumOf { it.amount }.toFloat()
        }
        val maxVal = amounts.maxOrNull() ?: 0f
        val avgVal = if (amounts.any { it > 0 }) amounts.filter { it > 0 }.average().toFloat() else 0f

        val bars = last7.mapIndexed { idx, (_, label) ->
            val v = amounts[idx]
            val color = when {
                v == 0f -> Color.parseColor("#CFD8DC")     // 空值：浅灰
                v == maxVal && v > avgVal * 1.4f -> BarChartView.COLOR_PEAK   // 峰值红
                v >= avgVal -> BarChartView.COLOR_ACCENT  // 高于均值：橙
                else -> BarChartView.COLOR_NORMAL          // 正常：青绿
            }
            BarChartView.Bar(label, v, color)
        }
        binding.barChart.setData(bars)
    }

    /** AI 智能建议：人格 + 多行建议 + 可选风险提示 */
    private fun renderAiAdvice(cats: Map<String, Double>, total: Double, monthRecords: List<Transaction>) {
        val personality = ExpenseAnalyzer.personalityTag(monthRecords)
        binding.tvAiTitle.text = "智能建议 · 消费人格：$personality"

        val lines = mutableListOf<String>()
        val top = cats.entries.maxByOrNull { it.value }
        if (top != null && total > 0) {
            val pct = (top.value / total * 100).toInt()
            // 按分类给具体建议
            val suggestion = when (top.key) {
                "餐饮" -> "本周外卖建议不超过 ¥200"
                "交通" -> "短途可多选公交或骑行"
                "购物" -> "下单前设置 24 小时冷静期"
                "娱乐" -> "可试试免费的公园散步或图书馆"
                "居住" -> "检查房租/水电是否有优化空间"
                "医疗" -> "定期体检比临时就医更划算"
                "教育" -> "投资自己是最好的复利"
                "通讯" -> "对比套餐，必要时降到更便宜的档位"
                "社交人情" -> "礼尚往来要量力而行"
                "旅行" -> "提前做预算，避免超支影响生活"
                else -> "适当控制，保持健康占比"
            }
            lines.add("本月${top.key}占比偏高（$pct%），$suggestion")
        }

        val income = monthRecords.filter { it.type == "收入" }.sumOf { it.amount }
        if (income > 0) {
            val rate = ((income - total) / income * 100).toInt().coerceAtLeast(0)
            lines.add("当前储蓄率约 $rate%，继续保持")
        }
        if (lines.isEmpty()) {
            lines.add("本月消费结构比较均衡，继续保持！")
        }
        binding.tvAiBody.text = lines.joinToString("\n")

        // 风险提示：top 占比 > 45% 时显示
        if (top != null && total > 0 && top.value / total > 0.45) {
            val pct = (top.value / total * 100).toInt()
            binding.llAiRisk.visibility = View.VISIBLE
            binding.tvAiRisk.text = "本月${top.key}支出占比已达 $pct%，月底可能超支，建议尽快调整"
        } else {
            binding.llAiRisk.visibility = View.GONE
        }
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}