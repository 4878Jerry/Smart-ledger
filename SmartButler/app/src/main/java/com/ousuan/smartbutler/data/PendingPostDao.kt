package com.ousuan.smartbutler.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 待发布帖子的 DAO：离线发布暂存 / 网络恢复后读取补发 / 成功后删除 */
@Dao
interface PendingPostDao {

    @Query("SELECT * FROM pending_posts ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingPost>

    @Query("SELECT * FROM pending_posts ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingPost>>

    @Insert
    suspend fun insert(post: PendingPost): Long

    @Query("DELETE FROM pending_posts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_posts")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM pending_posts")
    suspend fun count(): Int
}
