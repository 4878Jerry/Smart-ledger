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
    const val DEFAULT_BASE_URL = "http://10.21.22.205:8000/"

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

    // ===== 百度在线语音识别（在线模式，VoiceInputActivity 使用） =====
    // 在百度智能云控制台「语音技术 → 创建应用」后获取（免费额度：标准版 15000 次/日）。
    // 注意：官方 asr-sdk（com.baidu.aip:asr-sdk）托管在已关闭的 JCenter，无法从 Gradle 仓库拉取，
    // 故采用短语音识别 REST API 等价实现（BaiduAsrManager）：GET access_token + POST 音频识别。
    // 留空则语音页「在线模式」自动降级为 Vosk 离线识别，不影响原有功能。
    object BaiduAsr {
        const val APP_ID = ""          // 控制台应用 AppID（REST API 无需，保留字段）
        const val API_KEY = "f75G6QbWavxvpghgS6zysZau"         // 控制台应用 API Key
        const val SECRET_KEY = "NraFvvHtn2EcAM2GB920pnp29I0dZIi6"      // 控制台应用 Secret Key

        /** 百度 OAuth2.0 access_token 获取地址（client_credentials） */
        const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
        /** 短语音识别标准版接口（原始音频 POST，16k/16bit/单声道 PCM） */
        const val ASR_URL = "https://vop.baidu.com/server_api"
        /** dev_pid：1537=普通话（支持简单英文） */
        const val DEV_PID = "1537"
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
