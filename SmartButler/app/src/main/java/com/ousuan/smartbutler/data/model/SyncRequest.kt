package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/** 批量同步请求 */
data class SyncRequest(
    @SerializedName("transactions") val transactions: List<TransactionRequest>
)

/** 同步结果 data */
data class SyncData(
    @SerializedName("synced_count") val syncedCount: Int,
    @SerializedName("failed_ids") val failedIds: List<String>
)

/** 同步响应 */
data class SyncResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: SyncData?,
    @SerializedName("msg") val msg: String?
)
