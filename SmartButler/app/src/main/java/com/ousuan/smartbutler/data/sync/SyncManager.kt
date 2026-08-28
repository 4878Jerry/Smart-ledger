package com.ousuan.smartbutler.data.sync

import android.util.Log
import com.ousuan.smartbutler.data.Transaction
import com.ousuan.smartbutler.data.TransactionRepository
import com.ousuan.smartbutler.data.model.SyncRequest
import com.ousuan.smartbutler.data.model.TransactionOut
import com.ousuan.smartbutler.data.model.TransactionRequest
import com.ousuan.smartbutler.data.network.ApiClient
import com.ousuan.smartbutler.data.network.NetworkChecker
import com.ousuan.smartbutler.data.network.NetworkMonitor
import com.ousuan.smartbutler.data.repository.BudgetRepository
import com.ousuan.smartbutler.data.repository.CommunityRepository
import com.ousuan.smartbutler.data.repository.UserRepository
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 云同步管理器（本地数据库 = 服务器缓存副本）：
 * - [syncTransactions]：本地未同步记录批量上传服务器，成功后标记 synced；
 * - [downloadAllData]：登录后从服务器下载当前用户全部记录 + 我的帖子，合并到本地；
 * - [downloadTransactions]：GET /api/transactions → 合并进 Room（按 localId 去重）；
 * - [downloadMyPosts]：GET /api/posts/mine → 合并进社区列表；
 * - [syncOnNetworkRestore]：网络恢复 / App 启动时双向同步（先上传未同步，再下载服务器最新）。
 */
