package com.ousuan.smartbutler.data.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * 网络层统一配置：服务器地址与 API 端点。
 * 服务器地址默认 [DEFAULT_BASE_URL]，用户可在「我的 → 服务器设置」中修改并持久化，
 * 保存后立即重建 Retrofit 实例生效（无需重启应用）。
 */
object ApiConfig {

    private const val PREFS = "user_prefs"
    private const val KEY_BASE_URL = "server_url"
    private const val TAG = "ApiConfig"

    /** 默认服务器地址（未在「服务器设置」中修改时使用） */
    const val DEFAULT_BASE_URL = "http://10.126.126.1:8000/"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 必须在 Application.onCreate 中调用（幂等），其余模块通过 [getBaseUrl] 读取 */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    /** 当前服务器地址：优先用户保存值，未设置过则用默认值（尾部保证以 / 结尾） */
    fun getBaseUrl(): String {
        val saved = prefs?.getString(KEY_BASE_URL, null)?.trim()
        val url = if (saved.isNullOrEmpty()) DEFAULT_BASE_URL else saved
        return if (url.endsWith("/")) url else "$url/"
    }

    /** 保存服务器地址并立即重建 Retrofit 实例（无需重启） */
    fun saveBaseUrl(url: String) {
        val normalized = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        prefs?.edit()?.putString(KEY_BASE_URL, normalized)?.apply()
        ApiClient.rebuildService()
        Log.d(TAG, "服务器地址已保存并生效: $normalized")
    }

    /** 恢复默认地址（未设置时直接清空保存值） */
    fun resetBaseUrl() {
        prefs?.edit()?.remove(KEY_BASE_URL)?.apply()
        ApiClient.rebuildService()
        Log.d(TAG, "已恢复默认服务器地址: $DEFAULT_BASE_URL")
    }

    // ===== API 端点 =====
    const val LOGIN = "api/login"
    const val REGISTER = "api/register"
    const val USER_SETTINGS = "api/users/settings"
    const val USER_BUDGET = "api/users/budget"
    const val TRANSACTIONS = "api/transactions"
    const val SYNC = "api/transactions/sync"
    const val PUBLIC_STATS = "api/stats/public"
    const val POSTS = "api/posts"
    const val COMMENTS = "api/comments"
    const val LIKE = "api/like"
}
