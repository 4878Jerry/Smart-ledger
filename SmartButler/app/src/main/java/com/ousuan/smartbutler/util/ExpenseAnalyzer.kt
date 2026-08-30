package com.ousuan.smartbutler.util

import com.ousuan.smartbutler.data.Transaction

/**
 * 消费分析引擎：对应 C++ expense_analyzer.cpp 中的统计、预警与人格画像逻辑（简化移植）。
 */
object ExpenseAnalyzer {

    data class Summary(val income: Double, val expense: Double, val balance: Double)

    /** 汇总：收入 / 支出 / 结余 */
    fun summarize(records: List<Transaction>): Summary {
        val income = records.filter { it.type == "收入" }.sumOf { it.amount }
        val expense = records.filter { it.type == "支出" }.sumOf { it.amount }
        return Summary(income, expense, income - expense)
    }

    /** 支出分类金额汇总（仅统计支出） */
    fun categoryAmounts(records: List<Transaction>): Map<String, Double> {
        val map = linkedMapOf<String, Double>()
        records.filter { it.type == "支出" }.forEach {
            map[it.category] = (map[it.category] ?: 0.0) + it.amount
        }
        return map
    }

    /** 每日支出趋势（按日期升序，供折线图） */
    fun dailyTrend(records: List<Transaction>): List<Pair<String, Double>> {
        val map = sortedMapOf<String, Double>()
        records.filter { it.type == "支出" }.forEach {
            map[it.date] = (map[it.date] ?: 0.0) + it.amount
        }
        return map.toList()
    }

    /** 消费人格画像：按占比最高的支出分类给标签（对应 C++ pickArchetype） */
    fun personalityTag(records: List<Transaction>): String {
        val cats = categoryAmounts(records)
        if (cats.isEmpty()) return "记账新手"
        val top = cats.maxByOrNull { it.value }?.key ?: return "记账新手"
        return when (top) {
            "餐饮" -> "美食家"
            "购物" -> "购物达人"
            "娱乐" -> "娱乐玩家"
            "居住" -> "居家宅"
            "旅行" -> "旅行家"
            "医疗" -> "养生达人"
            "教育" -> "终身学习者"
            "社交人情" -> "社交达人"
            "交通" -> "通勤战士"
            else -> "随心主义者"
        }
    }

    /** AI 消费建议（硬编码规则，先给几条示例） */
    fun aiAdvice(records: List<Transaction>): String {
        if (records.isEmpty()) return "开始记账吧，先记录一笔消费，我会为你分析消费习惯"
        val s = summarize(records)
        val cats = categoryAmounts(records)
        val total = cats.values.sum()
        if (total <= 0) return "暂无支出数据，记下第一笔支出后我会给出建议"
        val top = cats.maxByOrNull { it.value }?.key ?: return ""
        val topRatio = if (total > 0) (cats[top] ?: 0.0) / total else 0.0
        val rate = if (s.income > 0) s.balance / s.income else null
        return when {
            s.balance < 0 -> "本月入不敷出，支出超过收入，建议暂停非必要消费并盘点固定开销"
            rate != null && rate < 0.1 -> "储蓄率偏低（${(rate * 100).toInt()}%），建议发薪日先存后花"
            top == "餐饮" && topRatio > 0.3 -> "本月餐饮支出偏高（占比 ${(topRatio * 100).toInt()}%），建议减少外卖，多自己做饭"
            top == "购物" && topRatio > 0.2 -> "购物支出占比较高，建议下单前设置 24 小时冷静期"
            top == "娱乐" && topRatio > 0.15 -> "娱乐消费偏高，可以试试免费的公园散步或图书馆自习"
            top == "交通" && topRatio > 0.15 -> "交通支出偏高，短途出行可以多选公交或骑行"
            top == "旅行" -> "旅行消费可观，记得提前做预算，避免超支影响生活"
            else -> "本月消费结构比较均衡，继续保持！适当储蓄让钱包更安心"
        }
    }
}
