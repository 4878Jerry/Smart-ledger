package com.ousuan.smartbutler.data.repository

import android.content.Context
import android.util.Log
import com.ousuan.smartbutler.data.AppDatabase
import com.ousuan.smartbutler.data.CachedPost
import com.ousuan.smartbutler.data.CachedPostDao
import com.ousuan.smartbutler.data.PendingPost
import com.ousuan.smartbutler.data.PendingPostDao
import com.ousuan.smartbutler.data.model.CommentOut
import com.ousuan.smartbutler.data.model.CommentRequest
import com.ousuan.smartbutler.data.model.CommunityComment
import com.ousuan.smartbutler.data.model.CommunityPost
import com.ousuan.smartbutler.data.model.PostRequest
import com.ousuan.smartbutler.data.model.PostUpdateRequest
import com.ousuan.smartbutler.data.model.PublicStats
import com.ousuan.smartbutler.data.model.PublicStatsResponse
import com.ousuan.smartbutler.data.network.ApiClient
import com.ousuan.smartbutler.data.network.NetworkChecker
import com.ousuan.smartbutler.data.network.TokenManager
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * 社区仓库（单例，在线/离线双模式）：
 * - **在线**：浏览帖子从服务器 /api/stats/public 拉取，成功后写入 cached_posts 表缓存；
 * - **离线**：浏览帖子从 cached_posts 表读取（不弹错误、不空白），缓存为空时降级预置模拟帖；
 * - **发布**：在线直接 POST /api/posts 并更新缓存；离线存入 pending_posts 表待网络恢复后补发；
 * - **点赞/评论**：在线实时执行并同步更新缓存表；离线不执行，返回「网络不可用，请稍后重试」；
 * - 本地 [posts] 同时保存预置模拟帖与本地发布的 local 帖（SharedPreferences 持久化），
 *   登录后 [mergeServerPosts] 从服务器拉回「我的帖子」合并（重装 App 后可恢复）；
 * - 服务器为权威数据源，cached_posts / posts 均为离线兜底缓存。
 */
object CommunityRepository {

    private const val TAG = "CommunityRepository"
    private const val PREFS_NAME = "community_prefs"
    private const val KEY_MY_POSTS = "my_posts"

    private val posts: MutableList<CommunityPost> = mutableListOf()

    private var appContext: Context? = null

    private lateinit var cachedPostDao: CachedPostDao

    private lateinit var pendingPostDao: PendingPostDao

    /** 最近一次加载是否处于离线模式（UI 提示条显示用） */
    @Volatile
    var isOfflineMode: Boolean = false
        private set

    /** 系统网络已恢复时调用：清除离线模式标记，下次加载会走在线路径 */
    fun clearOfflineMode() {
        if (isOfflineMode) isOfflineMode = false
    }

    /**
     * 切换账号成功后调用：清空旧账号的社区数据。
     * - 内存 [posts] 仅保留预置模拟帖（sample- 前缀），清掉本地发布的 local- 帖与服务器合并帖；
     * - 清空 SharedPreferences 持久化的「我的帖子」（旧账号的本地发布记录）；
     * - 清空 cached_posts 缓存表与 pending_posts 待发布表（旧账号数据）。
     * 下次 [getAllPosts] 会重新从服务器拉取新账号的公开数据，本地未同步帖不再残留。
     */
    suspend fun resetForAccountSwitch() {
        posts.removeAll { !it.postId.startsWith("sample-") }
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit()?.remove(KEY_MY_POSTS)?.apply()
        runCatching { cachedPostDao.clear() }
        runCatching { pendingPostDao.clear() }
        isOfflineMode = false
        Log.d(TAG, "账号切换：已清空社区帖子（内存/持久化/缓存表/待发布表）")
    }

    /**
     * 当前用户当月是否已发布过帖子（发布按钮文案动态切换用）。
     * 先查内存 [posts]，再查 cached_posts 缓存表（覆盖只拉过一次公开列表、尚未进「我的帖子」的场景）。
     */
    suspend fun hasPublishedPostForMonth(month: String, username: String?): Boolean {
        if (username.isNullOrBlank()) return false
        if (posts.any { it.username == username && it.month == month }) return true
        return runCatching {
            cachedPostDao.getByUsernameAndMonth(username, month) != null
        }.getOrDefault(false)
    }

    /** 当前登录用户名提供者（评论/发布时使用），由 Application 注入 */
    private var usernameProvider: () -> String? = { null }

    /** 由 Application 在启动时注入 Context 与当前用户提供者 */
    fun init(context: Context, usernameProvider: () -> String?) {
        this.appContext = context.applicationContext
        this.usernameProvider = usernameProvider
        val db = AppDatabase.get(context)
        cachedPostDao = db.cachedPostDao()
        pendingPostDao = db.pendingPostDao()
        loadPersistedPosts()
    }

