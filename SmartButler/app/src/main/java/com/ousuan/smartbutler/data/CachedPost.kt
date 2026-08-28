package com.ousuan.smartbutler.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 社区帖子缓存实体（表 cached_posts）：
 * 在线时从服务器 /api/stats/public 拉取后落库，离线时社区列表直接读此表展示，
 * 保证断网时社区仍显示最近一次在线看到的数据（而非空白）。
 */
@Entity(tableName = "cached_posts")
data class CachedPost(
    /** 帖子 ID（对应服务器 post_id） */
    @PrimaryKey
    val postId: String,
    /** 发布者用户名 */
    val username: String,
    /** 统计月份 yyyy-MM */
    val month: String,
    /** 月度总支出（元） */
    val totalExpense: Double,
    /** 分类 → 金额（JSON 字符串） */
    val categoryBreakdown: String,
    /** 分类 → 预算金额（JSON 字符串，预算方案模块；老缓存回填为空串） */
    val budgetBreakdown: String = "",
    /** 支出最高的分类 */
    val topCategory: String,
    /** 省钱建议 */
    val savingTip: String,
    /** 点赞数 */
    val likes: Int,
    /** 评论列表（JSON 字符串） */
    val comments: String,
    /** 首次发布时间（毫秒时间戳） */
    val timestamp: Long,
    /** 最近更新时间（毫秒时间戳） */
    val updatedAt: Long = timestamp,
    /** 帖子级可见度：public 公开 / private 仅自己可见（老缓存回填 public） */
    val visibility: String = "public",
    /** 消费数据模块可见度：public 公开 / private 仅自己可见（老缓存回填 public） */
    val dataVisibility: String = "public",
    /** 预算方案模块可见度：public 公开 / private 仅自己可见（老缓存回填 public） */
    val budgetVisibility: String = "public"
)
