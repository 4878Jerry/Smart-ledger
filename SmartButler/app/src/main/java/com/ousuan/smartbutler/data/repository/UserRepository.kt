package com.ousuan.smartbutler.data.repository

import android.content.Context
import android.util.Log
import com.ousuan.smartbutler.data.DataPublicPrefs
import com.ousuan.smartbutler.data.model.LoginRequest
import com.ousuan.smartbutler.data.model.RegisterRequest
import com.ousuan.smartbutler.data.model.User
import com.ousuan.smartbutler.data.model.UserOut
import com.ousuan.smartbutler.data.model.UserSettingsRequest
import com.ousuan.smartbutler.data.network.ApiClient
import com.ousuan.smartbutler.data.network.NetworkMonitor
import com.ousuan.smartbutler.data.network.TokenManager
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException

/** 账号异常，message 可直接用于 Toast 提示 */
class UserException(message: String) : Exception(message)

/**
 * 账号仓库：
 * - 登录 / 注册优先请求服务器（FastAPI 后端），成功后保存 JWT token 与用户信息；
 * - 服务器不可达（离线）时降级到本地模拟账号，保证功能可用；
 * - token 统一由 [TokenManager] 管理，[AuthInterceptor] 自动携带到后续请求。
 */
class UserRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 内存「本地模拟用户库」，启动时从本地恢复（离线降级用） */
    private val users: MutableList<User> = loadUsers()

    /**
     * 登录成功回调（由 Application 注入）：登录成功后自动从服务器下载该用户数据
     * （消费记录 + 我的帖子）到本地，实现「重装 App 后数据恢复」。
     */
    var onLoginSuccess: ((User) -> Unit)? = null

    /**
     * 最近一次登录是否为「离线降级登录」（本地模拟账号，无有效 JWT token）。
     * 离线登录录入的数据只能保存在本地，无法同步到服务器；
     * UI 据此提示用户「联网后请重新登录以同步」，避免误以为已上传成功。
     */
    @Volatile
    var lastLoginOffline: Boolean = false

    // ---------- 登录 / 注册 ----------

    /** 登录：优先服务器，失败降级本地模拟账号 */
    suspend fun login(username: String, password: String): Result<User> {
        val name = username.trim()
        // 系统断网：跳过服务器请求，直接本地离线登录（无需联网验证、不等待超时、不报网络错误）
        if (!NetworkMonitor.isConnected()) {
            Log.d(TAG, "当前无可用网络，直接本地离线登录: $name")
            return localLogin(name, password)
        }
        try {
            val resp = ApiClient.service.login(LoginRequest(name, password))
            if (resp.code == 0 && resp.data?.token?.isNotEmpty() == true && resp.data.user != null) {
                val user = resp.data.user.toLocalUser()
                TokenManager.saveToken(resp.data.token)
                lastLoginOffline = false
                saveCurrentUser(user)
                rememberLocalUser(user, password)
                // 登录成功后自动拉取服务器数据（记录 + 帖子），实现重装恢复
                onLoginSuccess?.invoke(user)
                return Result.success(user)
            }
            return Result.failure(UserException(resp.msg ?: "登录失败"))
        } catch (e: HttpException) {
            // 服务器明确拒绝（如用户名或密码错误）：返回服务器 msg
            val serverMsg = e.response()?.errorBody()?.string()?.let { extractMsg(it) }
            return Result.failure(UserException(serverMsg ?: "登录失败（HTTP ${e.code()}）"))
        } catch (e: Exception) {
            // 任何网络异常（连接失败 / 超时 / 解析等）都降级本地模拟账号，避免提示「网络错误」
            if (e is CancellationException) throw e
            Log.w(TAG, "服务器登录失败(${e.javaClass.simpleName}: ${e.message})，降级本地登录")
            return localLogin(name, password)
        }
    }

    /** 注册：优先服务器，失败降级本地模拟账号 */
    suspend fun register(
        username: String,
        password: String,
        nickname: String? = null
    ): Result<User> {
        val name = username.trim()
        // 系统断网：跳过服务器请求，直接本地注册（离线也能创建本地账号，供离线登录使用）
        if (!NetworkMonitor.isConnected()) {
            Log.d(TAG, "当前无可用网络，直接本地注册: $name")
            return localRegister(name, password, nickname)
        }
        try {
            val resp = ApiClient.service.register(
                RegisterRequest(name, password, nickname)
            )
            if (resp.code == 0 && resp.data != null) {
                val user = resp.data.toLocalUser()
                rememberLocalUser(user, password)
                // 注册成功不自动登录（UI 跳转登录页）：清掉可能残留的旧账号 token，
                // 避免后续 sync 用旧 token 把新账号数据串写到别的账号名下
                TokenManager.clearToken()
                lastLoginOffline = false
                return Result.success(user)
            }
            return Result.failure(UserException(resp.msg ?: "注册失败"))
        } catch (e: HttpException) {
            val serverMsg = e.response()?.errorBody()?.string()?.let { extractMsg(it) }
            return Result.failure(UserException(serverMsg ?: "注册失败（HTTP ${e.code()}）"))
        } catch (e: IOException) {
            return localRegister(name, password, nickname)
        }
    }

    /** 退出登录：清除 token 与当前登录用户（不删除账号） */
    fun logout() {
        TokenManager.clearToken()
        lastLoginOffline = false
        prefs.edit()
            .remove(KEY_CURRENT_USER)
            .apply()
        Log.d(TAG, "已退出登录，本地用户信息已清除")
    }

    // ---------- 查询 ----------

    /** 是否已登录：SharedPreferences 中存在保存的用户信息即视为已登录（无需联网验证） */
    fun isLoggedIn(): Boolean = prefs.contains(KEY_CURRENT_USER)

    /** 当前登录用户，未登录返回 null */
    fun getCurrentUser(): User? = loadCurrentUser()

    /** 当前登录用户的 JWT token（统一由 TokenManager 管理），未登录返回 null */
    fun getToken(): String? = TokenManager.getToken()

    /** 更新用户信息（昵称/头像等），同步内存库与当前登录缓存 */
    fun updateUser(user: User) {
        val index = users.indexOfFirst { it.userId == user.userId }
        if (index >= 0) {
            users[index] = user
            saveUsers()
        }
        saveCurrentUser(user)
    }

    // ---------- 数据公开开关同步 ----------

    /**
     * 把当前账号的「数据公开」开关同步到服务器（PUT /api/users/settings）。
     * - 在线：调用接口，成功清除待同步标志；失败标记待同步，下次网络恢复补推；
     * - 离线：仅标记待同步（本地已生效），由 SyncManager 在网络恢复后自动补推。
     * @return 是否已成功同步到服务器
     */
    suspend fun syncDataPublicSetting(): Boolean {
        val user = getCurrentUser() ?: return false
        if (!NetworkMonitor.isConnected()) {
            Log.d(TAG, "无网络，开关本地已生效，标记待同步: ${user.username}")
            DataPublicPrefs.setPendingSync(appContext, user.userId, true)
            return false
        }
        val isPublic = DataPublicPrefs.isPublic(appContext, user.userId)
        val result = ApiClient.safeApiCall {
            ApiClient.service.updateUserSettings(UserSettingsRequest(if (isPublic) 1 else 0))
        }
        return result.fold(
            onSuccess = { resp ->
                val ok = resp.code == 0
                DataPublicPrefs.setPendingSync(appContext, user.userId, !ok)
                if (ok) {
                    Log.d(TAG, "数据公开开关已同步: is_data_public=${if (isPublic) 1 else 0}")
                } else {
                    Log.w(TAG, "同步开关被服务器拒绝: ${resp.msg}")
                }
                ok
            },
            onFailure = { e ->
                Log.w(TAG, "同步开关失败(${e.message})，标记待同步")
                DataPublicPrefs.setPendingSync(appContext, user.userId, true)
                false
            }
        )
    }

    // ---------- 本地模拟（离线降级） ----------

    private fun localLogin(username: String, password: String): Result<User> {
        val user = users.firstOrNull { it.username == username }
            ?: return Result.failure(UserException("用户不存在，请先注册"))
        if (user.password != password) {
            return Result.failure(UserException("密码错误，请重试"))
        }
        saveCurrentUser(user)
        // 离线降级登录：本机没有该账号的有效 JWT token。
        // 必须清掉可能残留的旧账号 token —— 否则 sync 会带旧 token 请求，
        // 服务器按旧 token 解析 user_id，把本次录入的数据串写到别的账号名下；
        // 无 token 时 sync 被 401 拒绝、数据保留本地，联网重新在线登录后即可补传。
        TokenManager.clearToken()
        lastLoginOffline = true
        return Result.success(user)
    }

    private fun localRegister(
        username: String,
        password: String,
        nickname: String?
    ): Result<User> {
        if (username.isEmpty()) return Result.failure(UserException("用户名不能为空"))
        if (password.isEmpty()) return Result.failure(UserException("密码不能为空"))
        if (users.any { it.username == username }) {
            return Result.failure(UserException("用户名已存在，请直接登录"))
        }
        val user = User(username = username, password = password, nickname = nickname ?: username)
        users.add(user)
        saveUsers()
        saveCurrentUser(user)
        // 离线注册的账号在服务器上不存在，无 token；清掉残留旧 token 防串号
        TokenManager.clearToken()
        lastLoginOffline = true
        return Result.success(user)
    }

    // ---------- 持久化 ----------

    private fun rememberLocalUser(user: User, password: String) {
        // 服务器用户同步进本地库，保证离线时也能用该账号登录
        val exists = users.any { it.username == user.username }
        val localUser = user.copy(password = password)
        if (exists) {
            users.replaceAll { if (it.username == localUser.username) localUser else it }
        } else {
            users.add(localUser)
        }
        saveUsers()
    }

    private fun loadCurrentUser(): User? {
        val json = prefs.getString(KEY_CURRENT_USER, null) ?: return null
        return runCatching { parseUser(JSONObject(json)) }.getOrNull()
    }

    private fun saveCurrentUser(user: User) {
        prefs.edit().putString(KEY_CURRENT_USER, toJson(user).toString()).apply()
    }

    private fun loadUsers(): MutableList<User> {
        val json = prefs.getString(KEY_USERS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { parseUser(array.getJSONObject(it)) }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun saveUsers() {
        val array = JSONArray()
        users.forEach { array.put(toJson(it)) }
        prefs.edit().putString(KEY_USERS, array.toString()).apply()
    }

    private fun toJson(user: User): JSONObject = JSONObject().apply {
        put("userId", user.userId)
        put("username", user.username)
        put("password", user.password)
        put("nickname", user.nickname ?: JSONObject.NULL)
        put("avatar", user.avatar ?: JSONObject.NULL)
        put("createdAt", user.createdAt)
    }

    private fun parseUser(json: JSONObject): User = User(
        userId = json.optString("userId"),
        username = json.optString("username"),
        password = json.optString("password"),
        nickname = if (json.isNull("nickname")) null else json.optString("nickname"),
        avatar = if (json.isNull("avatar")) null else json.optString("avatar"),
        createdAt = json.optLong("createdAt", System.currentTimeMillis())
    )

    /** 服务器用户 → 本地 User（password 置空，凭 token 访问） */
    private fun UserOut.toLocalUser(): User = User(
        userId = id.toString(),
        username = username,
        password = "",
        nickname = nickname ?: username,
        createdAt = parseServerTime(createdAt)
    )

    /** 解析服务器 ISO 时间（"2026-08-20T09:00:00"），失败返回当前时间 */
    private fun parseServerTime(text: String?): Long {
        if (text.isNullOrBlank()) return System.currentTimeMillis()
        return runCatching {
            LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(System.currentTimeMillis())
    }

    /** 从统一返回体 {code, data, msg} 提取 msg */
    private fun extractMsg(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return runCatching { JSONObject(body).optString("msg").ifBlank { null } }.getOrNull()
    }

    companion object {
        private const val TAG = "UserRepository"
        private const val PREFS = "user_prefs"
        private const val KEY_CURRENT_USER = "current_user"
        private const val KEY_USERS = "users"
    }
}
