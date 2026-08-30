package com.ousuan.smartbutler.ui.budget

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ousuan.smartbutler.R
import com.ousuan.smartbutler.SmartButlerApp
import com.ousuan.smartbutler.data.BudgetPrefs
import com.ousuan.smartbutler.data.ProvinceFactors
import com.ousuan.smartbutler.databinding.FragmentBudgetBinding
import com.ousuan.smartbutler.util.Categories
import com.ousuan.smartbutler.util.fmtMoney
import kotlinx.coroutines.launch

/**
 * 功能② 预算规划：参照 C++ budget_planner.cpp。
 * 选择省份 + 城市等级（读取系数表）→ 输入月生活费总额 →
 * 滑块调整 6 个分类占比 → 生成方案并保存（供预警页使用）。
 */
class BudgetFragment : Fragment() {

    private var _binding: FragmentBudgetBinding? = null
    private val binding get() = _binding!!

    private val budgetCategories = Categories.BUDGET
    private val defaultWeights = intArrayOf(35, 8, 8, 6, 5, 38) // 餐饮/交通/购物/娱乐/学习/储蓄
    private val sliders = mutableListOf<SeekBar>()
    private val pctViews = mutableListOf<TextView>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBudgetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 省份下拉框（31 省系数表）
        val provNames = ProvinceFactors.provinces.map { it.first }
        binding.spProvince.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, provNames
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        // 城市等级下拉框（5 档系数表）
        val cityNames = ProvinceFactors.cityLevels.map { it.first }
        binding.spCity.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, cityNames
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spProvince.onItemSelectedListener = simpleSelect { updateFactor() }
        binding.spCity.onItemSelectedListener = simpleSelect { updateFactor() }

        buildSliders()

        binding.btnDefault.setOnClickListener {
            sliders.forEachIndexed { i, sb -> sb.progress = defaultWeights[i] }
        }
        binding.btnGenerate.setOnClickListener { generate() }

