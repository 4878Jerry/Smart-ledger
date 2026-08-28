package com.ousuan.smartbutler.data

import android.content.Context

/**
 * 「数据公开」开关持久化（SharedPreferences），按 userId 隔离：
 * - 存储 key 为 `is_data_public_${userId}`，每个账号独立存储；
 * - 未设置过的账号默认关闭；
 * - 开关变化只影响当前账号，不影响其他账号。
 */
object DataPublicPrefs {

    /** SharedPreferences 文件名（与旧实现一致，供其它页面注册跨页监听） */
    const val PREFS = "user_prefs"

    private const val KEY_PREFIX = "is_data_public_"
    private const val KEY_PENDING_PREFIX = "is_data_public_pending_"

    /** 某账号的存储 key */
    fun key(userId: String): String = "$KEY_PREFIX$userId"

    /** 某账号「待同步」标志 key（离线时本地已改，待网络恢复推送服务器） */
    fun pendingKey(userId: String): String = "$KEY_PENDING_PREFIX$userId"

    /** 判断该 key 是否为「数据公开」开关的 key（监听器过滤用） */
    fun isDataPublicKey(key: String?): Boolean = key?.startsWith(KEY_PREFIX) == true

    /** 当前账号是否开启数据公开；未登录或从未设置过默认返回 false */
    fun isPublic(context: Context, userId: String?): Boolean {
        if (userId.isNullOrBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(key(userId), false)
    }

    /** 设置当前账号的数据公开开关（只写入该账号的 key，不影响其他账号） */
    fun setPublic(context: Context, userId: String, isPublic: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key(userId), isPublic)
            .apply()
    }

    /** 当前账号是否有「待同步」的开关状态（离线时本地已改，待网络恢复补推服务器） */
    fun isPendingSync(context: Context, userId: String?): Boolean {
        if (userId.isNullOrBlank()) return false
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(pendingKey(userId), false)
    }

    /** 标记当前账号开关状态是否待同步（true=需要补推服务器，false=已同步） */
    fun setPendingSync(context: Context, userId: String, pending: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(pendingKey(userId), pending)
            .apply()
    }
}
