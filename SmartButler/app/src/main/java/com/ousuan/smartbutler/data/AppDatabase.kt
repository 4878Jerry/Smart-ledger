package com.ousuan.smartbutler.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Transaction::class, CachedPost::class, PendingPost::class, BudgetEntity::class],
    version = 9,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun cachedPostDao(): CachedPostDao

    abstract fun pendingPostDao(): PendingPostDao

    abstract fun budgetDao(): BudgetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v1 → v2：新增 userId 列（多用户数据隔离），历史数据默认空串 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN userId TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v2 → v3：新增 localId（云同步幂等标识）与 synced（是否已同步）列 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN localId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE transactions ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3 → v4：新增社区缓存表 cached_posts 与待发布表 pending_posts（纯建表，已有数据保留） */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_posts` (" +
                        "`postId` TEXT NOT NULL, " +
                        "`username` TEXT NOT NULL, " +
                        "`month` TEXT NOT NULL, " +
                        "`totalExpense` REAL NOT NULL, " +
                        "`categoryBreakdown` TEXT NOT NULL, " +
                        "`topCategory` TEXT NOT NULL, " +
                        "`savingTip` TEXT NOT NULL, " +
                        "`likes` INTEGER NOT NULL, " +
                        "`comments` TEXT NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`postId`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_posts` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`postData` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        /** v4 → v5：cached_posts 新增 updatedAt 列（最近更新时间），历史数据回填为 timestamp */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cached_posts` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE `cached_posts` SET `updatedAt` = `timestamp` WHERE `updatedAt` = 0")
            }
        }

        /** v5 → v6：cached_posts 新增 budgetBreakdown 列（预算方案 JSON，默认空串表示无预算） */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cached_posts` ADD COLUMN `budgetBreakdown` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v6 → v7：cached_posts 新增 visibility 列（帖子可见度，老缓存回填 public 视为公开） */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cached_posts` ADD COLUMN `visibility` TEXT NOT NULL DEFAULT 'public'")
            }
        }

        /** v7 → v8：cached_posts 新增 dataVisibility / budgetVisibility 列（模块级可见度，老缓存回填 public） */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `cached_posts` ADD COLUMN `dataVisibility` TEXT NOT NULL DEFAULT 'public'")
                db.execSQL("ALTER TABLE `cached_posts` ADD COLUMN `budgetVisibility` TEXT NOT NULL DEFAULT 'public'")
            }
        }

        /** v8 → v9：新增预算表 budgets（预算云同步：本地缓存 users.budget_json 副本） */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `budgets` (" +
                        "`userId` TEXT NOT NULL, " +
                        "`category` TEXT NOT NULL, " +
                        "`amount` REAL NOT NULL, " +
                        "PRIMARY KEY(`userId`, `category`))"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smartbutler.db"
                )
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                        MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                        MIGRATION_8_9
                    )
                    .build().also { INSTANCE = it }
            }
    }
}
