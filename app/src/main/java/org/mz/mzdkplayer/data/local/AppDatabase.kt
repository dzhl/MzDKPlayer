package org.mz.mzdkplayer.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration // 👈 记得导入
import androidx.sqlite.db.SupportSQLiteDatabase // 👈 记得导入

@Database(entities = [MediaCacheEntity::class,MediaHistoryEntity::class], version = 3, exportSchema = false) // 👈 版本改为 3
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun mediaHistoryDao(): MediaHistoryDao // 新增

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 👇 【定义 V1 到 V2 的迁移】 👇
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 新增的列都是 String 类型，在 Room 中对应 TEXT NOT NULL，
                // 必须提供 DEFAULT 值，否则无法将现有数据升级。
                db.execSQL("ALTER TABLE media_cache ADD COLUMN dataSourceType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_cache ADD COLUMN fileName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE media_cache ADD COLUMN connectionName TEXT NOT NULL DEFAULT ''")
            }
        }

        // 👆 【定义 V1 到 V2 的迁移】 👆
        // 定义 V2 到 V3 的迁移
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `media_history` (
                        `mediaUri` TEXT NOT NULL, 
                        `fileName` TEXT NOT NULL, 
                        `playbackPosition` INTEGER NOT NULL, 
                        `mediaDuration` INTEGER NOT NULL, 
                        `protocolName` TEXT NOT NULL, 
                        `connectionName` TEXT NOT NULL, 
                        `serverAddress` TEXT NOT NULL, 
                        `timestamp` INTEGER NOT NULL, 
                        `mediaType` TEXT NOT NULL, 
                        PRIMARY KEY(`mediaUri`)
                    )
                """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mzdk_player_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3) // 添加迁移
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }

}