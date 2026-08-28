package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/** 发布月度统计到社区的请求 */
data class PostRequest(
    @SerializedName("month") val month: String,
    @SerializedName("total_expense") val totalExpense: Double,
    @SerializedName("category_breakdown") val categoryBreakdown: Map<String, Double>,
    /** 预算方案（可选）：为空/null 表示本帖子不发布预算数据 */
    @SerializedName("budget_breakdown") val budgetBreakdown: Map<String, Double>? = null,
    @SerializedName("top_category") val topCategory: String,
    @SerializedName("saving_tip") val savingTip: String,
    /** 帖子级可见度：public 公开 / private 仅自己可见（默认 public） */
    @SerializedName("visibility") val visibility: String = "public",
    /** 消费数据模块可见度：public 公开 / private 仅自己可见（默认 public） */
    @SerializedName("data_visibility") val dataVisibility: String = "public",
    /** 预算方案模块可见度：public 公开 / private 仅自己可见（默认 public） */
    @SerializedName("budget_visibility") val budgetVisibility: String = "public",
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/** 帖子可见度更新请求（PUT /api/posts/{id}）：各字段可选，不传则保持原值 */
data class PostUpdateRequest(
    /** 帖子级可见度：public 公开 / private 仅自己可见 */
    @SerializedName("visibility") val visibility: String? = null,
    /** 消费数据模块可见度：public 公开 / private 仅自己可见 */
    @SerializedName("data_visibility") val dataVisibility: String? = null,
    /** 预算方案模块可见度：public 公开 / private 仅自己可见 */
    @SerializedName("budget_visibility") val budgetVisibility: String? = null
)

/** 发布成功 data */
data class CreatePostData(
    @SerializedName("post_id") val postId: Long,
    @SerializedName("created_at") val createdAt: String
)

/** 发布响应 */
data class CreatePostResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: CreatePostData?,
    @SerializedName("msg") val msg: String?
)