    init {
        posts.addAll(buildSamplePosts())
    }

    /**
     * 全部帖子（在线/离线双模式）：
     * - 在线：拉取服务器公开数据 → 写入 cached_posts 表 → 返回（本地 local- 帖补充在前）；
     * - 离线：从 cached_posts 表读缓存；缓存为空时降级预置模拟帖（不弹错误、不空白）。
     */
    suspend fun getAllPosts(): List<CommunityPost> {
        val online = NetworkChecker.checkServerAvailable(timeoutMs = 2000)
        if (online) {
            val result = ApiClient.safeApiCall { ApiClient.service.getPublicStats() }
            result.onSuccess { resp ->
                if (resp.code == 0 && resp.data != null) {
                    val serverPosts = resp.data.map { it.toCommunityPost() }
                    if (serverPosts.isNotEmpty()) {
                        // 服务器有数据：写入缓存表（REPLACE 按 postId 去重），返回时本地发布帖补充在前
                        cachedPostDao.insertAll(serverPosts.map { it.toCachedPost() })
                        val localPosts = posts.filter { it.postId.startsWith("local-") }
                        isOfflineMode = false
                        Log.d(TAG, "服务器帖子 ${serverPosts.size} 条 + 本地发布 ${localPosts.size} 条")
                        Log.d("Offline", "网络状态: true，从服务器加载数据（${serverPosts.size} 条）")
                        return (localPosts + serverPosts).sortedByDescending { it.timestamp }
                    }
                    // 服务器返回空数组：不清空缓存，走下方缓存/本地降级
                    Log.w(TAG, "服务器帖子列表为空，保留本地缓存")
                }
            }
        }
        // 离线 / 服务器失败：读缓存表；缓存为空时用预置模拟帖兜底
        isOfflineMode = true
        val cached = runCatching { cachedPostDao.getAll().map { it.toCommunityPost() } }
            .getOrDefault(emptyList())
        val localPosts = posts.filter { it.postId.startsWith("local-") }
        val list = if (cached.isNotEmpty()) {
            (localPosts + cached).sortedByDescending { it.timestamp }
        } else {
            posts.sortedByDescending { it.timestamp }
        }
        val source = if (cached.isNotEmpty()) "缓存表" else "本地模拟"
        Log.d("Offline", "网络状态: false，从${source}加载数据（${list.size} 条）")
        return list
    }

    /**
     * 合并「当前用户」服务器帖子到本地列表（登录后由 SyncManager 调用）。
     * 按 postId 去重（本地已存在的同 ID 帖子被替换，保留本地未同步发布的 local- 帖），
     * 合并结果持久化到本地，供离线降级时可见。
     * @return 新增条数
     */
    suspend fun mergeServerPosts(list: List<PublicStatsResponse>): Int {
        val serverPosts = list.map { it.toCommunityPost() }
        val serverIds = serverPosts.map { it.postId }.toSet()
        // 删除本地同 ID 的旧版本（local- 本地未同步帖除外）
        posts.removeAll { it.postId in serverIds && !it.postId.startsWith("local-") }
        var added = 0
        for (sp in serverPosts) {
            if (posts.none { it.postId == sp.postId }) {
                posts.add(sp)
                added++
            }
        }
        posts.sortByDescending { it.timestamp }
        savePersistedPosts()
        // 同步写入缓存表（离线时也能看到我的帖子）
        runCatching { cachedPostDao.insertAll(serverPosts.map { it.toCachedPost() }) }
        Log.d(TAG, "合并服务器帖子 ${serverPosts.size} 条，新增 $added 条，当前共 ${posts.size} 条")
        return added
    }

    /**
     * 当前用户发布的全部帖子（含仅自己可见的私有帖）。
     * - 在线：从 /api/posts/mine 拉取并合并到本地（服务器返回含 visibility），返回自己的帖子；
     * - 离线：返回本地 [posts] 中属于自己的帖子（含 local- 未同步帖），不弹错误。
     */
    suspend fun fetchMyPosts(): List<CommunityPost> {
        val username = usernameProvider() ?: return emptyList()
        val online = NetworkChecker.checkServerAvailable(timeoutMs = 2000)
        if (online) {
            val result = ApiClient.safeApiCall { ApiClient.service.getMyPosts() }
            result.onSuccess { resp ->
                if (resp.code == 0 && resp.data != null) {
                    mergeServerPosts(resp.data)
                }
            }
        }
        return posts.filter { it.username == username }
            .sortedByDescending { it.timestamp }
    }

