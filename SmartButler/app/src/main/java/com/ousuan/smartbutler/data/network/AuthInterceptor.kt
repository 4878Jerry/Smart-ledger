package com.ousuan.smartbutler.data.network

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 认证拦截器：通过 [TokenManager] 统一读取 JWT token（SharedPreferences 持久化，
 * 进程重启后仍有效），自动为所有请求添加 Authorization: Bearer {token} 头。
 * 未登录（无 token）时保持原请求（公开接口照常访问）。
 */
class AuthInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = TokenManager.getToken()
        Log.d("Auth", "读取token的key: token, token: ${token?.take(20)}...")
        val request = if (token.isNullOrEmpty()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
