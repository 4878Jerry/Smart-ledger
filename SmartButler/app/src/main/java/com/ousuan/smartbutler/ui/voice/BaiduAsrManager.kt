package com.ousuan.smartbutler.ui.voice

import android.util.Log
import com.ousuan.smartbutler.data.network.ApiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 百度短语音识别 REST API 封装（在线模式，替代已下架的 asr-sdk）：
 * - 凭据在 [ApiConfig.BaiduAsr] 配置（API_KEY / SECRET_KEY），留空时 [isConfigured] 返回 false，调用方自动降级离线
 * - [ensureToken] 用 OAuth2.0 client_credentials 换取 access_token（有效期 30 天，内存缓存并提前一天过期）
 * - [recognize] 将 16kHz/16bit/单声道 PCM 上传 vop.baidu.com/server_api（dev_pid=1537 普通话）识别
 * - 失败返回 null，由调用方降级到 Vosk 离线识别同一段音频；全程不抛异常
 *
 * 在线模式为「停止录音后一次性识别」（REST 无流式部分结果），
 * 与 asr-sdk 的流式回调不同，但接口（configured/recognize）保持为后续替换预留。
 */
class BaiduAsrManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var accessToken: String? = null

    @Volatile
    private var tokenExpireAt = 0L

    companion object {
        private const val TAG = "BaiduAsr"
        private const val AUDIO_MIME = "audio/pcm;rate=16000"
        /** token 有效期 30 天，提前 1 天过期强制刷新，避免临界失效 */
        private const val TOKEN_SAFE_MARGIN_MS = 24 * 60 * 60 * 1000L
        /** 语音页固定 cuid（百度要求，无业务含义） */
        private const val CUID = "smartbutler-android"
    }

    /** 是否已配置有效凭据（未配置则在线模式直接降级离线） */
    val isConfigured: Boolean
        get() = ApiConfig.BaiduAsr.API_KEY.isNotBlank() && ApiConfig.BaiduAsr.SECRET_KEY.isNotBlank()

    /**
     * 获取 access_token（内存缓存 29 天）。失败返回 null，不抛异常。
     * 已在 Dispatchers.IO 上下文执行，可安全在协程中直接调用。
     */
    suspend fun ensureToken(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        accessToken?.takeIf { now < tokenExpireAt }?.let { return@withContext it }
        try {
            val url = ApiConfig.BaiduAsr.TOKEN_URL +
                "?grant_type=client_credentials" +
                "&client_id=${ApiConfig.BaiduAsr.API_KEY}" +
                "&client_secret=${ApiConfig.BaiduAsr.SECRET_KEY}"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext null
                Log.d(TAG, "获取 token: http=${resp.code}")
                val json = JSONObject(body)
                val token = json.optString("access_token", "").trim().ifEmpty { null }
                if (token == null) {
                    Log.e(TAG, "获取 token 失败: ${json.optString("error_description", body)}")
                    return@withContext null
                }
                val expiresIn = json.optLong("expires_in", 2_592_000L) * 1000
                accessToken = token
                tokenExpireAt = now + expiresIn - TOKEN_SAFE_MARGIN_MS
                token
            }
        } catch (t: Throwable) {
            Log.e(TAG, "获取 token 异常: ${t.javaClass.name}: ${t.message}", t)
            null
        }
    }

    /**
     * 识别一段 PCM（16kHz/16bit/单声道），返回文本；失败（网络/token/服务错误/无结果）返回 null。
     * 无结果或错误时已打日志，调用方据此降级 Vosk。
     */
    suspend fun recognize(pcm: ByteArray): String? = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) {
            Log.w(TAG, "音频数据为空，跳过识别")
            return@withContext null
        }
        try {
            var token = ensureToken() ?: return@withContext null
            var result = recognizeOnce(pcm, token)
            // token 失效（err_no=3302 或 3312）时强制刷新重试一次
            if (result == null && lastErrNo == 3302 || result == null && lastErrNo == 3312) {
                accessToken = null
                tokenExpireAt = 0
                token = ensureToken() ?: return@withContext null
                result = recognizeOnce(pcm, token)
            }
            result
        } catch (t: Throwable) {
            Log.e(TAG, "识别异常: ${t.javaClass.name}: ${t.message}", t)
            null
        }
    }

    /** 最近一次请求的百度 err_no（供 token 失效重试判断） */
    @Volatile
    private var lastErrNo = 0

    private fun recognizeOnce(pcm: ByteArray, token: String): String? {
        val url = ApiConfig.BaiduAsr.ASR_URL +
            "?cuid=$CUID&token=$token&dev_pid=${ApiConfig.BaiduAsr.DEV_PID}"
        val body = pcm.toRequestBody(AUDIO_MIME.toMediaType())
        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", AUDIO_MIME)
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: return null
            Log.d(TAG, "识别响应: http=${resp.code} body=$text")
            val json = JSONObject(text)
            lastErrNo = json.optInt("err_no", -1)
            if (json.optInt("err_no") != 0) {
                Log.e(TAG, "识别失败: err_no=${json.optInt("err_no")} err_msg=${json.optString("err_msg")}")
                return null
            }
            val arr = json.optJSONArray("result") ?: return null
            val r = arr.optString(0, "").trim()
            return r.ifEmpty { null }
        }
    }
}