    /**
     * 修改帖子可见度（public 公开 / private 仅自己可见）：帖子级 visibility 与
     * 模块级 dataVisibility / budgetVisibility 均可设置（默认跟随帖子级）。
     * - 服务器帖子：在线调用 PUT /api/posts/{id} 更新，成功后同步本地 [posts] 与 cached_posts 缓存；
     * - local- 本地未同步帖：直接本地修改（补发时由 postToJson 带上可见度）；
     * - 离线：返回「网络不可用」。
     */
    suspend fun updatePostVisibility(
        post: CommunityPost,
        visibility: String,
        dataVisibility: String = visibility,
        budgetVisibility: String = visibility
    ): Result<CommunityPost> {
        val v = if (visibility == "private") "private" else "public"
        val dv = if (dataVisibility == "private") "private" else "public"
        val bv = if (budgetVisibility == "private") "private" else "public"
        // local- 本地未同步帖：直接本地修改
        if (post.postId.startsWith("local-")) {
            val idx = posts.indexOfFirst { it.postId == post.postId }
            if (idx < 0) return Result.failure(UserException("帖子不存在"))
            posts[idx] = posts[idx].copy(visibility = v, dataVisibility = dv, budgetVisibility = bv)
            savePersistedPosts()
            return Result.success(posts[idx])
        }
        // 服务器帖子：离线拦截，不发起请求
        if (!NetworkChecker.checkServerAvailable()) {
            Log.d("Offline", "网络状态: false，可见度修改已拦截（帖子 ${post.postId}）")
            return Result.failure(UserException("网络不可用，请稍后重试"))
        }
        val serverId = post.postId.toLongOrNull()
        if (serverId == null) return Result.failure(UserException("帖子 ID 无效"))
        val result = ApiClient.safeApiCall {
            ApiClient.service.updatePost(
                serverId,
                PostUpdateRequest(visibility = v, dataVisibility = dv, budgetVisibility = bv)
            )
        }
        result.onSuccess { resp ->
            if (resp.code == 0) {
                val updated = resp.data?.toCommunityPost()
                    ?: post.copy(visibility = v, dataVisibility = dv, budgetVisibility = bv)
                val idx = posts.indexOfFirst { it.postId == post.postId }
                if (idx >= 0) {
                    posts[idx] = updated
                    savePersistedPosts()
                    runCatching { cachedPostDao.insert(updated.toCachedPost()) }
                }
                return Result.success(updated)
            }
            return Result.failure(UserException(resp.msg ?: "更新可见度失败"))
        }
        return Result.failure(UserException("网络不可用，更新可见度失败"))
    }

    /** 点赞 / 取消点赞：在线实时执行并同步缓存；离线不执行，返回「网络不可用」提示 */
    suspend fun likePost(postId: String): Result<Int> {
        val serverId = postId.toLongOrNull()
        if (serverId == null) {
            // 本地模拟帖子（local- 前缀，未同步到服务器）
            return likeLocal(postId)
        }
        // 服务器帖子：离线拦截，不发起请求
        if (!NetworkChecker.checkServerAvailable()) {
            Log.d("Offline", "网络状态: false，点赞操作已拦截（帖子 $postId）")
            return Result.failure(UserException("网络不可用，请稍后重试"))
        }
        return ApiClient.safeApiCall { ApiClient.service.likePost(serverId) }
            .map { it.data?.likes ?: 0 }
            .onSuccess { likes ->
                syncLocalLike(postId, likes)
                // 同步更新缓存表
                runCatching { cachedPostDao.updateLikes(postId, likes) }
            }
    }

    /** 发表评论：在线实时执行并同步缓存；离线不执行，返回「网络不可用」提示 */
    suspend fun addComment(postId: String, content: String): Result<CommunityComment> {
        val text = content.trim()
        if (text.isEmpty()) return Result.failure(IllegalArgumentException("评论内容不能为空"))
        val username = usernameProvider() ?: return Result.failure(IllegalStateException("请先登录"))
        val serverId = postId.toLongOrNull()
        if (serverId == null) {
            // 本地模拟帖子（local- 前缀）
            return addCommentLocal(postId, text, username)
        }
        // 服务器帖子：离线拦截，不发起请求
        if (!NetworkChecker.checkServerAvailable()) {
            Log.d("Offline", "网络状态: false，评论操作已拦截（帖子 $postId）")
            return Result.failure(UserException("网络不可用，请稍后重试"))
        }
        return ApiClient.safeApiCall { ApiClient.service.addComment(CommentRequest(serverId, text)) }
            .map { it.data?.toLocalComment() ?: CommunityComment(
                commentId = UUID.randomUUID().toString(),
                username = username,
                content = text,
                timestamp = System.currentTimeMillis()
            ) }
            .onSuccess { comment ->
                syncLocalComment(postId, comment)
                // 同步更新缓存表：读取当前帖子，追加评论后写回
                runCatching {
                    val cur = cachedPostDao.getByPostId(postId) ?: return@onSuccess
                    val json = JSONArray().apply {
                        runCatching {
                            JSONArray(cur.comments).let { arr ->
                                (0 until arr.length()).forEach { i -> put(arr.getJSONObject(i)) }
                            }
                        }
                        put(JSONObject().apply {
                            put("commentId", comment.commentId)
                            put("username", comment.username)
                            put("content", comment.content)
                            put("timestamp", comment.timestamp)
                        })
                    }.toString()
                    cachedPostDao.insert(cur.copy(comments = json))
                }
            }
    }

