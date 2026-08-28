package com.ousuan.smartbutler.data

import com.ousuan.smartbutler.data.model.PublicStats
import com.ousuan.smartbutler.data.model.User
import com.ousuan.smartbutler.util.ExpenseAnalyzer
import java.util.UUID
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/**
 * 数据仓库：封装所有数据库操作，UI 层统一从这里取数。
 *
 * 多用户数据隔离：所有查询/写入自动带当前登录用户的 userId；
 * 未登录时查询返回空、写入抛异常（不允许操作）。
 * [userProvider] 由 Application 注入，返回当前登录用户（未登录为 null）。
 */
class TransactionRepository(
    private val dao: TransactionDao,
    private val userProvider: () -> User? = { null }
) {
    /** 新记录插入后触发（由 Application 注入，用于立即同步到服务器） */
    var onInserted: (() -> Unit)? = null

    /** 当前用户 ID；未登录返回 null */
    private fun uidOrNull(): String? = userProvider()?.userId

    /**
     * 账号切换信号：切换账号成功后 +1。
     * [allTransactions] 通过 flatMapLatest 监听该信号，账号变化时自动以新 userId 重新订阅，
     * 从而让首页/列表/图表等已收集 Flow 的页面无需重启即可切换到新账号数据。
     */
    private val accountVersion = MutableStateFlow(0)

    /** 全部记录（仅当前用户，日期倒序，Flow 自动刷新）；未登录为空流 */
    @OptIn(FlowPreview::class)
    val allTransactions: Flow<List<Transaction>>
        get() = accountVersion.flatMapLatest {
            uidOrNull()?.let { dao.observeByUser(it) } ?: flowOf(emptyList())
        }

    /** 切换账号成功后调用：通知所有已订阅 [allTransactions] 的页面按新账号重新加载 */
    fun notifyAccountSwitched() {
        accountVersion.value++
    }

    /** 插入前检查登录状态，自动填入当前用户 userId、生成 localId、重置同步标记 */
    suspend fun insert(transaction: Transaction): Long {
        val uid = uidOrNull() ?: throw IllegalStateException("请先登录后再记账")
        val t = transaction.copy(
            userId = uid,
            localId = if (transaction.localId.isEmpty()) UUID.randomUUID().toString() else transaction.localId,
            synced = false
        )
        val id = dao.insert(t)
        // 本地落库成功后立即尝试同步到服务器（无网络时保持 synced=false，网络恢复后自动补传）
        onInserted?.invoke()
        return id
    }

    /**
     * 合并服务器下载的记录到本地（登录后拉取 / 网络恢复刷新用）。
     * 以 localId 为唯一键：本地已存在则跳过（保留本地状态），不存在则插入并标记已同步。
     * @return 实际新增条数
     */
    suspend fun mergeServerTransactions(list: List<Transaction>): Int {
        val uid = uidOrNull() ?: return 0
        var added = 0
        for (t in list) {
            if (t.localId.isEmpty()) continue
            if (dao.getByLocalId(t.localId) == null) {
                dao.insert(t.copy(userId = uid, synced = true))
                added++
            }
        }
        return added
    }

    /** 未同步记录（云同步上传用）；未登录返回空 */
    suspend fun getUnsynced(): List<Transaction> =
        uidOrNull()?.let { dao.getUnsynced(it) } ?: emptyList()

    /** 上传成功后按 localId 批量标记为已同步 */
    suspend fun markSynced(localIds: List<String>) {
        if (localIds.isNotEmpty()) dao.markSynced(localIds)
    }

    suspend fun update(transaction: Transaction) = dao.update(transaction)

    suspend fun delete(transaction: Transaction) = dao.delete(transaction)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /** 全部记录（仅当前用户）；未登录返回空 */
    suspend fun getAll(): List<Transaction> = uidOrNull()?.let { dao.getAll(it) } ?: emptyList()

    suspend fun getById(id: Long): Transaction? = dao.getById(id)

    /** 按月查询，prefix 形如 "2026-08"（仅当前用户） */
    suspend fun getByMonth(prefix: String): List<Transaction> {
        val uid = uidOrNull() ?: return emptyList()
        val parts = prefix.split("-")
        val year = parts.getOrNull(0)?.toIntOrNull() ?: return emptyList()
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return emptyList()
        return dao.getByMonth(uid, year, month)
    }

    /** 按年查询，year 形如 "2026"（仅当前用户） */
    suspend fun getByYear(year: String): List<Transaction> =
        uidOrNull()?.let { dao.getByYear(it, year) } ?: emptyList()

    /** 按日查询，date 形如 "2026-08-23"（仅当前用户） */
    suspend fun getByDate(date: String): List<Transaction> =
        uidOrNull()?.let { dao.getByDate(it, date) } ?: emptyList()

    /** 更新单条记录的公开状态 */
    suspend fun updatePublicStatus(id: Long, isPublic: Boolean) =
        dao.updatePublicStatus(id, isPublic)

    /** 全部公开记录（跨用户，供社区浏览） */
    suspend fun getAllPublic(): List<Transaction> = dao.getAllPublic()

    /** 批量设置「当前用户」全部记录的公开状态（数据公开开关用，不影响他人） */
    suspend fun updateAllPublicStatus(isPublic: Boolean) {
        val uid = uidOrNull() ?: throw IllegalStateException("请先登录")
        dao.updateAllPublicStatus(uid, isPublic)
    }

    /**
     * 生成某月对外公开的脱敏统计（仅汇总，不含逐笔明细）。
     * 只统计当前用户的支出类型；该月无支出或未登录时返回 null。
     */
    suspend fun getMonthlyStats(year: Int, month: Int): PublicStats? {
        val user = userProvider() ?: return null
        val rows = dao.getByMonth(user.userId, year, month)
        val breakdown = ExpenseAnalyzer.categoryAmounts(rows)
        val total = breakdown.values.sum()
        if (total <= 0) return null
        val top = breakdown.maxByOrNull { it.value }?.key ?: return null
        val topAmount = breakdown[top] ?: 0.0
        val tip = "本月「$top」支出最高（¥${"%.2f".format(topAmount)}，" +
            "占比 ${(topAmount / total * 100).toInt()}%），可适当控制该分类开销"
        return PublicStats(
            username = user.username,
            month = String.format("%d-%02d", year, month),
            totalExpense = total,
            categoryBreakdown = breakdown,
            topCategory = top,
            savingTip = tip
        )
    }
}
