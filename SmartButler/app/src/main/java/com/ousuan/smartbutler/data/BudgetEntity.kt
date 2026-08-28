package com.ousuan.smartbutler.data

import androidx.room.Entity

/**
 * 预算实体（表 budgets）：每个用户的预算方案（分类 → 金额）。
 * 本地缓存服务器 users.budget_json 的副本，按 userId 隔离多账号；
 * 写入本地立即生效，离线修改标记待同步，网络恢复自动补推服务器。
 */
@Entity(tableName = "budgets", primaryKeys = ["userId", "category"])
data class BudgetEntity(
    /** 用户 ID（对应 User.userId，服务器用户为数字 ID 字符串） */
    val userId: String,
    /** 分类名（如 餐饮 / 交通 / 购物 ...） */
    val category: String,
    /** 该分类的月预算金额（元） */
    val amount: Double
)