    /**
     * 将当前用户的双模块数据发布为帖子（在线/离线双模式）：
     * - 消费数据模块：stats 非空时发送 total_expense / category_breakdown / top_category / saving_tip；
     * - 预算方案模块：budgetBreakdown 非空时发送 budget_breakdown；
     * - 两个模块都为空时返回「至少选择一个模块」错误；
     * - 在线：POST /api/posts 发布到服务器，成功后写入 cached_posts 缓存；
     * - 离线：存入 pending_posts 表，返回「网络不可用，已保存待发布」（网络恢复后自动补发）。
     */
    suspend fun publishMyStats(
        month: String,
        stats: PublicStats?,
        budgetBreakdown: Map<String, Double> = emptyMap(),
        visibility: String = "public",
        dataVisibility: String = "public",
        budgetVisibility: String = "public"
    ): Result<CommunityPost> {
        // 发布前检查：已登录且 token 存在（服务器发布接口需要认证）
        val username = usernameProvider() ?: return Result.failure(UserException("请先登录"))
        val token = TokenManager.getToken()
        Log.d(TAG, "发布前检查: 用户=$username, token存在=${!token.isNullOrEmpty()}")
        if (token.isNullOrEmpty()) {
            return Result.failure(UserException("登录已过期，请重新登录"))
        }
        val categoryBreakdown = stats?.categoryBreakdown ?: emptyMap()
        val totalExpense = stats?.totalExpense ?: 0.0
        val topCategory = stats?.topCategory ?: ""
        val savingTip = stats?.savingTip ?: ""
        if (categoryBreakdown.isEmpty() && budgetBreakdown.isEmpty()) {
            return Result.failure(UserException("至少选择一个模块"))
        }
        val request = PostRequest(
            month = month,
            totalExpense = totalExpense,
            categoryBreakdown = categoryBreakdown,
            budgetBreakdown = budgetBreakdown.ifEmpty { null },
            topCategory = topCategory,
            savingTip = savingTip,
            visibility = visibility,
            dataVisibility = dataVisibility,
            budgetVisibility = budgetVisibility
        )
        // 离线：暂存待发布
        if (!NetworkChecker.checkServerAvailable()) {
            savePendingPost(month, stats, budgetBreakdown, visibility, dataVisibility, budgetVisibility)
            Log.d("Offline", "网络状态: false，帖子已保存待发布")
            return Result.failure(UserException("网络不可用，已保存待发布"))
        }
        val result = ApiClient.safeApiCall { ApiClient.service.createPost(request) }
        result.onSuccess { resp ->
            if (resp.code == 0 && resp.data != null) {
                val serverPostId = resp.data.postId
                Log.d(TAG, "发布成功，服务器帖子 ID: $serverPostId")
                val post = CommunityPost(
                    postId = serverPostId.toString(),
                    username = username,
                    month = month,
                    totalExpense = totalExpense,
                    categoryBreakdown = categoryBreakdown,
                    budgetBreakdown = budgetBreakdown,
                    topCategory = topCategory,
                    savingTip = savingTip,
                    likes = 0,
                    comments = emptyList(),
                    timestamp = System.currentTimeMillis(),
                    visibility = visibility,
                    dataVisibility = dataVisibility,
                    budgetVisibility = budgetVisibility
                )
                // 本地也存一份（离线降级时可见）并持久化（记录服务器帖子 ID，防丢失）
                posts.add(0, post)
                savePersistedPosts()
                // 写入缓存表（重装后离线也能看到）
                runCatching { cachedPostDao.insert(post.toCachedPost()) }
                return Result.success(post)
            }
            return Result.failure(UserException(resp.msg ?: "发布失败"))
        }
        // 网络请求失败（检测通过但实际失败）：也暂存待发布，网络恢复后自动补发
        savePendingPost(month, stats, budgetBreakdown, visibility, dataVisibility, budgetVisibility)
        Log.d("Offline", "发布请求失败，帖子已保存待发布")
        return Result.failure(UserException("网络不可用，已保存待发布"))
    }

