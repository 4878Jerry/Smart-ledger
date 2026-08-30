package com.ousuan.smartbutler.ui.alert

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 功能③ 预警 + IF 线 + 年月日视图切换。
 *
 * IF 线（副本模型，对应 C++ expense_analyzer.cpp 的 g.ifline = g.records）：
 * - 进入 IF 线视图时快照当前日/月/年筛选范围的记录到 [iflineRecords]
 * - 所有操作（修改金额 / 修改分类 / 删除）只作用于副本，绝不触碰 transactions 表
 * - 副本用 Gson 序列化到 SharedPreferences 做简单持久化（参照 BudgetPrefs 模式）
 * - 切视图 / 换日期时重新快照；同视图重进时恢复持久化副本，保留编辑结果
 * - 搜索框按类别 / 备注 / 商户关键词实时过滤副本
 * - 底部差异分析面板：既有线 vs IF 线收支结余、差额、攒 1000 元应急金月数、分类变化 Top3
 *
 * 预警与汇总仍基于 repository 原始数据，不受 IF 线影响。
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

    /** 既有线：当前筛选范围的原始记录（只读，供差异对比） */
    private var rangeRecords: List<Transaction> = emptyList()

    /** IF 线副本：进入视图时从 range 快照，所有操作只动它 */
    private var iflineRecords: MutableList<Transaction> = mutableListOf()

    /** 当前 IF 线副本对应的视图标识（"0:2026-08-31" / "1:2026-08" / "2:2026"） */
    private var ifRangeKey: String = ""

    /** IF 线排序列：-1 不排序；0 日期 / 1 分类 / 2 金额 / 3 收款方 */
    private var ifSortCol = -1

    /** 排序方向：true 升序 / false 降序（对应 C++ ifSortAsc） */
    private var ifSortAsc = true

    /** 新增记录用的负 id 计数器（自减，避免与既有线正 id 冲突，对应 C++ ifLineNextId--） */
    private var ifNextId = -1L

    private val gson = Gson()

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

        // IF 线搜索：实时过滤副本
        binding.etIfSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                renderIfList()
            }
        })

        // IF 线工具栏：新增 / 重置 / 建议报告 / 生成现实规划
        binding.btnIfAdd.setOnClickListener { showAddIfRecordDialog(null) }
        binding.btnIfReset.setOnClickListener { recopyIfFromMain() }
        binding.btnIfAdvice.setOnClickListener { showHabitAdviceDialog() }
        binding.btnIfPlan.setOnClickListener { ifLineToPlan() }
        binding.btnIfPlanReal.setOnClickListener { generatePlanFromIfLine() }

        // IF 线排序表头：点击列头切换升降序
        binding.ifHdrDate.setOnClickListener { onSortHeaderClick(0) }
        binding.ifHdrCat.setOnClickListener { onSortHeaderClick(1) }
        binding.ifHdrAmt.setOnClickListener { onSortHeaderClick(2) }
        binding.ifHdrPayee.setOnClickListener { onSortHeaderClick(3) }

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
            rangeRecords = range
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

            // ---------- IF 线：副本快照 ----------
            // 切视图 / 换日期（key 变化）→ 重新快照 range；
            // 同视图重进（key 相同）→ 恢复持久化副本，保留之前的编辑结果
            val key = "$viewType:$cursor"
            if (ifRangeKey != key) {
                ifRangeKey = key
                iflineRecords = if (range.isNotEmpty()) {
                    loadPersistedIfLine(key)?.toMutableList() ?: range.toMutableList()
                } else {
                    mutableListOf()
                }
                persistIfLine()
            }
            renderIfList()
            renderIfDiff()
        }
    }

    // ===================== IF 线：副本持久化 =====================

    private fun persistIfLine() {
        val prefs = requireContext().getSharedPreferences("ifline_prefs", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        if (iflineRecords.isEmpty()) {
            ed.remove("records_$ifRangeKey")
        } else {
            ed.putString("records_$ifRangeKey", gson.toJson(iflineRecords))
        }
        ed.apply()
    }

    private fun loadPersistedIfLine(key: String): List<Transaction>? {
        val prefs = requireContext().getSharedPreferences("ifline_prefs", Context.MODE_PRIVATE)
        val json = prefs.getString("records_$key", null) ?: return null
        return try {
            val type = object : TypeToken<List<Transaction>>() {}.type
            gson.fromJson<List<Transaction>>(json, type)
        } catch (e: Exception) {
            null
        }
    }

    // ===================== IF 线：列表渲染 + 搜索过滤 =====================

    private fun renderIfList() {
        val b = _binding ?: return
        b.ifContainer.removeAllViews()
        val hasData = iflineRecords.isNotEmpty()
        b.etIfSearch.visibility = if (hasData) View.VISIBLE else View.GONE
        b.ifHeader.visibility = if (hasData) View.VISIBLE else View.GONE
        b.ifToolbar.visibility = if (hasData) View.VISIBLE else View.GONE

        if (!hasData) {
            b.ifContainer.addView(simpleRow("暂无记录可模拟 IF 线"))
            return
        }

        // 搜索过滤：日期 / 类型 / 分类 / 收款方 / 备注（对应 C++ ifSearch 匹配逻辑）
        val query = b.etIfSearch.text.toString().trim()
        var list: List<Transaction> = if (query.isEmpty()) iflineRecords else iflineRecords.filter {
            it.date.contains(query, true) ||
                    it.type.contains(query, true) ||
                    it.category.contains(query, true) ||
                    it.payee.contains(query, true) ||
                    it.note.contains(query, true)
        }

        // 列排序：点击表头升降序（对应 C++ RefreshIfList 的 stable_sort）
        val comparator: Comparator<Transaction>? = when (ifSortCol) {
            0 -> compareBy { it.date }
            1 -> compareBy { it.category }
            2 -> compareBy { it.amount }
            3 -> compareBy { it.payee }
            else -> null
        }
        if (comparator != null) {
            list = if (ifSortAsc) list.sortedWith(comparator) else list.sortedWith(comparator.reversed())
        }

        if (list.isEmpty()) {
            b.ifContainer.addView(simpleRow("没有匹配「$query」的记录"))
        } else {
            list.forEach { b.ifContainer.addView(ifRow(it)) }
        }
    }

    /** 点击表头列：同列切换升降序，不同列设为升序（对应 C++ ifSortCol / ifSortAsc 切换） */
    private fun onSortHeaderClick(col: Int) {
        if (ifSortCol == col) {
            ifSortAsc = !ifSortAsc
        } else {
            ifSortCol = col
            ifSortAsc = true
        }
        updateSortHeaders()
        renderIfList()
    }

    /** 刷新表头箭头：当前排序列显示 ▲/▼，其余列只显示列名 */
    private fun updateSortHeaders() {
        val b = _binding ?: return
        val cols = listOf(b.ifHdrDate, b.ifHdrCat, b.ifHdrAmt, b.ifHdrPayee)
        val names = listOf("日期", "分类", "金额", "收款方")
        cols.forEachIndexed { i, tv ->
            tv.text = if (i == ifSortCol) {
                names[i] + if (ifSortAsc) " ▲" else " ▼"
            } else {
                names[i]
            }
        }
    }

    /** 单条 IF 行：日期 + 类别(色点) + 金额 + 右侧「操作」入口，整行可点开操作菜单 */
    private fun ifRow(r: Transaction): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { showIfMenu(r) }
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

        // 右侧「⋯ 操作」chip（点击弹出操作菜单，与整行点击一致）
        val action = TextView(ctx).apply {
            text = "⋯ 操作"
            textSize = 11f
            setTextColor(ctx.getColor(R.color.income))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = ctx.getDrawable(R.drawable.bg_factor_chip)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            isClickable = true
            isFocusable = true
            setOnClickListener { showIfMenu(r) }
        }

        row.addView(leftWrap)
        row.addView(action)
        return row
    }

    /** IF 线行操作菜单：改金额 / 改分类 / 编辑信息 / 恢复原值 / 删除（全部只作用于副本） */
    private fun showIfMenu(r: Transaction) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("IF 线 · ${r.category} ${fmtMoney(r.amount)} 元")
            .setItems(arrayOf("修改金额", "修改分类", "编辑信息", "恢复原值", "删除这条")) { _, which ->
                when (which) {
                    0 -> showEditAmountDialog(r)
                    1 -> showEditCategoryDialog(r)
                    2 -> showAddIfRecordDialog(r.id)
                    3 -> restoreFromOriginal(r)
                    else -> confirmDeleteIfLine(r)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===================== IF 线：新增 / 编辑完整表单（对应 C++ IfFormToRecord + AddOrSaveIfRecord） =====================

    /**
     * 新增（editId == null）或编辑（editId != null）一条 IF 线记录。
     * 完整表单：日期 / 类型 / 分类 / 金额 / 收款方 / 备注；类型切换联动重置分类列表。
     * 校验：日期必须 YYYY-MM-DD、金额必须为数字且 >= 0（对齐 C++ IfFormToRecord）。
     * 新增记录使用负 id（ifNextId--），避免与既有线正 id 冲突。
     */
    private fun showAddIfRecordDialog(editId: Long?) {
        val ctx = requireContext()
        val existing = editId?.let { id -> iflineRecords.firstOrNull { it.id == id } }

        val etDate = EditText(ctx).apply {
            hint = "日期（YYYY-MM-DD）"
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_CLASS_TEXT
            setText(existing?.date ?: defaultIfDate())
        }
        val spType = Spinner(ctx)
        spType.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, arrayOf("支出", "收入"))
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spType.setSelection(if (existing?.type == "收入") 1 else 0)

        val spCat = Spinner(ctx)
        spCat.adapter = catAdapter(existing?.category ?: "餐饮")
        // 类型切换 → 联动重置分类列表（对应 C++ OnIfFormTypeChanged）
        spType.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val cats = if (pos == 1) Categories.INCOME else Categories.EXPENSE
                spCat.adapter = catAdapter(if (cats.contains(existing?.category)) existing?.category ?: cats[0] else cats[0])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        val etAmount = EditText(ctx).apply {
            hint = "金额（元）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            if (existing != null) { setText(fmtMoney(existing.amount)); setSelection(text.length) }
        }
        val etPayee = EditText(ctx).apply {
            hint = "收款方 / 商户（可空）"
            setText(existing?.payee ?: "")
        }
        val etNote = EditText(ctx).apply {
            hint = "备注（可空）"
            setText(existing?.note ?: "")
        }

        val form = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(labeled("日期", etDate))
            addView(labeled("类型", spType))
            addView(labeled("分类", spCat))
            addView(labeled("金额", etAmount))
            addView(labeled("收款方", etPayee))
            addView(labeled("备注", etNote))
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(if (existing != null) "编辑 IF 线记录" else "新增 IF 线记录")
            .setMessage("仅作用于 IF 线副本，不改原账本。")
            .setView(form)
            .setPositiveButton("保存") { _, _ ->
                // 校验（移植 C++ IfFormToRecord）
                val dateStr = etDate.text.toString().trim()
                val okDate = dateStr.matches(Regex("""\d{4}-\d{2}-\d{2}""")) &&
                        runCatching { LocalDate.parse(dateStr) }.isSuccess
                val amount = etAmount.text.toString().trim().toDoubleOrNull()
                if (!okDate) {
                    Toast.makeText(ctx, "日期格式应为 YYYY-MM-DD", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (amount == null || amount < 0) {
                    Toast.makeText(ctx, "金额无效，请输入不小于 0 的数字", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val type = if (spType.selectedItemPosition == 1) "收入" else "支出"
                val category = (spCat.selectedItem as? String) ?: "餐饮"

                if (existing != null) {
                    // 编辑态：覆盖同 id 记录
                    updateIfLine(existing.id) {
                        it.copy(date = dateStr, type = type, category = category, amount = amount, payee = etPayee.text.toString().trim(), note = etNote.text.toString().trim())
                    }
                } else {
                    // 新增态：负 id 加入副本
                    iflineRecords.add(
                        Transaction(
                            id = ifNextId--,
                            date = dateStr, type = type, category = category, amount = amount,
                            payee = etPayee.text.toString().trim(), note = etNote.text.toString().trim()
                        )
                    )
                    persistIfLine()
                    renderIfList()
                    renderIfDiff()
                }
                Toast.makeText(ctx, if (existing != null) "已更新（仅 IF 线）" else "已新增（仅 IF 线）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 表单行：标签 + 控件 */
    private fun labeled(label: String, view: View): View {
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        wrap.addView(TextView(requireContext()).apply {
            text = label
            textSize = 12f
            setTextColor(requireContext().getColor(R.color.text_secondary))
        })
        wrap.addView(view)
        return wrap
    }

    /** 分类下拉适配器：优先选择 preSelect，否则第一项 */
    private fun catAdapter(preSelect: String): ArrayAdapter<String> {
        val cats = if (preSelect in Categories.EXPENSE) Categories.EXPENSE else Categories.INCOME
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, cats.toTypedArray())
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        return adapter
    }

    /** 新增表单默认日期：日视图用当前 cursor 日期，月/年视图用今天 */
    private fun defaultIfDate(): String = if (viewType == 0) cursor.toString() else LocalDate.now().toString()

    // ===================== IF 线：重新复制 / 恢复选中（对应 C++ RecopyIfFromMain / RestoreIfSelected） =====================

    /** 一键重置：IF 线副本 = 当前既有线数据（对应 C++ RecopyIfFromMain） */
    private fun recopyIfFromMain() {
        if (iflineRecords.isEmpty() && rangeRecords.isEmpty()) {
            Toast.makeText(requireContext(), "当前范围暂无记录", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("重新复制 IF 线？")
            .setMessage("将丢弃当前 IF 线的所有修改，还原为既有线副本。")
            .setPositiveButton("重置") { _, _ ->
                iflineRecords = rangeRecords.toMutableList()
                persistIfLine()
                renderIfList()
                renderIfDiff()
                Toast.makeText(requireContext(), "已重置为既有线副本", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 恢复选中：从既有线还原该条（覆盖副本中同 id 记录，缺失则补回，对应 C++ RestoreIfSelected） */
    private fun restoreFromOriginal(r: Transaction) {
        val orig = rangeRecords.firstOrNull { it.id == r.id }
        if (orig == null) {
            Toast.makeText(requireContext(), "既有线中不存在该记录（可能为 IF 线新增）", Toast.LENGTH_SHORT).show()
            return
        }
        val idx = iflineRecords.indexOfFirst { it.id == r.id }
        if (idx >= 0) {
            iflineRecords[idx] = orig
        } else {
            iflineRecords.add(orig)
        }
        persistIfLine()
        renderIfList()
        renderIfDiff()
        Toast.makeText(requireContext(), "已恢复「${orig.category} ${fmtMoney(orig.amount)} 元」原值", Toast.LENGTH_SHORT).show()
    }

    // ===================== IF 线：修改金额（胡腾飞点名） =====================

    private fun showEditAmountDialog(r: Transaction) {
        val input = EditText(requireContext()).apply {
            hint = "输入新金额（元）"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(fmtMoney(r.amount))
            setSelection(text.length)
        }
        val wrap = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("修改金额（IF 线）")
            .setMessage("例如 38 → 15，只影响 IF 线副本，不改原账本。")
            .setView(wrap)
            .setPositiveButton("确定") { _, _ ->
                val v = input.text.toString().toDoubleOrNull()
                if (v == null || v <= 0) {
                    Toast.makeText(requireContext(), "金额无效，请输入正数", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                updateIfLine(r.id) { it.copy(amount = v) }
                Toast.makeText(requireContext(), "已改为 ${fmtMoney(v)} 元（仅 IF 线）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===================== IF 线：修改分类 =====================

    private fun showEditCategoryDialog(r: Transaction) {
        val cats = if (r.type == "收入") Categories.INCOME else Categories.EXPENSE
        val names = cats.toTypedArray()
        val holder = intArrayOf(cats.indexOf(r.category).coerceAtLeast(0))
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("修改分类（IF 线）")
            .setSingleChoiceItems(names, holder[0]) { _, which -> holder[0] = which }
            .setPositiveButton("确定") { _, _ ->
                val name = names.getOrNull(holder[0]) ?: return@setPositiveButton
                if (name != r.category) {
                    updateIfLine(r.id) { it.copy(category = name) }
                    Toast.makeText(requireContext(), "分类已改为「$name」（仅 IF 线）", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===================== IF 线：删除（副本移除） =====================

    private fun confirmDeleteIfLine(r: Transaction) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("从 IF 线删除这条？")
            .setMessage("仅从 IF 线副本移除，原始账本不受影响。")
            .setPositiveButton("删除") { _, _ ->
                iflineRecords.removeAll { it.id == r.id }
                persistIfLine()
                renderIfList()
                renderIfDiff()
                Toast.makeText(requireContext(), "已从 IF 线移除（原账本未动）", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 按 id 更新副本中的单条记录，落盘并刷新列表 + 差异面板 */
    private fun updateIfLine(id: Long, transform: (Transaction) -> Transaction) {
        val idx = iflineRecords.indexOfFirst { it.id == id }
        if (idx < 0) return
        iflineRecords[idx] = transform(iflineRecords[idx])
        persistIfLine()
        renderIfList()
        renderIfDiff()
    }

    // ===================== IF 线：消费习惯建议（对应 C++ MakeHabitAdvice 四段式） =====================

    /** 生成消费习惯 4 段式分析文本：储蓄率 / 支出集中度 / 省超对比 / 整体结余变化 */
    private fun makeHabitAdvice(): String {
        if (iflineRecords.isEmpty()) return "IF 线暂无记录，先复制或新增再分析吧。"

        val sum = ExpenseAnalyzer.summarize(iflineRecords)
        val save = sum.income - sum.expense
        val sb = StringBuilder()

        // 第 1 段：储蓄率
        sb.append("① 储蓄率")
        if (sum.income > 0) {
            val rate = save / sum.income
            sb.append("（${fmtMoney(save)} / ${fmtMoney(sum.income)} 收入）\n")
            sb.append(
                when {
                    rate >= 0.20 -> "✅ ${(rate * 100).toInt()}%，储蓄充足，继续保持！\n"
                    rate >= 0.10 -> "🟡 ${(rate * 100).toInt()}%，有一定储蓄，建议再提升。\n"
                    rate >= 0 -> "⚠️ ${(rate * 100).toInt()}%，几乎月光，该控制了。\n"
                    else -> "🚨 支出超过收入，已透支 ${fmtMoney(-save)} 元，急需调整！\n"
                }
            )
        } else {
            sb.append("：无收入记录，无法计算储蓄率。\n")
        }

        // 第 2 段：支出集中度
        val cats = ExpenseAnalyzer.categoryAmounts(iflineRecords)
            .entries.filter { it.value > 0 }
            .sortedByDescending { it.value }
        if (cats.isNotEmpty()) {
            val total = cats.sumOf { it.value }
            val topName = cats[0].key
            val topPct = cats[0].value / total
            sb.append("② 支出集中度\n")
            sb.append(
                when {
                    topPct >= 0.40 -> "📌 「$topName」占支出 ${(topPct * 100).toInt()}%，过于集中，风险较高。\n"
                    topPct >= 0.25 -> "📌 「$topName」占支出 ${(topPct * 100).toInt()}%，可适度分散。\n"
                    else -> "📌 支出较分散，单一分类占比 ${(topPct * 100).toInt()}%，结构健康。\n"
                }
            )
        }

        // 第 3 段：省超对比（与既有线比，各分类差额）
        val origCat = ExpenseAnalyzer.categoryAmounts(rangeRecords)
        val ifCat = ExpenseAnalyzer.categoryAmounts(iflineRecords)
        val deltas = (origCat.keys + ifCat.keys).distinct().mapNotNull { name ->
            val d = (origCat[name] ?: 0.0) - (ifCat[name] ?: 0.0)
            if (abs(d) < 0.005) null else name to d
        }.sortedByDescending { it.second }
        sb.append("③ 省超对比（IF 线 vs 既有线）\n")
        if (deltas.isEmpty()) {
            sb.append("ℹ️ 各分类与既有线持平。\n")
        } else {
            deltas.firstOrNull()?.let { (name, d) ->
                if (d >= 1.0) sb.append("💪 最省「$name」${fmtMoney(d)} 元\n")
            }
            deltas.lastOrNull()?.let { (name, d) ->
                if (d <= -1.0) sb.append("📈 最多花「$name」${fmtMoney(-d)} 元\n")
            }
        }

        // 第 4 段：整体结余变化
        val origSave = ExpenseAnalyzer.summarize(rangeRecords).let { it.income - it.expense }
        val delta = save - origSave
        sb.append("④ 整体结余变化\n")
        sb.append(
            when {
                delta >= 1.0 -> "🏆 IF 线比既有线多结余 ${fmtMoney(delta)} 元，假想方案值得执行！\n"
                delta <= -1.0 -> "💡 IF 线比既有线少结余 ${fmtMoney(-delta)} 元，该方案会恶化财务，请慎用。\n"
                else -> "ℹ️ 结余与既有线基本持平。\n"
            }
        )
        return sb.toString()
    }

    /** 弹窗展示消费习惯建议（对应 C++ ShowHabitAdvice） */
    private fun showHabitAdviceDialog() {
        if (iflineRecords.isEmpty()) {
            Toast.makeText(requireContext(), "IF 线暂无记录，先复制或新增再分析", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("💡 消费习惯建议（${binding.tvRange.text}）")
            .setMessage(makeHabitAdvice())
            .setPositiveButton("知道了", null)
            .show()
    }

    // ===================== IF 线：生成现实规划（对应 C++ IfLineToPlan） =====================

    /**
     * 将 IF 线消费结构落地为现实预算方案：
     * 统计副本各分类支出与结余 → 映射为预算 6 类（餐饮/交通/购物/娱乐/学习/储蓄）
     * → 弹窗展示方案 → 一键保存到 BudgetRepository（本地立即生效 + 云同步）。
     */
    private fun ifLineToPlan() {
        if (iflineRecords.isEmpty()) {
            Toast.makeText(requireContext(), "IF 线暂无记录，先复制或新增再生成规划", Toast.LENGTH_SHORT).show()
            return
        }
        val sum = ExpenseAnalyzer.summarize(iflineRecords)
        val save = sum.income - sum.expense
        if (sum.expense <= 0 && sum.income <= 0) {
            Toast.makeText(requireContext(), "IF 线范围内没有有效金额", Toast.LENGTH_SHORT).show()
            return
        }

        // 分类支出明细（按金额降序）
        val detail = ExpenseAnalyzer.categoryAmounts(iflineRecords)
            .entries.sortedByDescending { it.value }
        val sb = StringBuilder()
        sb.append("IF 线消费结构（${binding.tvRange.text}）：\n\n")
        detail.forEach { (cat, amt) ->
            val pct = if (sum.expense > 0) (amt / sum.expense * 100).toInt() else 0
            sb.append("· $cat：${fmtMoney(amt)} 元（$pct%）\n")
        }
        sb.append("\n总收入 ${fmtMoney(sum.income)} 元 · 总支出 ${fmtMoney(sum.expense)} 元")
        sb.append("\n结余 ${fmtMoney(save)} 元")
        if (sum.income > 0) sb.append(" · 储蓄率 ${(save / sum.income * 100).toInt()}%")
        if (save > 0) sb.append("\n按此方案，约 ${ceil(1000.0 / save).toInt()} 个月可攒 1000 元应急金")

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("🎯 生成现实规划")
            .setMessage(sb.toString())
            .setPositiveButton("保存为预算方案") { _, _ -> saveIfLineAsBudget() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 将 IF 线结构映射为预算 11 类并保存（IF 线支出分类与预算分类同名，按名累加） */
    private fun saveIfLineAsBudget() {
        val app = requireActivity().application as SmartButlerApp
        val userId = app.userRepository.getCurrentUser()?.userId ?: ""

        // IF 线支出分类（餐饮/居住/交通/购物/娱乐/医疗/教育/通讯/社交人情/旅行/其他）
        // 与预算 11 类同名，按分类名直接累加；结余为储蓄结果，作为提示保留、不入分类
        val plan = LinkedHashMap<String, Double>()
        Categories.BUDGET.forEach { plan[it] = 0.0 }
        ExpenseAnalyzer.categoryAmounts(iflineRecords).forEach { (cat, amt) ->
            if (cat in plan) plan[cat] = plan.getValue(cat) + amt
        }

        viewLifecycleOwner.lifecycleScope.launch {
            app.budgetRepository.saveBudget(userId, plan)
            Toast.makeText(requireContext(), "已保存为预算方案（本地生效 + 云同步）", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 生成现实规划：将 IF 线副本的消费结构（各分类支出占比）应用到预算方案，覆盖当前用户预算。
     * 对应 C++ expense_analyzer.cpp 的 IfLineToPlan：只统计支出（收入不参与占比），
     * 占比 = 分类支出 / 总支出，归一化保证总和 = 100%。
     */
    private fun generatePlanFromIfLine() {
        if (iflineRecords.isEmpty()) {
            Toast.makeText(requireContext(), "无数据，请先添加或修改记录", Toast.LENGTH_SHORT).show()
            return
        }
        // 只统计支出：分类支出累加；收入不参与占比（对应 C++ IfLineToPlan）
        val catAmt = LinkedHashMap<String, Double>()
        var totalExp = 0.0
        iflineRecords.forEach { r ->
            if (r.type == "支出") {
                catAmt[r.category] = (catAmt[r.category] ?: 0.0) + r.amount
                totalExp += r.amount
            }
        }
        if (totalExp <= 0) {
            Toast.makeText(requireContext(), "无数据，请先添加或修改记录", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val app = requireActivity().application as SmartButlerApp
            val userId = app.userRepository.getCurrentUser()?.userId ?: ""

            // 总额基准：优先沿用当前预算总额（保持用户已定规模），无则用 IF 线总支出
            var total = if (userId.isNotBlank()) {
                app.budgetRepository.getBudgetMap(userId).values.sum()
            } else {
                0.0
            }
            if (total <= 0 && BudgetPrefs.hasBudget(requireContext())) {
                total = BudgetPrefs.total(requireContext())
            }
            if (total <= 0) total = totalExp

            // 各分类占比 = 分类支出 / 总支出（对应 C++ weights[i] = catAmt[cat] / totalExp * 100），
            // 归一化保证总和 = 100%；各分类金额 = 总额 × 占比
            val ratios = DoubleArray(Categories.BUDGET.size)
            var sum = 0.0
            Categories.BUDGET.forEachIndexed { i, name ->
                ratios[i] = (catAmt[name] ?: 0.0) / totalExp * 100
                sum += ratios[i]
            }
            val plan = LinkedHashMap<String, Double>()
            Categories.BUDGET.forEachIndexed { i, name ->
                val pct = if (sum > 0) ratios[i] / sum * 100 else 0.0
                plan[name] = total * pct / 100
            }
            app.budgetRepository.saveBudget(userId, plan)
            Toast.makeText(requireContext(), "已按 IF 线生成预算方案", Toast.LENGTH_SHORT).show()

            // 预算 Tab 的 Fragment 在 MainActivity 中复用（add + show/hide）不会自动刷新：
            // 移除缓存实例，下次切到预算 Tab（tag = "budget"）时 MainActivity 重新 add 同一实例，
            // 重新走 onCreateView → loadSavedBudget 读取最新数据
            requireActivity().supportFragmentManager.findFragmentByTag("budget")?.let {
                requireActivity().supportFragmentManager.beginTransaction().remove(it).commit()
            }
        }
    }

    // ===================== IF 线：差异分析面板 =====================

    private fun renderIfDiff() {
        val b = _binding ?: return
        b.ifDiffContainer.removeAllViews()
        if (iflineRecords.isEmpty()) {
            b.ifDiffContainer.visibility = View.GONE
            return
        }
        b.ifDiffContainer.visibility = View.VISIBLE

        val origSum = ExpenseAnalyzer.summarize(rangeRecords)
        val ifSum = ExpenseAnalyzer.summarize(iflineRecords)
        val origSave = origSum.income - origSum.expense
        val ifSave = ifSum.income - ifSum.expense
        val delta = ifSave - origSave

        addDiffLine("IF 线 vs 既有线（${binding.tvRange.text}）", color = 0xFF1A237E.toInt(), bold = true)
        addDiffLine("既有线：总支出 ${fmtMoney(origSum.expense)} 元 · 总收入 ${fmtMoney(origSum.income)} 元 · 结余 ${fmtMoney(origSave)} 元")
        addDiffLine("IF 线：总支出 ${fmtMoney(ifSum.expense)} 元 · 总收入 ${fmtMoney(ifSum.income)} 元 · 结余 ${fmtMoney(ifSave)} 元")

        if (delta >= 0.005) {
            val months = ceil(1000.0 / delta).toInt().coerceAtLeast(1)
            addDiffLine("已省下 ${fmtMoney(delta)} 元，坚持 $months 个月可攒 1000 元应急金", color = 0xFF2E7D32.toInt())
        } else if (delta <= -0.005) {
            addDiffLine("比既有线多支出 ${fmtMoney(-delta)} 元，建议削减可压缩分类", color = 0xFFC62828.toInt())
        } else {
            addDiffLine("结余与既有线持平")
        }

        // 分类支出变化 Top3（移植 C++ ComputeIfDiff：按 原始 - IF 的差额排序）
        val origCat = ExpenseAnalyzer.categoryAmounts(rangeRecords)
        val ifCat = ExpenseAnalyzer.categoryAmounts(iflineRecords)
        val deltas = (origCat.keys + ifCat.keys).distinct().mapNotNull { name ->
            val d = (origCat[name] ?: 0.0) - (ifCat[name] ?: 0.0)
            if (abs(d) < 0.005) null else name to d
        }.sortedByDescending { it.second }.take(3)
        if (deltas.isNotEmpty()) {
            addDiffLine("分类变化 Top${deltas.size}：", color = 0xFF1A237E.toInt(), bold = true)
            deltas.forEach { (name, d) ->
                addDiffLine(
                    if (d >= 0) "  $name 省下 ${fmtMoney(d)} 元" else "  $name 多花 ${fmtMoney(-d)} 元",
                    color = if (d >= 0) 0xFF2E7D32.toInt() else 0xFFC62828.toInt()
                )
            }
        }
    }

    private fun addDiffLine(text: String, color: Int = 0xFF0E7490.toInt(), bold: Boolean = false) {
        val b = _binding ?: return
        b.ifDiffContainer.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 12f
            setTextColor(color)
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(2), 0, dp(2))
        })
    }

    // ===================== 原有逻辑：小鸥 / 通用 =====================

    private fun simpleRow(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        MascotManager.removeObserver(mascotListener)
        super.onDestroyView()
        _binding = null
    }
}
