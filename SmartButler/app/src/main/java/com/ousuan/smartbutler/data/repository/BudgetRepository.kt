package com.ousuan.smartbutler.data.repository

import android.content.Context
import android.util.Log
import com.ousuan.smartbutler.data.BudgetDao
import com.ousuan.smartbutler.data.BudgetEntity
import com.ousuan.smartbutler.data.BudgetPrefs
import com.ousuan.smartbutler.data.model.BudgetUpdateRequest
import com.ousuan.smartbutler.data.network.ApiClient

/**
 * 预算仓库：本地 Room 缓存（表 budgets）+ 服务器同步（users.budget_json）。
 *
 * - 保存：先写本地（立即生效），再真实请求 PUT 服务器（局域网/Tailscale 会被
 *   NetworkMonitor 误判离线，故不以 isConnected 短路，直接请求，失败才标记待同步）；
 * - 网络恢复：SyncManager 调用 [syncPending] 补推服务器；
 * - 登录后：downloadAllData 调用 [fetchFromServer] 拉取服务器预算调和到本地（重装恢复）；
 *   本地存在「未上传的离线修改」（pending 标记）时以本地为准补推，绝不用旧服务器数据覆盖。
 *
 * 兼容旧版：写本地时同步写入 [BudgetPrefs]，保证预警页 / 社区发帖等旧读取点数据一致。
 */
