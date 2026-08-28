package com.ousuan.smartbutler.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 社区帖子缓存的 DAO：在线拉取落库 / 离线读取 / 点赞评论后更新单条 */
@Dao
interface CachedPostDao {

    @Query("SELECT * FROM cached_posts ORDER BY timestamp DESC")
    suspend fun getAll(): List<CachedPost>

    @Query("SELECT * FROM cached_posts ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<CachedPost>>

    @Query("SELECT * FROM cached_posts WHERE postId = :postId LIMIT 1")
    suspend fun getByPostId(postId: String): CachedPost?

    @Query("SELECT * FROM cached_posts WHERE username = :username AND month = :month LIMIT 1")
    suspend fun getByUsernameAndMonth(username: String, month: String): CachedPost?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(post: CachedPost)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(posts: List<CachedPost>)

    @Query("UPDATE cached_posts SET likes = :likes WHERE postId = :postId")
    suspend fun updateLikes(postId: String, likes: Int)

    /** 覆盖指定帖子的 comments JSON 字符串 */
    @Query("UPDATE cached_posts SET comments = :commentsJson WHERE postId = :postId")
    suspend fun updateComments(postId: String, commentsJson: String)

    @Query("DELETE FROM cached_posts WHERE postId = :postId")
    suspend fun delete(postId: String)

    @Query("DELETE FROM cached_posts")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM cached_posts")
    suspend fun count(): Int
}