    /** 离线发布：将双模块帖子数据序列化为 JSON 存入 pending_posts 表 */
    private suspend fun savePendingPost(
        month: String,
        stats: PublicStats?,
        budgetBreakdown: Map<String, Double>,
        visibility: String = "public",
        dataVisibility: String = "public",
        budgetVisibility: String = "public"
    ) {
        val data = JSONObject().apply {
            put("month", month)
            put("totalExpense", stats?.totalExpense ?: 0.0)
            val bd = JSONObject()
            stats?.categoryBreakdown?.forEach { (k, v) -> bd.put(k, v) }
            put("categoryBreakdown", bd)
            val bb = JSONObject()
            budgetBreakdown.forEach { (k, v) -> bb.put(k, v) }
            put("budgetBreakdown", bb)
            put("topCategory", stats?.topCategory ?: "")
            put("savingTip", stats?.savingTip ?: "")
            put("visibility", visibility)
            put("dataVisibility", dataVisibility)
            put("budgetVisibility", budgetVisibility)
        }.toString()
        pendingPostDao.insert(PendingPost(postData = data, createdAt = System.currentTimeMillis()))
        val count = runCatching { pendingPostDao.count() }.getOrDefault(1)
        Log.d("Offline", "待发布帖子数量: $count")
    }

    /** 补发待发布帖子（网络恢复后由 SyncManager 调用），返回成功发布数 */
    suspend fun publishPendingPosts(): Int {
        val pending = runCatching { pendingPostDao.getAll() }.getOrDefault(emptyList())
        if (pending.isEmpty()) return 0
        Log.d("Offline", "待发布帖子数量: ${pending.size}")
        var published = 0
        for (p in pending) {
            val body = runCatching { JSONObject(p.postData) }.getOrNull() ?: continue
            val request = PostRequest(
                month = body.optString("month"),
                totalExpense = body.optDouble("totalExpense"),
                categoryBreakdown = runCatching {
                    val bd = linkedMapOf<String, Double>()
                    body.optJSONObject("categoryBreakdown")?.keys()?.forEach { k ->
                        bd[k] = body.optJSONObject("categoryBreakdown")!!.optDouble(k)
                    }
                    bd
                }.getOrDefault(emptyMap()),
                budgetBreakdown = runCatching {
                    val bb = linkedMapOf<String, Double>()
                    body.optJSONObject("budgetBreakdown")?.keys()?.forEach { k ->
                        bb[k] = body.optJSONObject("budgetBreakdown")!!.optDouble(k)
                    }
                    bb.ifEmpty { null }
                }.getOrDefault(null),
                topCategory = body.optString("topCategory"),
                savingTip = body.optString("savingTip"),
                visibility = body.optString("visibility", "public"),
                dataVisibility = body.optString("dataVisibility", "public"),
                budgetVisibility = body.optString("budgetVisibility", "public")
            )
            val result = ApiClient.safeApiCall { ApiClient.service.createPost(request) }
            result.onSuccess { resp ->
                if (resp.code == 0 && resp.data != null) {
                    pendingPostDao.deleteById(p.id)
                    published++
                    Log.d(TAG, "待发布帖子补发成功: post_id=${resp.data.postId}")
                }
            }
        }
        Log.d("Offline", "待发布帖子补发完成: $published/${pending.size}")
        return published
    }

    /** 刷新社区缓存（网络恢复后由 SyncManager 调用）：拉取最新公开帖子写入 cached_posts */
    suspend fun refreshCacheFromServer(): Boolean {
        if (!NetworkChecker.checkServerAvailable()) return false
        val result = ApiClient.safeApiCall { ApiClient.service.getPublicStats() }
        result.onSuccess { resp ->
            if (resp.code == 0 && resp.data != null && resp.data.isNotEmpty()) {
                cachedPostDao.insertAll(resp.data.map { it.toCommunityPost().toCachedPost() })
                Log.d("Sync", "社区缓存已刷新: ${resp.data.size} 条")
                return true
            }
        }
        return false
    }

    /** 待发布帖子数量（日志/同步用） */
    suspend fun pendingPostCount(): Int = runCatching { pendingPostDao.count() }.getOrDefault(0)

    // ---------- 本地模拟操作（离线降级） ----------

    private fun likeLocal(postId: String): Result<Int> {
        val index = posts.indexOfFirst { it.postId == postId }
        if (index < 0) return Result.failure(IllegalArgumentException("帖子不存在"))
        val post = posts[index]
        posts[index] = post.copy(likes = post.likes + 1)
        savePersistedPosts()
        return Result.success(posts[index].likes)
    }

