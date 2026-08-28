package com.ousuan.smartbutler.data.network

import android.util.Log
import com.google.gson.GsonBuilder
import com.ousuan.smartbutler.data.repository.UserException
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Retrofit 客户端：
 * - Base URL 使用 [ApiConfig.BASE_URL]（不硬编码）；
 * - OkHttp 配置连接/读写超时、JWT 自动携带拦截器、日志打印拦截器；
 * - [safeApiCall] 统一处理网络异常（超时、连接失败、HTTP 错误），返回友好提示。
 */
object ApiClient {

    private val logger = HttpLoggingInterceptor { message ->
        Log.d("SmartButler-API", message)
    }.apply {
        level = HttpLoggingInterceptor.Level.BODY // 打印请求/响应体，方便调试
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(AuthInterceptor())      // 自动携带 JWT token
            .addInterceptor(logger)                  // 日志（打印请求 URL 与响应状态）
            .build()
    }

    /** 当前 Retrofit 服务实例；服务器地址变更后调用 [rebuildService] 重建（旧实例丢弃，无需重启） */
    @Volatile
    var service: ApiService = createService(ApiConfig.getBaseUrl())
        private set

    /** 用当前保存的服务器地址重建 Retrofit 实例，所有调用点自动切换到新地址 */
    fun rebuildService() {
        service = createService(ApiConfig.getBaseUrl())
        Log.d("SmartButler-API", "已重建 Retrofit 实例，baseUrl=${ApiConfig.getBaseUrl()}")
    }

    private fun createService(baseUrl: String): ApiService =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
            .build()
            .create(ApiService::class.java)

    /**
     * 统一网络调用封装：捕获所有网络异常并转换为 [UserException]（message 可直接 Toast）。
     * - 连接失败 / 超时 → 友好提示；
     * - HTTP 错误 → 优先提取服务器返回的 msg（如「用户名或密码错误」），否则显示状态码。
     */
    suspend fun <T> safeApiCall(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: HttpException) {
        val serverMsg = e.response()?.errorBody()?.string()?.let { extractMsg(it) }
        val msg = serverMsg ?: "请求失败（HTTP ${e.code()}）"
        Result.failure(UserException(msg))
    } catch (e: IOException) {
        Log.w("SmartButler-API", "网络异常", e)
        Result.failure(UserException("网络连接失败，请检查网络或服务器地址"))
    } catch (e: Exception) {
        Log.w("SmartButler-API", "请求异常", e)
        Result.failure(UserException(e.message ?: "请求失败，请稍后重试"))
    }

    /** 从统一返回体 {code, data, msg} 提取 msg */
    private fun extractMsg(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching { JSONObject(body).optString("msg").ifBlank { null } }.getOrNull()
    }
}
