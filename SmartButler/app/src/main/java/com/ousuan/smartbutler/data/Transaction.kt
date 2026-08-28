package com.ousuan.smartbutler.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 记账实体：字段完全照搬 C++ expense_analyzer.cpp 的 Record 结构体
 * （id、date、type、category、amount、payee、note），额外增加
 * timestamp（毫秒）、userId（多用户数据隔离）与 isPublic（默认 false）。
 */
@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    /** 日期 YYYY-MM-DD */
    val date: String,
    /** 收支类型：支出 / 收入 */
    val type: String,
    /** 消费分类：见 Categories.EXPENSE / INCOME */
    val category: String,
    /** 金额（元） */
    val amount: Double,
    /** 收款方 / 商户 */
    val payee: String = "",
    /** 备注 */
    val note: String = "",
    /** 录入时间（毫秒时间戳） */
    val timestamp: Long = System.currentTimeMillis(),
    /** 所属用户 ID（多用户数据隔离；v1 迁移前历史数据为空串） */
    @ColumnInfo(defaultValue = "''")
    val userId: String = "",
    /** 是否公开（预留社区功能，默认 false） */
    val isPublic: Boolean = false,
    /** 本地稳定标识（插入时生成 UUID），用于服务器端增量同步幂等去重 */
    @ColumnInfo(defaultValue = "''")
    val localId: String = "",
    /** 是否已同步到服务器（默认 false；v3 迁移前历史数据为 0） */
    val synced: Boolean = false
)
