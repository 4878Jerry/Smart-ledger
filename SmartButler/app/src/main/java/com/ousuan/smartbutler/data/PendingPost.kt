package com.ousuan.smartbutler.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 待发布帖子实体（表 pending_posts）：
 * 离线时发布的帖子先暂存于此，网络恢复后由 SyncManager 自动补发到服务器。
 */
@Entity(tableName = "pending_posts")
data class PendingPost(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 待发布的帖子数据（JSON 字符串，序列化 PostRequest 内容） */
    val postData: String,
    /** 创建时间（毫秒时间戳） */
    val createdAt: Long
)
