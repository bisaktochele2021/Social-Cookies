package dev.daymond.socialcookies

import androidx.annotation.Keep

/**
 * Model representing App Update details stored in Firebase Realtime Database.
 */
@Keep
data class AppUpdateInfo(
    val latestVersionCode: Long = 0L,
    val latestVersionName: String = "",
    val minVersionCode: Long = 0L,
    val isForceUpdate: Boolean = false,
    val updateTitle: String = "New Update Available!",
    val releaseNotes: String = "",
    val downloadUrl: String = ""
)

/**
 * Model representing an In-App Notice stored in Firebase Realtime Database.
 */
@Keep
data class NoticeInfo(
    val enabled: Boolean = false,
    val id: String = "",
    val title: String = "Notice",
    val message: String = "",
    val buttonText: String = "Got It",
    val buttonUrl: String = "",
    val canDismiss: Boolean = true,
    val showOnce: Boolean = true
)
