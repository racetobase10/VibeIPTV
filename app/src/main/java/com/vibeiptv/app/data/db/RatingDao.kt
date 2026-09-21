package com.vibeiptv.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Cache for external ratings (TMDB / OMDb). Keyed by a normalized
 * "type|title|year" string so repeat lookups never hit the network twice.
 */
@Entity(tableName = "rating_cache")
data class RatingEntity(
    @PrimaryKey val key: String,
    val rating: Double,
    /** e.g. "TMDB" or "OMDb". */
    val source: String,
    val updatedAt: Long
)

@Dao
interface RatingDao {
    @Query("SELECT * FROM rating_cache WHERE `key`=:k LIMIT 1")
    suspend fun get(k: String): RatingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: RatingEntity)

    @Query("DELETE FROM rating_cache")
    suspend fun clearAll()
}
