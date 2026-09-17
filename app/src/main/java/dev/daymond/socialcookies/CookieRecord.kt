package dev.daymond.socialcookies

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cookies",
    indices = [
        Index(value = ["userUid"]),
        Index(value = ["host"]),
        Index(value = ["uid"])
    ]
)
data class CookieRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val userUid: String = "",
    val host: String,
    val uid: String,
    val cookies: String,
    val password: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val isLive: Boolean = true
)
