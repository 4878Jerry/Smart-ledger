package com.ousuan.smartbutler.util

import android.graphics.Color

/**
 * 分类定义与配色：完全参照 C++ expense_analyzer.cpp 的
 * kExpenseCategories / kIncomeCategories / kExpenseColors。
 */
object Categories {

    /** 支出分类（11 个） */
    val EXPENSE = listOf(
        "餐饮", "交通", "购物", "娱乐", "居住", "医疗", "教育",
        "通讯", "社交人情", "旅行", "其他"
    )

    /** 收入分类（7 个） */
    val INCOME = listOf(
        "工资", "奖金", "兼职", "理财", "红包", "报销", "其他"
    )

    /**
     * 预算滑块分类（11 个支出分类，对应 C++ budget_planner.cpp；
     * 顺序与默认占比表 kDefaultRatios 一致，居住 = 索引 1）。
     */
    val BUDGET = listOf(
        "餐饮", "居住", "交通", "购物", "娱乐", "医疗", "教育",
        "通讯", "社交人情", "旅行", "其他"
    )

    /** 分类 → 颜色（取自 C++ 颜色表） */
    private val COLORS = mapOf(
        "餐饮" to "#FF7043",
        "交通" to "#4FC3F7",
        "购物" to "#EC407A",
        "娱乐" to "#AB47BC",
        "居住" to "#8D6E63",
        "医疗" to "#EF5350",
        "教育" to "#5C6BC0",
        "通讯" to "#26A69A",
        "社交人情" to "#FFA726",
        "旅行" to "#42A5F5",
        "其他" to "#90A4AE",
        // 收入分类色
        "工资" to "#66BB6A",
        "奖金" to "#9CCC65",
        "兼职" to "#7CB342",
        "理财" to "#43A047",
        "红包" to "#FFCA28",
        "报销" to "#78909C",
        // 预算分类色
        "学习" to "#5C6BC0",
        "储蓄" to "#66BB6A"
    )

    /** 取分类颜色，未收录分类回退灰色 */
    fun color(name: String): Int = Color.parseColor(COLORS[name] ?: "#90A4AE")
}
