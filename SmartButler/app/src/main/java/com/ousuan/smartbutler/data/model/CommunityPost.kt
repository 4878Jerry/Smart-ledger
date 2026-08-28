package com.ousuan.smartbutler.data.model

/**
 * 社区帖子：仅含脱敏月度统计（无逐笔明细）。
 * 模拟数据与用户发布的真实统计共用此结构。
 */
data class CommunityPost(
    /** 帖子 ID（UUID） */
    val postId: String,
    /** 发布者用户名 */
    val username: String,
    /** 统计月份 yyyy-MM */
    val month: String,
    /** 月度总支出（元） */
    val totalExpense: Double,
    /** 分类 → 金额（仅支出分类，消费数据模块；为空表示只发预算方案） */
    val categoryBreakdown: Map<String, Double>,
    /** 分类 → 预算金额（预算方案模块；为空表示不包含预算） */
    val budgetBreakdown: Map<String, Double> = emptyMap(),
    /** 支出最高的分类 */
    val topCategory: String,
    /** 省钱建议（可选） */
    val savingTip: String?,
    /** 点赞数 */
    val likes: Int,
    /** 评论列表 */
    val comments: List<CommunityComment>,
    /** 首次发布时间（毫秒时间戳） */
    val timestamp: Long,
    /** 最近更新时间（毫秒时间戳），首次发布时等于 timestamp */
    val updatedAt: Long = timestamp,
    /** 帖子级可见度：public 公开 / private 仅自己可见 */
    val visibility: String = "public",
    /** 消费数据模块可见度：public 公开 / private 仅自己可见 */
    val dataVisibility: String = "public",
    /** 预算方案模块可见度：public 公开 / private 仅自己可见 */
    val budgetVisibility: String = "public"
)

/** 帖子评论 */
data class CommunityComment(
    /** 评论 ID（UUID） */
    val commentId: String,
    /** 评论者用户名 */
    val username: String,
    /** 评论内容 */
    val content: String,
    /** 评论时间（毫秒时间戳） */
    val timestamp: Long
)