        updateFactor()
        // 打开页面时从本地读取已保存预算并回填界面（Room 优先，未登录回退 SharedPreferences）
        loadSavedBudget()
    }

    private fun simpleSelect(action: () -> Unit) = object : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) = action()
        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
    }

    /** 动态构建 6 个分类滑块（分类色点 + 彩色标签 + 彩色进度条） */
    private fun buildSliders() {
        budgetCategories.forEachIndexed { i, name ->
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            // 分类色点 + 彩色标签
            val labelWrap = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val dot = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Categories.color(name))
                }
                layoutParams = LinearLayout.LayoutParams(dp(9), dp(9))
            }
            val label = TextView(requireContext()).apply {
                text = name
                textSize = 13f
                setTextColor(Categories.color(name))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            }
            labelWrap.addView(dot)
            labelWrap.addView(label)
            row.addView(labelWrap)

            // 滑块：进度条与滑块小圆点都使用分类色
            val seekBar = SeekBar(requireContext()).apply {
                max = 100
                progress = defaultWeights[i]
                progressTintList = ColorStateList.valueOf(Categories.color(name))
                thumbTintList = ColorStateList.valueOf(Categories.color(name))
            }
            row.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            val pct = TextView(requireContext()).apply {
                text = "${defaultWeights[i]}%"
                textSize = 13f
                gravity = Gravity.END
                setTextColor(Categories.color(name))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            row.addView(pct, LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))

            binding.sliderContainer.addView(row)
            sliders.add(seekBar)
            pctViews.add(pct)

            val index = i
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    pctViews[index].text = "$progress%"
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
    }

    /** 综合地域系数 = 省份系数 × 城市等级系数（与 C++ 一致） */
    private fun updateFactor() {
        val provFactor = ProvinceFactors.provinces[binding.spProvince.selectedItemPosition.coerceAtLeast(0)].second
        val cityFactor = ProvinceFactors.cityLevels[binding.spCity.selectedItemPosition.coerceAtLeast(0)].second
        val factor = provFactor * cityFactor
        binding.tvFactor.text = String.format(
            "综合地域系数 ×%.2f（省份 %.2f × 城市等级 %.2f）", factor, provFactor, cityFactor
        )
    }

    /** 生成预算方案：总额 × 归一化占比，保存到 BudgetPrefs */
    private fun generate() {
        val total = binding.etTotal.text.toString().trim().toDoubleOrNull()
        if (total == null || total <= 0) {
            binding.etTotal.error = "请输入月生活费总额"
            return
        }
        val sumWeights = sliders.sumOf { it.progress }
        if (sumWeights <= 0) {
            Toast.makeText(requireContext(), "请先拖动滑块设置占比", Toast.LENGTH_SHORT).show()
            return
        }

        val catMap = mutableMapOf<String, Double>()
        budgetCategories.forEachIndexed { i, name ->
            catMap[name] = total * sliders[i].progress / sumWeights
        }
        showResult(total, catMap)

        // 保存到本地 Room 缓存（预算页/预警页读取）并同步服务器（离线则标记待同步，网络恢复自动补推）
        val app = requireActivity().application as SmartButlerApp
        val userId = app.userRepository.getCurrentUser()?.userId ?: ""
        Log.d("BudgetFragment", "点击生成预算: userId=$userId 分类数=${catMap.size} 总额=${catMap.values.sum()}")
        viewLifecycleOwner.lifecycleScope.launch {
            app.budgetRepository.saveBudget(userId, catMap)
        }
        Toast.makeText(requireContext(), "预算方案已生成并保存", Toast.LENGTH_SHORT).show()
    }

    /** 在结果区域展示预算方案（生成后 / 打开页面加载已保存方案共用） */
    private fun showResult(total: Double, catMap: Map<String, Double>) {
        val ctx = requireContext()
        binding.resultContainer.removeAllViews()

        // 外层卡片
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_card)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        // 标题行：总额 chip + 提示
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(ctx).apply {
            text = "📊 预算方案"
            textSize = 14f
            setTextColor(ctx.getColor(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val totalChip = TextView(ctx).apply {
            text = "月生活费 ¥${fmtMoney(total)}"
            textSize = 11f
            setTextColor(ctx.getColor(R.color.primary))
            background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_factor_chip)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        headerRow.addView(title)
        headerRow.addView(totalChip)
        card.addView(headerRow)

        // 分割线
        val divider = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                .apply { topMargin = dp(10); bottomMargin = dp(6) }
            setBackgroundColor(ctx.getColor(R.color.divider))
        }
        card.addView(divider)

        // 每条分类：色点 + 类别名 + 金额(占比)
        val sumWeights = sliders.sumOf { it.progress }
        budgetCategories.forEachIndexed { i, name ->
            val amount = catMap[name] ?: 0.0
            val pct = if (sumWeights > 0) sliders[i].progress * 100 / sumWeights else 0
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            // 圆点（分类色）
            val dot = View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Categories.color(name))
                }
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10))
            }
            // 类别名
            val tvName = TextView(ctx).apply {
                text = name
                textSize = 13f
                setTextColor(ctx.getColor(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(8) }
            }
            // 金额 + 占比
            val rightWrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvPct = TextView(ctx).apply {
                text = "$pct%"
                textSize = 11f
                setTextColor(Categories.color(name))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val tvAmt = TextView(ctx).apply {
                text = "  ¥${fmtMoney(amount)}"
                textSize = 13f
                setTextColor(ctx.getColor(R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            rightWrap.addView(tvPct)
            rightWrap.addView(tvAmt)
            row.addView(dot)
            row.addView(tvName)
            row.addView(rightWrap)
            card.addView(row)
        }

        // 提示行
        val hint = TextView(ctx).apply {
            text = "已保存至「预警」页，可查看余额预警"
            textSize = 11f
            setTextColor(ctx.getColor(R.color.text_hint))
            setPadding(0, dp(8), 0, 0)
        }
        card.addView(hint)

        binding.resultContainer.addView(card)
    }

    /**
     * 打开页面时从本地读取已保存预算并回填界面（纯本地读取，不依赖网络）：
     * 1) 已登录 → Room（budgets 表，按 userId 隔离）；
     * 2) Room 为空或未登录 → 回退 SharedPreferences（BudgetPrefs）遗留预算。
     * 回填项：月生活费总额输入框 + 6 个分类滑块占比 + 结果区域方案展示。
     */
    private fun loadSavedBudget() {
        val app = requireActivity().application as SmartButlerApp
        val userId = app.userRepository.getCurrentUser()?.userId ?: ""
        viewLifecycleOwner.lifecycleScope.launch {
            var catMap: Map<String, Double> = emptyMap()
            if (userId.isNotBlank()) {
                catMap = app.budgetRepository.getBudgetMap(userId)
                Log.d("BudgetFragment", "从 Room 读取预算: userId=$userId 分类数=${catMap.size}")
            } else {
                Log.d("BudgetFragment", "未登录（userId 为空），跳过 Room 读取，回退 SharedPreferences")
            }
            if (catMap.isEmpty() && BudgetPrefs.hasBudget(requireContext())) {
                catMap = BudgetPrefs.allCategoryBudgets(requireContext())
                Log.d("BudgetFragment", "Room 为空，回退 SharedPreferences 读取预算: 分类数=${catMap.size}")
            }
            if (catMap.isEmpty()) {
                Log.d("BudgetFragment", "本地无已保存预算，保持默认界面")
                return@launch
            }
            val total = catMap.values.sum()
            binding.etTotal.setText(fmtMoney(total))
            // 金额 → 滑块占比（toInt 截断后归一到 100）
            val weights = IntArray(budgetCategories.size) { i ->
                val amount = catMap[budgetCategories[i]] ?: 0.0
                if (total > 0) (amount / total * 100).toInt() else 0
            }
            val sum = weights.sum()
            if (sum > 0 && sum != 100) {
                var diff = 100 - sum
                var i = 0
                while (diff != 0 && i < weights.size) {
                    if (weights[i] > 0) {
                        weights[i] += diff
                        diff = 0
                    }
                    i++
                }
            }
            sliders.forEachIndexed { i, sb -> sb.progress = weights[i] }
            showResult(total, catMap)
            Log.d("BudgetFragment", "已回填预算到界面: 总额=${fmtMoney(total)} 分类数=${catMap.size}")
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
