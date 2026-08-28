package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/** 注册请求 */
data class RegisterRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("nickname") val nickname: String? = null
)

/** 注册响应：data 为注册成功的用户信息 */
data class RegisterResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: UserOut?,
    @SerializedName("msg") val msg: String?
)
