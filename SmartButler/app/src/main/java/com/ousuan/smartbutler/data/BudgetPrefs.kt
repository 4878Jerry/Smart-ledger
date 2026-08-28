package com.ousuan.smartbutler.data

import android.content.Context

/** 预算方案持久化（SharedPreferences），供「预警」页读取余额预警阈值 */
object BudgetPrefs {
    private const val PREFS = "budget_prefs"

    /** 保存月预算总额与各分类预算 */
    fun save(context: Context, total: Double, categoryBudget: Map<String, Double>) {
        val ed = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        ed.putFloat(KEY_TOTAL, total.toFloat())
        categoryBudget.forEach { (name, amount) ->
            ed.putFloat("cat_$name", amount.toFloat())
        }
        ed.apply()
    }

    /** 月预算总额，未设置返回 0 */
    fun total(context: Context): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat(KEY_TOTAL, 0f).toDouble()

    /** 某分类的月预算，未设置返回 0 */
    fun category(context: Context, name: String): Double =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getFloat("cat_$name", 0f).toDouble()

    /** 当月是否已设置预算（月预算总额 > 0） */
    fun hasBudget(context: Context): Boolean = total(context) > 0

    /** 全部已设置分类的预算 Map（仅返回金额 > 0 的分类），供社区「预算方案」模块发布 */
    fun allCategoryBudgets(context: Context): Map<String, Double> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val result = linkedMapOf<String, Double>()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith("cat_") && value is Float && value > 0f) {
                result[key.removePrefix("cat_")] = value.toDouble()
            }
        }
        return result
    }

    private const val KEY_TOTAL = "total"
}
