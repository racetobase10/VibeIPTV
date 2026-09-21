package com.vibeiptv.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        ResumeEntity::class,
        EpgProgrammeEntity::class,
        EpgMetaEntity::class
    ],
    version = 1
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favoriteDao(): FavoriteDao
    abstract fun resumeDao(): ResumeDao
    abstract fun epgDao(): EpgDao
    abstract fun epgMetaDao(): EpgMetaDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(ctx: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "vibeiptv.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
