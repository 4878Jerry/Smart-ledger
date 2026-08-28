package com.ousuan.smartbutler.data.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * JWT token 统一管理器：
 * - 登录成功写入、[AuthInterceptor] 读取、登出清除，key 全局唯一（避免各模块 key 不一致）；
 * - 持久化到 SharedPreferences，**进程重启后仍可读取**（修复 AuthInterceptor 只读内存缓存导致 401 的问题）；
 * - 由 [com.ousuan.smartbutler.SmartButlerApp] 在 onCreate 中调用 [init] 完成初始化（幂等）。
 */
object TokenManager {

    private const val PREFS = "user_prefs"
    private const val KEY_TOKEN = "token"
    private const val TAG = "TokenManager"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 必须在 Application.onCreate 中调用，幂等 */
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            Log.d(TAG, "初始化完成, prefs: $PREFS, key: $KEY_TOKEN")
        }
    }

    /** 保存 token（登录成功后调用） */
    fun saveToken(token: String) {
        Log.d(TAG, "保存token, key: $KEY_TOKEN, token: ${token.take(20)}...")
        prefs()?.edit()?.putString(KEY_TOKEN, token)?.apply()
    }

    /** 读取当前 token，未登录返回 null；优先读 SharedPreferences（进程重启后仍有效） */
    fun getToken(): String? {
        val token = prefs()?.getString(KEY_TOKEN, null)
        Log.d(TAG, "读取token, key: $KEY_TOKEN, token: ${token?.take(20)}...")
        return token
    }

    /** 清除 token（登出时调用） */
    fun clearToken() {
        Log.d(TAG, "清除token, key: $KEY_TOKEN")
        prefs()?.edit()?.remove(KEY_TOKEN)?.apply()
    }

    private fun prefs(): SharedPreferences? {
        if (prefs == null) {
            Log.w(TAG, "TokenManager 未初始化，请确认 Application.onCreate 中已调用 TokenManager.init(context)")
        }
        return prefs
    }
}
