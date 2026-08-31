package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/**
 * 统一响应包装：所有接口返回 { code, data, msg }。
 * code == 0 表示成功；否则 msg 为错误信息。
 */
data class ApiResponse<T>(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: T?,
    @SerializedName("msg") val msg: String?
)

/** 服务器用户信息 */
data class UserOut(
    @SerializedName("id") val id: Long,
    @SerializedName("username") val username: String,
    @SerializedName("nickname") val nickname: String?,
    /** 用户级全局数据公开开关：1=公开，0=不公开 */
    @SerializedName("is_data_public") val isDataPublic: Int = 1,
    @SerializedName("created_at") val createdAt: String
)

/** 服务器交易记录（下拉同步 / 列表用） */
data class TransactionOut(
    @SerializedName("id") val id: Long,
    /** 本地 UUID：下载时用于本地去重（重装 App 后恢复记录不重复） */
    @SerializedName("local_id") val localId: String?,
    @SerializedName("amount") val amount: Double,
    @SerializedName("category") val category: String,
    @SerializedName("type") val type: String,
    @SerializedName("note") val note: String,
    @SerializedName("timestamp") val timestamp: Long,
    @SerializedName("is_public") val isPublic: Boolean
)

/** 点赞接口响应 data：最新点赞数 + 当前用户是否已赞（服务器权威状态，客户端据此校准本地状态） */
data class LikeData(
    @SerializedName("likes") val likes: Int,
    @SerializedName("liked") val liked: Boolean = false
)

data class LikeResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: LikeData?,
    @SerializedName("msg") val msg: String?
)
