package dev.daymond.socialcookies

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UserSyncManager coordinates Firebase authentication user synchronization
 * with the PHP/MySQL backend server.
 */
object UserSyncManager {
    private const val TAG = "UserSyncManager"

    /**
     * Synchronize the authenticated Firebase user profile with the backend server.
     * @param scope A CoroutineScope tied to the caller's lifecycle to prevent leaks.
     */
    fun syncUserToServer(
        scope: CoroutineScope,
        context: Context,
        user: FirebaseUser,
        provider: String = "password",
        customName: String? = null,
        onComplete: ((success: Boolean, message: String) -> Unit)? = null
    ) {
        scope.launch {
            try {
                val success = BackendApiManager.syncUser(
                    context = context,
                    user = user,
                    provider = provider,
                    customName = customName
                )
                withContext(Dispatchers.Main) {
                    if (success) {
                        onComplete?.invoke(true, "User synced successfully")
                    } else {
                        onComplete?.invoke(false, "Server sync failed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing user with backend", e)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(false, e.localizedMessage ?: "Sync error")
                }
            }
        }
    }
}