    private fun addCommentLocal(postId: String, text: String, username: String): Result<CommunityComment> {
        val index = posts.indexOfFirst { it.postId == postId }
        if (index < 0) return Result.failure(IllegalArgumentException("帖子不存在"))
        val comment = CommunityComment(
            commentId = UUID.randomUUID().toString(),
            username = username,
            content = text,
            timestamp = System.currentTimeMillis()
        )
        val post = posts[index]
        posts[index] = post.copy(comments = post.comments + comment)
        savePersistedPosts()
        return Result.success(comment)
    }

    /** 服务器点赞成功后，同步更新本地缓存中的同帖点赞数（若该帖在本地缓存中） */
    private fun syncLocalLike(postId: String, likes: Int) {
        val index = posts.indexOfFirst { it.postId == postId }
        if (index < 0) return
        posts[index] = posts[index].copy(likes = likes)
        savePersistedPosts()
    }

    /** 服务器评论成功后，同步更新本地缓存中的同帖评论列表（若该帖在本地缓存中） */
    private fun syncLocalComment(postId: String, comment: CommunityComment) {
        val index = posts.indexOfFirst { it.postId == postId }
        if (index < 0) return
        val post = posts[index]
        posts[index] = post.copy(comments = post.comments + comment)
        savePersistedPosts()
    }

    // ---------- 本地帖子持久化（用户发布的帖子，sample 预置帖不持久化） ----------

