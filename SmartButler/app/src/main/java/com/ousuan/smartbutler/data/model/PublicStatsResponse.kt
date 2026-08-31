package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/**
 * 服务器公开统计数据（社区帖子）。
 * 仅含脱敏月度汇总，不含任何逐笔明细。
 */
data class PublicStatsResponse(
    @SerializedName("post_id") val postId: Long,
    @SerializedName("username") val username: String,
    @SerializedName("nickname") val nickname: String?,
    @SerializedName("month") val month: String,
    @SerializedName("total_expense") val totalExpense: Double,
    @SerializedName("category_breakdown") val categoryBreakdown: Map<String, Double>,
    @SerializedName("budget_breakdown") val budgetBreakdown: Map<String, Double> = emptyMap(),
    @SerializedName("top_category") val topCategory: String,
    @SerializedName("saving_tip") val savingTip: String?,
    @SerializedName("likes") val likes: Int,
    /** 当前登录用户是否已赞（服务器权威状态；未登录/离线时为 false） */
    @SerializedName("liked") val liked: Boolean = false,
    @SerializedName("visibility") val visibility: String = "public",
    @SerializedName("data_visibility") val dataVisibility: String = "public",
    @SerializedName("budget_visibility") val budgetVisibility: String = "public",
    @SerializedName("comments") val comments: List<CommentOut>,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)
