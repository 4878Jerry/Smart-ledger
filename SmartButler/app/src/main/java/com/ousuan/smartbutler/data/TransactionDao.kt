package com.ousuan.smartbutler.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {

    @Insert
    suspend fun insert(transaction: Transaction): Long

    @Update
    suspend fun update(transaction: Transaction)

    @Delete
    suspend fun delete(transaction: Transaction)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 全部记录（仅当前用户），按日期倒序（列表页用，Flow 自动刷新） */
    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY date DESC, id DESC")
    fun observeByUser(userId: String): Flow<List<Transaction>>

    /** 全部记录（仅当前用户） */
    @Query("SELECT * FROM transactions WHERE userId = :userId ORDER BY date DESC, id DESC")
    suspend fun getAll(userId: String): List<Transaction>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): Transaction?

    /** 按月查询（仅当前用户），如 year=2026, month=8 表示 2026-08 */
    @Query("SELECT * FROM transactions WHERE userId = :userId AND substr(date, 1, 7) = printf('%04d-%02d', :year, :month) ORDER BY date DESC, id DESC")
    suspend fun getByMonth(userId: String, year: Int, month: Int): List<Transaction>

    /** 按年查询（仅当前用户），year 形如 "2026" */
    @Query("SELECT * FROM transactions WHERE userId = :userId AND substr(date, 1, 4) = :year ORDER BY date DESC, id DESC")
    suspend fun getByYear(userId: String, year: String): List<Transaction>

    /** 按日查询（仅当前用户），date 形如 "2026-08-23" */
    @Query("SELECT * FROM transactions WHERE userId = :userId AND date = :date ORDER BY id DESC")
    suspend fun getByDate(userId: String, date: String): List<Transaction>

    /** 更新单条记录的公开状态 */
    @Query("UPDATE transactions SET isPublic = :isPublic WHERE id = :id")
    suspend fun updatePublicStatus(id: Long, isPublic: Boolean)

    /** 全部公开记录（跨用户，isPublic = true，供社区浏览） */
    @Query("SELECT * FROM transactions WHERE isPublic = 1 ORDER BY date DESC, id DESC")
    suspend fun getAllPublic(): List<Transaction>

    /** 批量更新「当前用户」全部记录的公开状态（数据公开开关用，避免影响他人） */
    @Query("UPDATE transactions SET isPublic = :isPublic WHERE userId = :userId")
    suspend fun updateAllPublicStatus(userId: String, isPublic: Boolean)

    /** 未同步记录（云同步用，按录入时间升序） */
    @Query("SELECT * FROM transactions WHERE userId = :userId AND synced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsynced(userId: String): List<Transaction>

    /** 按 localId 标记为已同步 */
    @Query("UPDATE transactions SET synced = 1 WHERE localId IN (:localIds)")
    suspend fun markSynced(localIds: List<String>)

    /** 按 localId 查询（下载合并去重用：服务器记录与本地记录通过 localId 对应） */
    @Query("SELECT * FROM transactions WHERE localId = :localId LIMIT 1")
    suspend fun getByLocalId(localId: String): Transaction?

    /** 批量插入（服务器下载的记录） */
    @Insert
    suspend fun insertAll(transactions: List<Transaction>): List<Long>
}
