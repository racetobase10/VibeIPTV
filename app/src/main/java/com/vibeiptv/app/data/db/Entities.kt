package com.vibeiptv.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val refId: String,
    val type: String,
    val name: String,
    val logo: String?,
    val addedAt: Long
)

@Entity(tableName = "resume")
data class ResumeEntity(
    @PrimaryKey val contentId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

@Entity(tableName = "epg_programmes")
data class EpgProgrammeEntity(
    @PrimaryKey(autoGenerate = true) val uid: Long = 0,
    val channelKey: String,
    val startUtc: Long,
    val stopUtc: Long,
    val title: String,
    val desc: String?
)

@Entity(tableName = "epg_meta")
data class EpgMetaEntity(
    @PrimaryKey val key: String,
    val value: String
)