class BudgetRepository(
    context: Context,
    private val budgetDao: BudgetDao
) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 某账号「预算待同步」标志 key（离线时本地已改，待网络恢复推送服务器） */
    private fun pendingKey(userId: String) = "$KEY_PENDING_PREFIX$userId"

    /** 某账号是否有待同步的预算（离线修改后网络恢复补推用） */
    fun isPendingSync(userId: String?): Boolean =
        !userId.isNullOrBlank() && prefs.getBoolean(pendingKey(userId), false)

    private fun setPendingSync(userId: String, pending: Boolean) {
        prefs.edit().putBoolean(pendingKey(userId), pending).apply()
    }

    /** 读取某用户预算（分类 → 金额）；无数据返回空 Map */
    suspend fun getBudgetMap(userId: String): Map<String, Double> =
        budgetDao.getByUser(userId).associate { it.category to it.amount }

    /** 某用户预算总额 */
    suspend fun getTotal(userId: String): Double =
        budgetDao.getByUser(userId).sumOf { it.amount }

    /** 某用户是否已设置预算 */
    suspend fun hasBudget(userId: String): Boolean = getBudgetMap(userId).isNotEmpty()

    /**
     * 保存整包预算（本地生成方案 / 用户手动修改）：先写本地立即生效，
     * 再尝试推送服务器；离线时标记待同步，网络恢复自动补推。
     */
    suspend fun saveBudget(userId: String, budget: Map<String, Double>) {
        Log.d(TAG, "saveBudget 被调用: userId=$userId 分类数=${budget.size} 总额=${budget.values.sum()}")
        if (userId.isNotBlank()) {
            budgetDao.deleteByUser(userId)
            budgetDao.upsertAll(budget.map { (cat, amt) -> BudgetEntity(userId, cat, amt) })
            Log.d(TAG, "已写入本地 Room (userId=$userId)")
        } else {
            Log.w(TAG, "未登录（userId 为空），预算仅保存到本地 BudgetPrefs，不做云同步")
        }
        // 同步写旧缓存，保证预警页 / 社区发帖等旧读取点数据一致
        BudgetPrefs.save(appContext, budget.values.sum(), budget)
        pushToServer(userId, budget)
    }

    /**
     * 把当前用户预算推送到服务器（PUT /api/users/budget）。
     * 注意：不依赖 [NetworkMonitor.isConnected] 做短路 —— 局域网/Tailscale 无外网验证会被
     * 误判离线，若短路会导致本地预算永远推不上服务器，网络恢复时 fetchFromServer 又会用
     * 服务器旧数据覆盖本地新修改。这里直接真实请求，失败才标记待同步。
     * @return 是否已成功同步到服务器
     */
    suspend fun pushToServer(userId: String, budget: Map<String, Double>): Boolean {
        if (userId.isBlank()) {
            Log.w(TAG, "pushToServer 跳过: 未登录（userId 为空），预算仅存本地")
            return false // 未登录只存本地，不同步
        }
        Log.d(TAG, "pushToServer 开始上传预算: userId=$userId 分类数=${budget.size}")
        val result = ApiClient.safeApiCall {
            ApiClient.service.putBudget(BudgetUpdateRequest(budget))
        }
        return result.fold(
            onSuccess = { resp ->
                val ok = resp.code == 0
                setPendingSync(userId, !ok)
                if (!ok) {
                    Log.w(TAG, "预算同步被服务器拒绝: code=${resp.code} msg=${resp.msg}")
                } else {
                    Log.d(TAG, "预算已成功上传服务器: ${budget.size} 个分类")
                }
                ok
            },
            onFailure = { e ->
                Log.w(TAG, "预算同步失败(${e::class.java.simpleName}: ${e.message})，标记待同步")
                setPendingSync(userId, true)
                false
            }
        )
    }

    /**
     * 登录后 / 网络恢复：从服务器拉取预算调和到本地（重装恢复、多端同步）。
     * - 服务器有预算：覆盖本地（服务器为权威，保留最近一次上传结果）；
     * - 服务器无预算但本地有：保留本地并立即补传（登录前设置的预算不丢失）；
     * - 服务器本地都无：检查旧 BudgetPrefs 遗留预算（未登录时设置过），迁移到账号并上传。
     */
    suspend fun fetchFromServer(userId: String): Boolean {
        Log.d(TAG, "fetchFromServer 被调用: userId=$userId")
        if (userId.isBlank()) {
            Log.w(TAG, "fetchFromServer 跳过: 未登录（userId 为空）")
            return false
        }
        val result = ApiClient.safeApiCall { ApiClient.service.getBudget() }
        result.onSuccess { resp ->
            if (resp.code == 0 && resp.data != null) {
                val serverBudget = resp.data.budget
                val localBudget = getBudgetMap(userId)
                Log.d(TAG, "服务器预算=${serverBudget.size} 个分类，本地 Room 预算=${localBudget.size} 个分类")
                // 本地存在「未上传的离线修改」（push 失败标记的 pending）：以本地为准。
                // 先补推本地（成功则服务器已与本地一致）；补推失败则保留本地，
                // 绝不用旧服务器数据覆盖新本地修改（防止预算页刚改的方案被回滚）。
                if (isPendingSync(userId) && localBudget.isNotEmpty()) {
                    val pushed = pushToServer(userId, localBudget)
                    if (!pushed) {
                        Log.w(TAG, "fetchFromServer: 本地有待同步预算且补推失败，保留本地不覆盖（网络恢复后再补推）")
                    } else {
                        Log.d(TAG, "fetchFromServer: 本地待同步预算已补推成功，服务器与本地一致")
                    }
                    return true
                }
                if (serverBudget.isEmpty() && localBudget.isNotEmpty()) {
                    // 服务器无预算但本地有：保留本地并补传（未登录时设置的预算登录后不丢）
                    Log.d(TAG, "服务器无预算，保留本地 ${localBudget.size} 个分类并上传")
                    pushToServer(userId, localBudget)
                    return true
                }
                if (serverBudget.isEmpty() && localBudget.isEmpty() && BudgetPrefs.hasBudget(appContext)) {
                    // 服务器与 Room 均无、但旧 BudgetPrefs 有遗留（未登录时设置过）：
                    // 迁移到当前账号并上传，避免登录后预算丢失
                    val legacy = BudgetPrefs.allCategoryBudgets(appContext)
                    if (legacy.isNotEmpty()) {
                        Log.d(TAG, "检测到 BudgetPrefs 遗留预算 ${legacy.size} 个分类，迁移到账号 $userId 并上传")
                        budgetDao.deleteByUser(userId)
                        budgetDao.upsertAll(legacy.map { (cat, amt) -> BudgetEntity(userId, cat, amt) })
                        pushToServer(userId, legacy)
                        return true
                    }
                }
                budgetDao.deleteByUser(userId)
                budgetDao.upsertAll(serverBudget.map { (cat, amt) -> BudgetEntity(userId, cat, amt) })
                BudgetPrefs.save(appContext, serverBudget.values.sum(), serverBudget)
                setPendingSync(userId, false)
                Log.d(TAG, "预算已从服务器下载: ${serverBudget.size} 个分类")
                return true
            }
            Log.w(TAG, "下载预算失败: code=${resp.code} msg=${resp.msg}")
        }.onFailure { e ->
            Log.w(TAG, "下载预算失败(${e::class.java.simpleName}: ${e.message})")
        }
        return false
    }

    /** 网络恢复补推：当前用户有待同步预算则推送服务器（无待同步直接跳过） */
    suspend fun syncPending(userId: String?): Boolean {
        if (userId.isNullOrBlank()) {
            Log.w(TAG, "syncPending 跳过: 未登录")
            return true
        }
        if (!isPendingSync(userId)) {
            Log.d(TAG, "syncPending 跳过: userId=$userId 无待同步标记")
            return true
        }
        Log.d(TAG, "syncPending 开始补推: userId=$userId")
        val budget = getBudgetMap(userId)
        if (budget.isEmpty()) {
            // 本地无预算但标记了待同步：直接拉服务器覆盖并清标志
            Log.d(TAG, "syncPending: 本地无预算但有待同步标记，拉取服务器覆盖")
            return fetchFromServer(userId)
        }
        return pushToServer(userId, budget)
    }

    companion object {
        private const val TAG = "BudgetRepository"
        private const val PREFS = "user_prefs"
        private const val KEY_PENDING_PREFIX = "budget_pending_"
    }
}
