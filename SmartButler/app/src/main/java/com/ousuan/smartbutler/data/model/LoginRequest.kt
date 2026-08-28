package com.ousuan.smartbutler.data.model

import com.google.gson.annotations.SerializedName

/** 登录请求 */
data class LoginRequest(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

/** 登录响应 data：JWT token + 用户信息 */
data class LoginData(
    @SerializedName("token") val token: String,
    @SerializedName("user") val user: UserOut?
)

/** 登录响应 */
data class LoginResponse(
    @SerializedName("code") val code: Int,
    @SerializedName("data") val data: LoginData?,
    @SerializedName("msg") val msg: String?
)
