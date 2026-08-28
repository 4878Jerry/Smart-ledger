package com.ousuan.smartbutler.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface BudgetDao {

    /** 某用户全部预算（预算页 / 预警页读取），按分类名排序 */
    @Query("SELECT * FROM budgets WHERE userId = :userId ORDER BY category")
    suspend fun getByUser(userId: String): List<BudgetEntity>

    /** 单条写入：同分类覆盖、不同分类追加 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BudgetEntity)

    /** 批量写入（从服务器下载 / 本地生成方案时整包落库） */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<BudgetEntity>)

    /** 删除某用户单条分类预算 */
    @Query("DELETE FROM budgets WHERE userId = :userId AND category = :category")
    suspend fun delete(userId: String, category: String)

    /** 清空某用户全部预算（整包替换前调用） */
    @Query("DELETE FROM budgets WHERE userId = :userId")
    suspend fun deleteByUser(userId: String)
}
