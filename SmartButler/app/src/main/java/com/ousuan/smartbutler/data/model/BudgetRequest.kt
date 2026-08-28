package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/**
 * 预算同步 DTO：
 * 服务器 users.budget_json 存储 {分类: 金额} 的 JSON，Android 端整包上传 / 整包下载。
 */

/** 预算整体上传（PUT /api/users/budget） */
data class BudgetUpdateRequest(
    @SerializedName("budget") val budget: Map<String, Double>
)

/** 预算响应 data（GET/PUT /api/users/budget 均返回该结构） */
data class BudgetData(
    @SerializedName("budget") val budget: Map<String, Double> = emptyMap()
)
