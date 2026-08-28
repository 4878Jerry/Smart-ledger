package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/**
 * 上传单条交易记录。
 * [localId] 为 Android 端本地标识（本地数据库自增 id 对应的稳定 ID），
 * 服务器按 localId 幂等去重，重复上传不会产生重复记录。
 */
data class TransactionRequest(
    @SerializedName("localId") val localId: String,
    @SerializedName("amount") val amount: Double,
    @SerializedName("category") val category: String,
    @SerializedName("type") val type: String,
    @SerializedName("note") val note: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("is_public") val isPublic: Boolean
)
