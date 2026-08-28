package com.ousuan.smartbutler.data.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 百度 NLP 在线文本纠错（ecnet 接口）：
 * - 在 Vosk 识别出文本后叠加一层在线纠错，修正同音错字（「一白领五」→「一百零五」），
 *   纠错后的文本再交给 ParseUtils 提取金额与分类；
 * - 断网 / 未配置 Key / 接口异常时返回 null，调用方直接使用 Vosk 原始结果，**不影响离线流程**；
 * - Access Token 通过 API Key / Secret Key 换取（有效期约 30 天，进程内缓存，到期自动刷新）。
 *
 * 接口文档：https://cloud.baidu.com/doc/NLP/s/ak3pmxw9f
 */
object BaiduNlpCorrector {

    private const val TAG = "BaiduNlp"

    // 百度智能云 NLP 控制台创建应用后获得的 Key
    private const val API_KEY = "Aw8E67S4nJXsVday5wUgS3gf"
    private const val SECRET_KEY = "eONy8ucwGcSH6GIDFSlxrqR8rh8uhONE"

    private const val TOKEN_URL = "https://aip.baidubce.com/oauth/2.0/token"
    private const val CORRECT_URL = "https://aip.baidubce.com/rest/2.0/nlp/v1/ecnet"

    private const val REQUEST_TIMEOUT_MS = 5000L

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    /** 进程内缓存的 access_token 及其过期时间戳（毫秒），过期自动重新换取 */
    @Volatile
    private var cachedToken: String? = null
    @Volatile
    private var tokenExpireAt: Long = 0L

    /**
     * 对识别文本做在线纠错。
     * @return 纠错后的文本（可能与原文本相同）；断网 / 未配置 Key / 接口失败返回 null，
     *         调用方应回退使用 Vosk 原始识别结果。
     */
    suspend fun correct(text: String): String? {
        if (text.isBlank()) return null
        if (API_KEY.startsWith("在此填入") || SECRET_KEY.startsWith("在此填入")) {
            Log.w(TAG, "未配置百度 API Key/Secret Key，跳过在线纠错")
            return null
        }
        if (!NetworkMonitor.isConnected()) {
            Log.d(TAG, "当前断网，跳过在线纠错，使用 Vosk 原始结果")
            return null
        }
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                try {
                    val token = fetchAccessToken() ?: return@withTimeoutOrNull null
                    val request = Request.Builder()
                        .url("$CORRECT_URL?access_token=$token")
                        .post(FormBody.Builder().add("text", text).build())
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            Log.w(TAG, "纠错接口响应异常: HTTP ${resp.code}")
                            return@use null
                        }
                        val json = gson.fromJson(resp.body?.string(), JsonObject::class.java)
                            ?: return@use null
                        // 百度返回错误码（110 token 无效 / 282004 参数错误 等）时清缓存并降级
                        if (json.has("error_code")) {
                            val code = json.get("error_code").asString
                            Log.w(TAG, "纠错接口返回错误: code=$code, msg=${json.get("error_msg")?.asString}")
                            if (code == "110" || code == "111" || code == "31003") {
                                cachedToken = null // token 失效，下次重新换取
                            }
                            return@use null
                        }
                        val corrected = applyCorrections(text, json)
                        Log.d(TAG, "百度纠错: \"$text\" -> \"$corrected\"")
                        corrected
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "百度纠错异常: ${e.javaClass.simpleName}: ${e.message}")
                    null
                }
            }
        }
    }

    /**
     * 按接口返回的 items 把原文中的错别字片段替换为正确片段。
     * 先按 begin_pos 从大到小倒序替换，避免前面替换后位置漂移。
     */
    private fun applyCorrections(original: String, json: JsonObject): String {
        val items = json.getAsJsonArray("items") ?: return original
        val replacements = mutableListOf<Triple<Int, Int, String>>()
        for (element in items) {
            val obj = element.asJsonObject
            val begin = obj.get("begin_pos")?.asInt ?: continue
            val end = obj.get("end_pos")?.asInt ?: continue
            val frag = obj.get("correct_frag")?.asString ?: continue
            if (begin in 0 until original.length && end in begin..original.length) {
                replacements += Triple(begin, end, frag)
            }
        }
        replacements.sortByDescending { it.first }
        val sb = StringBuilder(original)
        for ((begin, end, frag) in replacements) {
            sb.replace(begin, end, frag)
        }
        return sb.toString()
    }

    /** 用 API Key/Secret Key 换取 access_token（30 天有效，进程内缓存，提前 5 分钟过期刷新） */
    private suspend fun fetchAccessToken(): String? {
        val now = System.currentTimeMillis()
        cachedToken?.let { if (now < tokenExpireAt) return it }
        val url = "$TOKEN_URL?grant_type=client_credentials" +
            "&client_id=$API_KEY&client_secret=$SECRET_KEY"
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "获取百度 token 失败: HTTP ${resp.code}")
                return null
            }
            val json = gson.fromJson(resp.body?.string(), JsonObject::class.java) ?: return null
            val token = json.get("access_token")?.asString ?: run {
                Log.w(TAG, "百度 token 响应缺少 access_token: $json")
                return null
            }
            val expiresIn = json.get("expires_in")?.asLong ?: 2_592_000L
            cachedToken = token
            tokenExpireAt = now + (expiresIn - 300) * 1000L // 提前 5 分钟视为过期
            Log.d(TAG, "获取百度 token 成功（${expiresIn}s 后过期）")
            return token
        }
    }
}

/*
 * 百度 API Key / Secret Key 申请步骤（https://cloud.baidu.com）：
 * 1. 注册并登录百度智能云，完成「实名认证」（个人实名即可，免费）；
 * 2. 控制台 → 产品服务 → 人工智能 → 自然语言处理 NLP，开通「文本纠错」服务
 *    （有免费调用额度，如每月 1000 次，超出后按量计费，QPS 限制约 2）；
 * 3. 控制台 → 安全认证 → 应用列表 → 「创建应用」，
 *    勾选需要的接口权限（自然语言处理 → 文本纠错），创建后即可看到 API Key 和 Secret Key；
 * 4. 将两个 Key 填入上方 API_KEY / SECRET_KEY 常量即可。
 * 注意：硬编码 Key 会随 APK 被反编译泄露，正式发布建议改为服务端转发或本地动态下发。
 */
