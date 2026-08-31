package com.ousuan.smartbutler.ui.budget

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
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
import kotlin.math.roundToInt

/**
 * 预算规划页：完整移植 C++ budget_planner.cpp。
 *
 * - 11 个支出分类滑块（餐饮/居住/交通/购物/娱乐/医疗/教育/通讯/社交人情/旅行/其他），
 *   拖动或 −/+ 微调时，差额补到其余分类中占比最大的一类，总和恒 100%。
 * - 省份 × 城市等级综合系数 → 一键配置默认占比（居住随系数浮动，其余 10 类等比补足）。
 * - 推荐预算：收入 × 基准比例（70/60/50/40%）× 综合地域系数；自定义总额：直接输入按占比分配。
 * - 月 / 年周期切换；生成后显示软提醒（6 条规则）。
 */
class BudgetFragment : Fragment() {

    private var _binding: FragmentBudgetBinding? = null
    private val binding get() = _binding!!

    /** 11 个预算分类（顺序与 C++ kDefaultRatios 一致，居住 = 索引 1） */
    private val budgetCategories = Categories.BUDGET

    /** 基准占比（对应 C++ kDefaultRatios） */
    private val DEFAULT_RATIOS = doubleArrayOf(
        0.35, 0.25, 0.08, 0.08, 0.06, 0.04, 0.05, 0.03, 0.04, 0.01, 0.01
    )

    /** 居住分类在 budgetCategories 中的下标（居住占比随地域系数浮动） */
    private val HOUSING_IDX = 1

    private val sliders = mutableListOf<SeekBar>()
    private val pctViews = mutableListOf<TextView>()
    private val weights = IntArray(budgetCategories.size)

    /** 程序化刷新滑块时置 true，防止 setProgress 触发重入补足 */
    private var balancing = false

    /** 周期：false=月，true=年；模式：false=推荐，true=自定义 */
    private var isYear = false
    private var isCustom = false