class SyncManager(
    private val scope: CoroutineScope,
    private val repository: TransactionRepository,
    private val userRepository: UserRepository,
    private val budgetRepository: BudgetRepository
) {

    private val currentUser get() = userRepository.getCurrentUser()

    /** 网络恢复 / App 启动 / 新记录插入时调用：异步双向同步（服务器不可用时跳过，不抛异常） */
    fun syncOnNetworkRestore() {
        scope.launch {
            if (!NetworkChecker.checkServerAvailable()) {
                Log.d("Sync", "服务器不可用，跳过本次同步（运行在离线模式）")
                return@launch
            }
            val pendingCount = CommunityRepository.pendingPostCount()
            Log.d("Offline", "待发布帖子数量: $pendingCount")
            val uploadOk = syncTransactions()
            // 补推离线期间更改的「数据公开」开关状态（PUT /api/users/settings）
            val settingsOk = userRepository.syncDataPublicSetting()
            // 补推离线期间更改的预算（PUT /api/users/budget）
            val budgetOk = budgetRepository.syncPending(currentUser?.userId)
            // 补发离线期间暂存的待发布帖子
            val publishOk = CommunityRepository.publishPendingPosts()
            // 刷新社区缓存（拉取最新公开帖子写入 cached_posts）
            val cacheOk = CommunityRepository.refreshCacheFromServer()
            val downloadOk = downloadAllData()
            Log.d("Offline", "同步完成: 记录上传=${if (uploadOk) "成功" else "失败"}，开关补推=$settingsOk，预算补推=$budgetOk，待发布补发=$publishOk，缓存刷新=$cacheOk，下载=${if (downloadOk) "成功" else "失败"}")
        }
    }

    /**
     * 监听系统网络状态变化：从「离线」恢复为「在线」时自动触发一次双向同步。
     * 由 SmartButlerApp.onCreate 调用；仅在 false→true 跃迁时触发，避免重复同步。
     */
    fun observeNetworkState() {
        scope.launch {
            var previous: Boolean? = null
            NetworkMonitor.isConnectedFlow.collect { connected ->
                val prev = previous
                previous = connected
                if (prev == false && connected) {
                    Log.d("NetworkMonitor", "网络已恢复，触发自动同步")
                    syncOnNetworkRestore()
                }
            }
        }
    }

    /** 下载当前用户全部数据（消费记录 + 我的帖子）；服务器不可用时跳过，不抛异常 */
    suspend fun downloadAllData(): Boolean {
        if (!NetworkChecker.checkServerAvailable()) {
            Log.d("Sync", "服务器不可用，跳过数据下载")
            return false
        }
        Log.d("Sync", "开始下载所有数据...")
        val txCount = downloadTransactions()
        val postCount = downloadMyPosts()
        val budgetOk = budgetRepository.fetchFromServer(currentUser?.userId ?: "")
        Log.d("Sync", "下载了 $txCount 条记录，$postCount 条帖子，预算=${if (budgetOk) "已调和" else "未调和"}")
        return true
    }

    /** 从服务器下载当前用户所有消费记录，合并进本地 Room */
    suspend fun downloadTransactions(): Int {
        if (currentUser == null) {
            Log.d("Sync", "未登录，跳过记录下载")
            return 0
        }
        val result = ApiClient.safeApiCall { ApiClient.service.getTransactions() }
        result.onSuccess { resp ->
            if (resp.code == 0 && resp.data != null) {
                val local = resp.data.map { it.toLocalTransaction() }
                val added = repository.mergeServerTransactions(local)
                Log.d("Sync", "下载了 $added 条记录（服务器共 ${resp.data.size} 条）")
                return added
            }
            Log.w("Sync", "下载记录失败: ${resp.msg}")
        }.onFailure { e ->
            Log.d("Sync", "同步失败: ${e.message}")
        }
        return 0
    }

    /** 从服务器下载当前用户发布的帖子，合并进社区列表 */
    suspend fun downloadMyPosts(): Int {
        if (currentUser == null) {
            Log.d("Sync", "未登录，跳过帖子下载")
            return 0
        }
        val result = ApiClient.safeApiCall { ApiClient.service.getMyPosts() }
        result.onSuccess { resp ->
            if (resp.code == 0 && resp.data != null) {
                val added = CommunityRepository.mergeServerPosts(resp.data)
                Log.d("Sync", "下载了 $added 条帖子（服务器共 ${resp.data.size} 条）")
                return added
            }
            Log.w("Sync", "下载帖子失败: ${resp.msg}")
        }.onFailure { e ->
            Log.d("Sync", "同步失败: ${e.message}")
        }
        return 0
    }

    /**
     * 增量上传：未登录或无未同步记录时直接返回 true。
     * @return 是否全部上传成功
     */
    suspend fun syncTransactions(): Boolean {
        if (currentUser == null) return true // 未登录不同步
        val unsynced = repository.getUnsynced()
        if (unsynced.isEmpty()) return true

        val result = ApiClient.safeApiCall {
            ApiClient.service.syncTransactions(
                SyncRequest(unsynced.map { it.toRequest() })
            )
        }

        result.onSuccess { resp ->
            if (resp.code == 0 && resp.data != null) {
                // 服务器按 localId 幂等；失败列表不标记，留给下次重试
                val failed = resp.data.failedIds.toSet()
                val syncedIds = unsynced
                    .filter { it.localId.isNotEmpty() && it.localId !in failed }
                    .map { it.localId }
                repository.markSynced(syncedIds)
                Log.d("SyncManager", "上传成功 ${syncedIds.size} 条，失败 ${failed.size} 条")
                return true
            }
            Log.w("SyncManager", "同步被拒绝：${resp.msg}")
        }.onFailure { e ->
            Log.w("Sync", "同步失败: ${e.message}")
        }
        return false
    }

    /** 本地记录 → 上传请求体 */
    private fun Transaction.toRequest(): TransactionRequest = TransactionRequest(
        localId = localId,
        amount = amount,
        category = category,
        type = type,
        note = note,
        timestamp = timestamp,
        isPublic = isPublic
    )

    /** 服务器记录 → 本地实体（date 由 timestamp 推导，localId 复用服务器保存的 UUID） */
    private fun TransactionOut.toLocalTransaction(): Transaction {
        val date = Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
        return Transaction(
            date = date,
            type = type,
            category = category,
            amount = amount,
            note = note,
            timestamp = timestamp,
            isPublic = isPublic,
            localId = localId ?: UUID.randomUUID().toString(),
            synced = true
        )
    }
}
