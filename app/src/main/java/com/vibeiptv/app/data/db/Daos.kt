package com.vibeiptv.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE refId=:id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM favorites WHERE type=:t ORDER BY addedAt DESC")
    suspend fun byType(t: String): List<FavoriteEntity>

    @Query("SELECT refId FROM favorites WHERE type=:t")
    suspend fun idsByType(t: String): List<String>
}

@Dao
interface ResumeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: ResumeEntity)

    @Query("SELECT * FROM resume WHERE contentId=:id")
    suspend fun get(id: String): ResumeEntity?

    @Query("DELETE FROM resume WHERE contentId=:id")
    suspend fun delete(id: String)
}

@Dao
interface EpgDao {
    @Insert
    suspend fun insertAll(list: List<EpgProgrammeEntity>)

    @Query("DELETE FROM epg_programmes")
    suspend fun clearAll()

    @Query("SELECT * FROM epg_programmes WHERE channelKey=:k AND stopUtc>:from AND startUtc<:to ORDER BY startUtc")
    suspend fun window(k: String, from: Long, to: Long): List<EpgProgrammeEntity>

    @Query("SELECT * FROM epg_programmes WHERE channelKey=:k AND startUtc<=:now AND stopUtc>:now LIMIT 1")
    suspend fun nowAt(k: String, now: Long): EpgProgrammeEntity?

    @Query("SELECT * FROM epg_programmes WHERE channelKey=:k AND startUtc>=:now ORDER BY startUtc LIMIT 1")
    suspend fun nextAfter(k: String, now: Long): EpgProgrammeEntity?
}

@Dao
interface EpgMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(m: EpgMetaEntity)

    @Query("SELECT value FROM epg_meta WHERE `key`=:k")
    suspend fun get(k: String): String?
}