    /** 旧 6 类预算分类名 → 新 11 类（旧「学习」并入「教育」；旧「储蓄」为结余结果，不属于支出分类，丢弃） */
    private val legacyCategoryMap = mapOf("学习" to "教育")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBudgetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        buildSliders()
        setupSpinners()
        setupModeAndPeriod()
        binding.btnDefault.setOnClickListener { applyDefaultRatios() }
        binding.btnGenerate.setOnClickListener {
            Log.d(TAG, "generate 按钮被点击")
            generate()
        }
        loadSavedBudget()
    }

    override fun onResume() {
        super.onResume()
        // 回到前台重新加载已保存预算（幂等；保证外部写入后展示最新数据）
        if (_binding != null) loadSavedBudget()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // MainActivity 用 add + show/hide 复用 Fragment：show/hide 不会重新走 onResume，
        // 只有在 onHiddenChanged 中刷新，IF 线联动（Alert 页写入 BudgetRepository）后
        // 切回预算 Tab 才能看到最新方案
        if (!hidden && _binding != null) loadSavedBudget()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ==================== 滑块构建 ====================

    private fun buildSliders() {
        // 初始占比 = 基准默认（对应 C++ kDefaultRatios，总和恰好 100）
        val init = IntArray(budgetCategories.size) { i -> (DEFAULT_RATIOS[i] * 100).roundToInt() }
        normalizeSum(init).copyInto(weights)

        budgetCategories.forEachIndexed { i, name ->
            val color = Categories.color(name)

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }

            // 色点 + 分类名
            val labelWrap = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val dot = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
            }
            val label = TextView(requireContext()).apply {
                text = name
                textSize = 12f
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(5) }
            }
            labelWrap.addView(dot)
            labelWrap.addView(label)
            row.addView(labelWrap)

            // 滑块（0-100）
            val seekBar = SeekBar(requireContext()).apply {
                max = 100
                progress = weights[i]
                progressTintList = ColorStateList.valueOf(color)
                thumbTintList = ColorStateList.valueOf(color)
            }
            row.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // 百分比文本
            val pct = TextView(requireContext()).apply {
                text = "${weights[i]}%"
                textSize = 12f
                gravity = Gravity.END
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(pct, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))

            // − / + 1% 步进微调按钮
            val minus = stepButton(color, "−")
            val plus = stepButton(color, "+")
            val index = i
            minus.setOnClickListener { adjustWeight(index, weights[index] - 1) }
            plus.setOnClickListener { adjustWeight(index, weights[index] + 1) }
            row.addView(minus, LinearLayout.LayoutParams(dp(30), dp(30)))
            row.addView(plus, LinearLayout.LayoutParams(dp(30), dp(30)))

            binding.sliderContainer.addView(row)
            sliders.add(seekBar)
            pctViews.add(pct)

            // 拖动 → 目标分类取当前值，差额补到最大分类
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && !balancing) adjustWeight(index, progress)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
    }

    private fun stepButton(color: Int, op: String): TextView = TextView(requireContext()).apply {
        text = op
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(color)
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setStroke(dp(1), color)
            setColor(0x08A0A0A0)
        }
        isClickable = true
        isFocusable = true
    }

    // ==================== 占比计算与补足 ====================

    /** 当前综合地域系数 = 省份系数 × 城市等级系数 */
    private fun currentFactor(): Double {
        val p = ProvinceFactors.provinces[binding.spProvince.selectedItemPosition.coerceAtLeast(0)].second
        val c = ProvinceFactors.cityLevels[binding.spCity.selectedItemPosition.coerceAtLeast(0)].second
        return p * c
    }

    /**
     * 一键配置默认占比（对应 C++ defaultRatiosForRegion）：
     * 居住占比随综合系数浮动（clamp 15%~40%），其余 10 类等比缩放补足 100%。
     */
    private fun defaultRatiosForRegion(): IntArray {
        val factor = currentFactor()
        val housing = (0.25 + (factor - 1.0) * 0.15).coerceIn(0.15, 0.40)
        val restScale = (1.0 - housing) / (1.0 - DEFAULT_RATIOS[HOUSING_IDX]) // 原非居住占比和 = 0.75
        val result = IntArray(budgetCategories.size)
        budgetCategories.indices.forEach { i ->
            val ratio = if (i == HOUSING_IDX) housing else DEFAULT_RATIOS[i] * restScale
            result[i] = (ratio * 100).roundToInt()
        }
        return normalizeSum(result)
    }

    /** 保存前归一化：把整数数组修正到总和 = 100（误差加到最大占比项） */
    private fun normalizeSum(arr: IntArray): IntArray {
        val diff = 100 - arr.sum()
        if (diff == 0) return arr
        var best = 0
        arr.forEachIndexed { i, v -> if (v > arr[best]) best = i }
        arr[best] += diff
        return arr
    }

    /** 程序化刷新全部滑块与百分比文本（try-finally 保证 balancing 一定复位，防异常卡死） */
    private fun applyWeights(newWeights: IntArray) {
        balancing = true
        try {
            newWeights.forEachIndexed { i, w ->
                weights[i] = w
                sliders[i].progress = w
                pctViews[i].text = "$w%"
            }
        } finally {
            balancing = false
        }
    }

    /**
     * 拖动 / 微调后的自动补足（「差值补到最大分类」策略）：
     * 只改变被操作行与吸收差额的一行，其余行保持不动，保证总和恒 = 100%。
     * - 被操作行减小（target < 原值）：差额加到「其余分类中占比最大」的一类；
     * - 被操作行增大（target > 原值）：从「其余分类中占比最大」的一类开始扣减，
     *   不足以扣时依次扣次大分类（其余总和 ≥ 差额，数学上必能扣完）；
     * - 其余分类全为 0 时，差额按顺序补到第一个非操作行。
     */
    private fun adjustWeight(moved: Int, target: Int) {
        if (balancing) return
        val old = weights[moved]
        val v = target.coerceIn(0, 100)
        if (old == v) return

        val newWeights = weights.copyOf()
        newWeights[moved] = v

        // 差额：>0 表示其余分类需增加 old-v，<0 表示其余分类需减少 v-old
        val delta = old - v

        if (delta > 0) {
            // 差额加到「其余分类中最大」的一类；其余全 0 时补到第一个非 moved 分类
            val idx = maxIndexExcluding(moved)
            if (idx >= 0) {
                newWeights[idx] += delta
            } else {
                val first = weights.indices.firstOrNull { it != moved }
                if (first != null) newWeights[first] += delta
            }
        } else if (delta < 0) {
            // 从最大分类依次扣减（不足则继续扣次大），直至差额补足
            var remaining = -delta
            val order = weights.indices
                .filter { it != moved }
                .sortedByDescending { weights[it] }
            for (idx in order) {
                if (remaining <= 0) break
                val take = minOf(newWeights[idx], remaining)
                newWeights[idx] -= take
                remaining -= take
            }
            // v ≤ 100 时其余总和恒 ≥ 需扣量，正常不会走到；防御性钳制
            if (remaining > 0) newWeights[moved] -= remaining
        }
        // 兜底归一化（正常情况总和已恒 100）
        applyWeights(normalizeSum(newWeights))
    }

    /** 其余分类中占比最大者的下标；其余全为 0 时返回 -1（并列取先出现者） */
    private fun maxIndexExcluding(excluded: Int): Int {
        var idx = -1
        var max = -1
        weights.indices.forEach { i ->
            if (i != excluded && weights[i] > max) {
                max = weights[i]
                idx = i
            }
        }
        return idx
    }

    // ==================== 地域联动 ====================

    private fun setupSpinners() {
        binding.spProvince.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            ProvinceFactors.provinces.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        binding.spCity.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            ProvinceFactors.cityLevels.map { it.first }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val onRegionChanged = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFactor()
                applyDefaultRatios()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        binding.spProvince.onItemSelectedListener = onRegionChanged
        binding.spCity.onItemSelectedListener = onRegionChanged
        updateFactor()
    }

    private fun updateFactor() {
        val factor = currentFactor()
        binding.tvFactor.text = String.format(
            "综合地域系数 = %.2f（省份 %.2f × 城市等级 %.2f）",
            factor,
            ProvinceFactors.provinces[binding.spProvince.selectedItemPosition.coerceAtLeast(0)].second,
            ProvinceFactors.cityLevels[binding.spCity.selectedItemPosition.coerceAtLeast(0)].second
        )
    }

    /** 省份/城市变化或点击「一键配置」：刷新为地域默认占比（自动补足 100%） */
    private fun applyDefaultRatios() {
        if (sliders.isEmpty()) return
        applyWeights(defaultRatiosForRegion())
    }

    // ==================== 周期 / 模式 ====================

    private fun setupModeAndPeriod() {
        binding.tgPeriod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isYear = checkedId == binding.btnPeriodYear.id
            updatePeriodLabels()
        }
        binding.tgMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            isCustom = checkedId == binding.btnModeCustom.id
            updateModeVisibility()
        }
        updatePeriodLabels()
        updateModeVisibility()

        // 收入变化 → 实时刷新推荐总额预览
        binding.etIncome.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                updateRecommendPreview()
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun updatePeriodLabels() {
        binding.etIncome.hint = if (isYear) "输入年收入（元）" else "输入月收入（元）"
        binding.etCustomTotal.hint = if (isYear) "输入年度总预算（元）" else "输入月度总预算（元）"
        updateRecommendPreview()
    }

    private fun updateModeVisibility() {
        binding.etIncome.error = null
        binding.etCustomTotal.error = null
        val showIncome = !isCustom
        binding.etIncome.visibility = if (showIncome) View.VISIBLE else View.GONE
        binding.tvRecommend.visibility = if (showIncome) View.VISIBLE else View.GONE
        binding.etCustomTotal.visibility = if (isCustom) View.VISIBLE else View.GONE
    }

    /** 推荐预算实时预览：收入 × 基准比例 × 综合地域系数（对应 C++ 推荐模式） */
    private fun updateRecommendPreview() {
        if (isCustom || sliders.isEmpty()) return
        val income = binding.etIncome.text.toString().trim().toDoubleOrNull()
        if (income == null || income <= 0) {
            binding.tvRecommend.text = "推荐预算：-- 元（收入 × 基准比例 × 综合地域系数）"
            return
        }
        val monthly = if (isYear) income / 12 else income
        val ratio = incomeBaseRatio(monthly)
        val total = income * ratio * currentFactor()
        binding.tvRecommend.text = "推荐预算：¥" + fmtMoney(total) +
            "（基准比例 ${(ratio * 100).toInt()}% × 综合系数 ${"%.2f".format(currentFactor())}）"
    }

    /**
     * 收入基准比例（对应 C++ incomeBaseRatio）：
     * 月收入 ≤3000 → 70%；≤8000 → 60%；≤20000 → 50%；更高 → 40%。
     */
    private fun incomeBaseRatio(monthlyIncome: Double): Double = when {
        monthlyIncome <= 3000 -> 0.70
        monthlyIncome <= 8000 -> 0.60
        monthlyIncome <= 20000 -> 0.50
        else -> 0.40
    }

    // ==================== 生成与保存 ====================

    /** 计算生成总预算：推荐（收入×比例×系数）或自定义（输入总额，超出收入上限按收入上限） */
    private fun computeTotal(): Double? {
        return if (!isCustom) {
            val income = binding.etIncome.text.toString().trim().toDoubleOrNull()
            if (income == null || income <= 0) {
                binding.etIncome.error = "请输入${if (isYear) "年" else "月"}收入"
                // 除了输入框红字外再加 Toast，避免「按钮没反应」的误判
                Toast.makeText(requireContext(), "请先在「推荐」模式下输入收入，再点生成", Toast.LENGTH_SHORT).show()
                return null
            }
            val monthly = if (isYear) income / 12 else income
            income * incomeBaseRatio(monthly) * currentFactor()
        } else {
            val total = binding.etCustomTotal.text.toString().trim().toDoubleOrNull()
            if (total == null || total <= 0) {
                binding.etCustomTotal.error = "请输入总预算"
                Toast.makeText(requireContext(), "请先在「自定义」模式下输入总预算，再点生成", Toast.LENGTH_SHORT).show()
                return null
            }
            // 自定义总额超出收入上限 → 按收入上限生成（对应 C++ 行为），并在软提醒中说明
            val income = binding.etIncome.text.toString().trim().toDoubleOrNull()
            if (income != null && income > 0 && total > income) {
                Toast.makeText(
                    requireContext(),
                    "总预算超出收入上限，已按收入上限 ¥" + fmtMoney(income) + " 生成",
                    Toast.LENGTH_SHORT
                ).show()
                income
            } else {
                total
            }
        }
    }

    private fun generate() {
        Log.d(TAG, "generate() 被调用: isCustom=$isCustom, isYear=$isYear")
        try {
            val total = computeTotal() ?: return
            val sum = weights.sum()
            if (sum <= 0) {
                Toast.makeText(requireContext(), "请先拖动滑块设置占比", Toast.LENGTH_SHORT).show()
                return
            }
            // 各分类金额 = 总额 × 滑块当前占比（滑块总和恒 100，占比即 weights[i]）
            val catMap = LinkedHashMap<String, Double>()
            budgetCategories.forEachIndexed { i, name ->
                catMap[name] = total * weights[i] / sum
            }
            showResult(total, catMap)
            showReminders(total, catMap)

            // 保存：本地 Room + BudgetPrefs 立即生效，再异步推送服务器（离线标记待同步）
            val app = requireActivity().application as SmartButlerApp
            val userId = app.userRepository.getCurrentUser()?.userId ?: ""
            Log.d(TAG, "生成预算: total=${fmtMoney(total)} categories=${catMap.size}")
            viewLifecycleOwner.lifecycleScope.launch {
                app.budgetRepository.saveBudget(userId, catMap)
            }
            Toast.makeText(requireContext(), "预算方案已生成并保存", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // 生成逻辑异常不吞掉：打印完整堆栈 + Toast 提示
            Log.e(TAG, "生成预算异常", e)
            Toast.makeText(requireContext(), "生成失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 软提醒（对应 C++ 软提醒规则，6 条）：
     * 餐饮<15% / 居住<15% / 购物>15% / 娱乐>12% / 单分类≥60% / 月均低于基本生活线 1500 元；
     * 年周期额外提示按月均执行。
     */
    private fun showReminders(total: Double, catMap: Map<String, Double>) {
        val sb = StringBuilder()
        val pctOf = { name: String -> if (total > 0) (catMap[name] ?: 0.0) / total * 100 else 0.0 }

        if ((catMap["餐饮"] ?: 0.0) > 0 && pctOf("餐饮") < 15) {
            sb.append("· 餐饮占比偏低（${pctOf("餐饮").toInt()}%），注意基本生活保障\n")
        }
        if ((catMap["居住"] ?: 0.0) > 0 && pctOf("居住") < 15) {
            sb.append("· 居住占比偏低（${pctOf("居住").toInt()}%），保障住房需求\n")
        }
        if (pctOf("购物") > 15) {
            sb.append("· 购物占比偏高（${pctOf("购物").toInt()}%），理性消费\n")
        }
        if (pctOf("娱乐") > 12) {
            sb.append("· 娱乐占比偏高（${pctOf("娱乐").toInt()}%），合理安排休闲支出\n")
        }
        catMap.forEach { (name, amt) ->
            if (total > 0 && amt / total >= 0.60) {
                sb.append("· 「$name」占比过高（${(amt / total * 100).toInt()}%），建议留出弹性空间\n")
            }
        }
        val monthly = if (isYear) total / 12 else total
        if (monthly < 1500) {
            sb.append("· 月均预算低于基本生活线 1500 元，建议提高收入或精简非必要支出\n")
        }
        if (isYear) {
            sb.append("· 年周期方案：建议按月均 ¥" + fmtMoney(total / 12) + " 分配执行\n")
        }

        binding.tvReminder.text = if (sb.isEmpty()) "✅ 结构合理，无特别提醒" else sb.toString().trimEnd()
    }

    // ==================== 结果展示与回填 ====================

    /** 展示生成结果：占比使用滑块当前值（与滑块一致，总和 = 100%） */
    private fun showResult(total: Double, catMap: Map<String, Double>) {
        binding.resultContainer.removeAllViews()

        val chip = TextView(requireContext()).apply {
            val prefix = if (isYear) "年生活费" else "月生活费"
            text = "$prefix ¥" + fmtMoney(total) + "（" + if (isYear) "12 个月" else "30 天" + "）"
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFFE6F7F1.toInt())
            }
            setTextColor(0xFF0E9488.toInt())
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        binding.resultContainer.addView(chip)

        budgetCategories.forEachIndexed { i, name ->
            val amount = catMap[name] ?: 0.0
            val pct = weights[i] // 与滑块当前值一致
            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            val color = Categories.color(name)

            val dot = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
            }
            row.addView(dot)

            val label = TextView(requireContext()).apply {
                text = name
                textSize = 13f
                setTextColor(0xFF444444.toInt())
                layoutParams = LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(6)
                }
            }
            row.addView(label)

            val barBg = LinearLayout(requireContext()).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(0xFFEEEEEE.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(14), 1f)
            }
            val barFill = View(requireContext()).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(color)
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(14), pct.toFloat().coerceAtLeast(0f))
            }
            barBg.addView(barFill)
            row.addView(barBg)

            val amountTv = TextView(requireContext()).apply {
                text = "¥" + fmtMoney(amount)
                textSize = 12f
                gravity = Gravity.END
                setTextColor(0xFF444444.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(6) }
            }
            row.addView(amountTv)

            val pctTv = TextView(requireContext()).apply {
                text = "$pct%"
                textSize = 12f
                gravity = Gravity.END
                setTextColor(color)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(pctTv)

            binding.resultContainer.addView(row)
        }
    }

    /** 打开时从 Room 优先回填（未登录回退 BudgetPrefs）；旧 6 类数据做名称映射后回填 */
    private fun loadSavedBudget() {
        val app = requireActivity().application as SmartButlerApp
        val userId = app.userRepository.getCurrentUser()?.userId ?: ""
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var raw: Map<String, Double> = emptyMap()
                if (userId.isNotBlank()) {
                    raw = app.budgetRepository.getBudgetMap(userId)
                }
                if (raw.isEmpty() && BudgetPrefs.hasBudget(requireContext())) {
                    raw = BudgetPrefs.allCategoryBudgets(requireContext())
                }

                // 旧 6 类分类名 → 新 11 类（学习→教育；储蓄为结余结果丢弃）；
                // 不在 Categories.BUDGET 中的分类直接跳过，不参与回填
                val catMap = LinkedHashMap<String, Double>()
                raw.forEach { (cat, amt) ->
                    val key = legacyCategoryMap[cat] ?: cat
                    if (key in budgetCategories) catMap[key] = (catMap[key] ?: 0.0) + amt
                }

                // 数据为空或所有分类都匹配不上 → 使用地域默认占比（联动系数）
                if (catMap.isEmpty()) {
                    Log.w(TAG, "loadSavedBudget: 无可识别的预算分类，使用默认占比")
                    applyDefaultRatios()
                    return@launch
                }

                val total = catMap.values.sum()

                // 总额回填到自定义输入框并切到自定义模式，用户可直接改总额再生成
                binding.etCustomTotal.setText(fmtMoney(total))
                binding.tgMode.check(binding.btnModeCustom.id)

                // 金额 → 滑块占比（四舍五入 + 归一化 100）；缺失分类按 0 占比，空安全兜底
                val result = IntArray(budgetCategories.size)
                budgetCategories.forEachIndexed { i, name ->
                    result[i] = if (total > 0) ((catMap[name] ?: 0.0) / total * 100).roundToInt() else 0
                }
                applyWeights(normalizeSum(result))
                showResult(total, catMap)
                showReminders(total, catMap)
                Log.d("BudgetFragment", "已回填预算: total=${fmtMoney(total)} categories=${catMap.size}")
            } catch (e: Exception) {
                Log.e(TAG, "loadSavedBudget 回填失败，回退默认占比", e)
                try {
                    applyDefaultRatios()
                } catch (e2: Exception) {
                    Log.e(TAG, "回退默认占比也失败", e2)
                }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "BudgetFragment"
    }
}
