package com.ousuan.smartbutler.data.model

/**
 * 对外公开的脱敏统计数据：仅包含「月度汇总统计」，
 * 不包含任何逐笔明细，保护用户隐私。
 */
data class PublicStats(
    /** 公开者用户名 */
    val username: String,
    /** 统计月份 yyyy-MM */
    val month: String,
    /** 该月总支出（元） */
    val totalExpense: Double,
    /** 分类 → 金额（仅支出分类） */
    val categoryBreakdown: Map<String, Double>,
    /** 分类 → 预算金额（预算方案模块，仅发布时使用；消费统计本身不含此字段） */
    val budgetBreakdown: Map<String, Double> = emptyMap(),
    /** 支出最高的分类 */
    val topCategory: String,
    /** 省钱建议（可选） */
    val savingTip: String?
)