    private fun savePersistedPosts() {
        val ctx = appContext ?: return
        val myPosts = posts.filter { !it.postId.startsWith("sample-") }
        runCatching {
            val array = JSONArray()
            myPosts.forEach { array.put(postToJson(it)) }
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MY_POSTS, array.toString())
                .apply()
        }.onFailure { Log.w(TAG, "持久化帖子失败: ${it.message}") }
    }

    private fun loadPersistedPosts() {
        val ctx = appContext ?: return
        val json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_MY_POSTS, null)
        if (json.isNullOrBlank()) return
        runCatching {
            val array = JSONArray(json)
            val saved = (0 until array.length())
                .map { postFromJson(array.getJSONObject(it)) }
                .filterNotNull()
                .filter { !it.postId.startsWith("sample-") }
            if (saved.isNotEmpty()) {
                posts.addAll(saved)
                posts.sortByDescending { it.timestamp }
                Log.d(TAG, "从本地恢复用户帖子 ${saved.size} 条")
            }
        }.onFailure { Log.w(TAG, "解析本地帖子失败: ${it.message}") }
    }

    private fun postToJson(p: CommunityPost): JSONObject = JSONObject().apply {
        put("postId", p.postId)
        put("username", p.username)
        put("month", p.month)
        put("totalExpense", p.totalExpense)
        put("topCategory", p.topCategory)
        put("savingTip", p.savingTip)
        put("likes", p.likes)
        put("timestamp", p.timestamp)
        put("updatedAt", p.updatedAt)
        put("visibility", p.visibility)
        put("dataVisibility", p.dataVisibility)
        put("budgetVisibility", p.budgetVisibility)
        val breakdown = JSONObject()
        p.categoryBreakdown.forEach { (k, v) -> breakdown.put(k, v) }
        put("categoryBreakdown", breakdown)
        val budgetBreakdown = JSONObject()
        p.budgetBreakdown.forEach { (k, v) -> budgetBreakdown.put(k, v) }
        put("budgetBreakdown", budgetBreakdown)
        val comments = JSONArray()
        p.comments.forEach { c ->
            comments.put(JSONObject().apply {
                put("commentId", c.commentId)
                put("username", c.username)
                put("content", c.content)
                put("timestamp", c.timestamp)
            })
        }
        put("comments", comments)
    }

    private fun postFromJson(json: JSONObject): CommunityPost? = runCatching {
        val breakdown = linkedMapOf<String, Double>()
        json.optJSONObject("categoryBreakdown")?.keys()?.forEach { k ->
            breakdown[k] = json.optJSONObject("categoryBreakdown")!!.optDouble(k)
        }
        val budget = linkedMapOf<String, Double>()
        json.optJSONObject("budgetBreakdown")?.keys()?.forEach { k ->
            budget[k] = json.optJSONObject("budgetBreakdown")!!.optDouble(k)
        }
        val comments = mutableListOf<CommunityComment>()
        json.optJSONArray("comments")?.let { arr ->
            (0 until arr.length()).forEach { i ->
                val c = arr.getJSONObject(i)
                comments.add(CommunityComment(
                    commentId = c.optString("commentId"),
                    username = c.optString("username"),
                    content = c.optString("content"),
                    timestamp = c.optLong("timestamp")
                ))
            }
        }
        CommunityPost(
            postId = json.optString("postId"),
            username = json.optString("username"),
            month = json.optString("month"),
            totalExpense = json.optDouble("totalExpense"),
            categoryBreakdown = breakdown,
            budgetBreakdown = budget,
            topCategory = json.optString("topCategory"),
            savingTip = json.optString("savingTip"),
            likes = json.optInt("likes"),
            comments = comments,
            timestamp = json.optLong("timestamp"),
            updatedAt = json.optLong("updatedAt", json.optLong("timestamp")),
            visibility = json.optString("visibility", "public"),
            dataVisibility = json.optString("dataVisibility", "public"),
            budgetVisibility = json.optString("budgetVisibility", "public")
        )
    }.getOrNull()

    // ---------- CommunityPost ↔ CachedPost 缓存转换 ----------

    /** CommunityPost → CachedPost（categoryBreakdown / comments 序列化为 JSON 字符串） */
    private fun CommunityPost.toCachedPost(): CachedPost = CachedPost(
        postId = postId,
        username = username,
        month = month,
        totalExpense = totalExpense,
        categoryBreakdown = JSONObject().apply {
            categoryBreakdown.forEach { (k, v) -> put(k, v) }
        }.toString(),
        budgetBreakdown = JSONObject().apply {
            budgetBreakdown.forEach { (k, v) -> put(k, v) }
        }.toString(),
        topCategory = topCategory,
        savingTip = savingTip ?: "",
        likes = likes,
        comments = JSONArray().apply {
            comments.forEach { c ->
                put(JSONObject().apply {
                    put("commentId", c.commentId)
                    put("username", c.username)
                    put("content", c.content)
                    put("timestamp", c.timestamp)
                })
            }
        }.toString(),
        timestamp = timestamp,
        updatedAt = updatedAt,
        visibility = visibility,
        dataVisibility = dataVisibility,
        budgetVisibility = budgetVisibility
    )

    /** CachedPost → CommunityPost（解析 JSON 字符串） */
    private fun CachedPost.toCommunityPost(): CommunityPost {
        val breakdown = linkedMapOf<String, Double>()
        runCatching {
            val obj = JSONObject(categoryBreakdown)
            obj.keys().forEach { k -> breakdown[k] = obj.optDouble(k) }
        }
        val budget = linkedMapOf<String, Double>()
        runCatching {
            val obj = JSONObject(budgetBreakdown.ifEmpty { "{}" })
            obj.keys().forEach { k -> budget[k] = obj.optDouble(k) }
        }
        val commentList = mutableListOf<CommunityComment>()
        runCatching {
            val arr = JSONArray(comments)
            (0 until arr.length()).forEach { i ->
                val c = arr.getJSONObject(i)
                commentList.add(CommunityComment(
                    commentId = c.optString("commentId"),
                    username = c.optString("username"),
                    content = c.optString("content"),
                    timestamp = c.optLong("timestamp")
                ))
            }
        }
        return CommunityPost(
            postId = postId,
            username = username,
            month = month,
            totalExpense = totalExpense,
            categoryBreakdown = breakdown,
            budgetBreakdown = budget,
            topCategory = topCategory,
            savingTip = savingTip,
            likes = likes,
            comments = commentList,
            timestamp = timestamp,
            updatedAt = updatedAt,
            visibility = visibility,
            dataVisibility = dataVisibility,
            budgetVisibility = budgetVisibility
        )
    }

    // ---------- 服务器模型 → 本地模型 ----------

    private fun PublicStatsResponse.toCommunityPost(): CommunityPost = CommunityPost(
        postId = postId.toString(),
        username = nickname ?: username,
        month = month,
        totalExpense = totalExpense,
        categoryBreakdown = categoryBreakdown,
        budgetBreakdown = budgetBreakdown,
        topCategory = topCategory,
        savingTip = savingTip,
        likes = likes,
        comments = comments.map { it.toLocalComment() },
        timestamp = parseServerTime(createdAt),
        updatedAt = parseServerTime(updatedAt).takeIf { it > 0 } ?: parseServerTime(createdAt),
        visibility = visibility,
        dataVisibility = dataVisibility,
        budgetVisibility = budgetVisibility
    )

    private fun CommentOut.toLocalComment(): CommunityComment = CommunityComment(
        commentId = id.toString(),
        username = username,
        content = content,
        timestamp = parseServerTime(createdAt)
    )

    /**
     * 解析服务器 ISO 时间（"2026-08-20T09:00:00"）。
     * 解析失败/缺失时回退 0（排到列表末尾），绝不回退到当前时间——
     * 否则每次进入社区所有帖子时间都会变成当前时间，导致排序随机漂移。
     */
    private fun parseServerTime(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching {
            LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrDefault(0L)
    }

    // ---------- 预置模拟帖子 ----------

    private fun buildSamplePosts(): List<CommunityPost> = listOf(
        CommunityPost(
            postId = "sample-01",
            username = "小星星",
            month = "2026-08",
            totalExpense = 4280.50,
            categoryBreakdown = linkedMapOf(
                "餐饮" to 1650.00, "交通" to 620.50, "购物" to 980.00,
                "居住" to 850.00, "娱乐" to 180.00
            ),
            budgetBreakdown = linkedMapOf(
                "餐饮" to 1800.00, "交通" to 600.00, "购物" to 1200.00,
                "居住" to 1000.00, "娱乐" to 300.00
            ),
            topCategory = "餐饮",
            savingTip = "本月「餐饮」支出最高（¥1650.00，占比 39%），可适当控制外出就餐开销。",
            likes = 42,
            comments = listOf(
                CommunityComment("c-1", "Luna", "餐饮占比好高，我也是哈哈", ts(2026, 8, 20, 10, 30)),
                CommunityComment("c-2", "阿杰", "求推荐省钱的吃饭攻略！", ts(2026, 8, 21, 18, 5))
            ),
            timestamp = ts(2026, 8, 20, 9, 0)
        ),
        CommunityPost(
            postId = "sample-02",
            username = "阿杰",
            month = "2026-07",
            totalExpense = 3120.00,
            categoryBreakdown = linkedMapOf(
                "餐饮" to 980.00, "交通" to 340.00, "居住" to 1500.00, "娱乐" to 300.00
            ),
            topCategory = "居住",
            savingTip = "本月「居住」支出最高（¥1500.00，占比 48%），建议关注房租/水电优化。",
            likes = 28,
            comments = listOf(
                CommunityComment("c-3", "老王", "居住占比一半，房租党抱团取暖", ts(2026, 7, 15, 21, 0))
            ),
            timestamp = ts(2026, 7, 15, 8, 30)
        ),
        CommunityPost(
            postId = "sample-03",
            username = "Luna",
            month = "2026-08",
            totalExpense = 5210.00,
            categoryBreakdown = linkedMapOf(
                "餐饮" to 1200.00, "购物" to 2100.00, "交通" to 460.00,
                "居住" to 1050.00, "娱乐" to 400.00
            ),
            budgetBreakdown = linkedMapOf(
                "餐饮" to 1500.00, "购物" to 1500.00, "交通" to 500.00,
                "居住" to 1200.00, "娱乐" to 500.00
            ),
            topCategory = "购物",
            savingTip = "本月「购物」支出最高（¥2100.00，占比 40%），购物前先列清单可能更省钱。",
            likes = 67,
            comments = listOf(
                CommunityComment("c-4", "小星星", "购物达人！这个月没少买买买", ts(2026, 8, 25, 12, 10)),
                CommunityComment("c-5", "Momo", "求推荐好用的记账 App", ts(2026, 8, 26, 9, 45))
            ),
            timestamp = ts(2026, 8, 25, 11, 0)
        ),
        CommunityPost(
            postId = "sample-04",
            username = "老王",
            month = "2026-06",
            totalExpense = 6850.00,
            categoryBreakdown = linkedMapOf(
                "餐饮" to 1300.00, "交通" to 950.00, "购物" to 1800.00,
                "居住" to 2200.00, "医疗" to 600.00
            ),
            topCategory = "居住",
            savingTip = "本月「居住」支出最高（¥2200.00，占比 32%），注意控制非必要开支。",
            likes = 15,
            comments = emptyList(),
            timestamp = ts(2026, 6, 10, 7, 0)
        ),
        CommunityPost(
            postId = "sample-05",
            username = "小鹿",
            month = "2026-08",
            totalExpense = 2680.80,
            categoryBreakdown = linkedMapOf(
                "餐饮" to 890.00, "交通" to 210.80, "居住" to 980.00, "娱乐" to 600.00
            ),
            topCategory = "居住",
            savingTip = "本月「居住」支出最高（¥980.00，占比 37%），整体控制得不错，继续保持！",
            likes = 51,
            comments = listOf(
                CommunityComment("c-6", "阿杰", "羡慕这个消费水平", ts(2026, 8, 27, 20, 15))
            ),
            timestamp = ts(2026, 8, 27, 8, 20)
        ),
        CommunityPost(
            postId = "sample-06",
            username = "Momo",
            month = "2026-05",
            totalExpense = 4480.00,
            categoryBreakdown = linkedMapOf(
                "餐饮" to 1700.00, "购物" to 1300.00, "交通" to 480.00,
                "居住" to 1000.00
            ),
            topCategory = "餐饮",
            savingTip = "本月「餐饮」支出最高（¥1700.00，占比 38%），外卖点单前比价更省钱。",
            likes = 33,
            comments = emptyList(),
            timestamp = ts(2026, 5, 18, 14, 0)
        )
    )

    /** 构造模拟时间戳 */
    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute, 0)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
}
