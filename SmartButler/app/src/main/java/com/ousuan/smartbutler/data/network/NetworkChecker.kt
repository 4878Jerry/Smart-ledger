package com.ousuan.smartbutler.data.network

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 服务器连通性检测：在指定超时时间内尝试访问当前 Base URL，
 * 能建立连接并收到有效响应即视为服务器可用。
 */
object NetworkChecker {

    private const val TAG = "NetworkChecker"

    /**
     * 检查服务器是否可用。
     * @param timeoutMs 超时时间（毫秒），默认 3000ms
     * @return 连通返回 true；不可达 / 超时 / 解析失败均返回 false（不抛异常）
     */
    suspend fun checkServerAvailable(timeoutMs: Long = 3000): Boolean {
        // 优先使用系统级网络状态：完全断网时直接返回 false，不发起网络请求
        if (!NetworkMonitor.isConnected()) {
            Log.d(TAG, "系统网络不可用，跳过服务器检测")
            return false
        }
        val baseUrl = ApiConfig.getBaseUrl()
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                try {
                    val conn = URL(baseUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = timeoutMs.toInt()
                    conn.readTimeout = timeoutMs.toInt()
                    conn.instanceFollowRedirects = true
                    conn.requestMethod = "GET"
                    val code = conn.responseCode // 发起连接并读取响应码
                    conn.disconnect()
                    code in 200..399
                } catch (e: Exception) {
                    Log.w(TAG, "服务器不可达: $baseUrl, 原因: ${e.message}")
                    false
                }
            } ?: run {
                Log.w(TAG, "服务器检测超时（${timeoutMs}ms）: $baseUrl")
                false
            }
        }
    }
}
