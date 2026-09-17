package dev.daymond.socialcookies

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AppNoticeManager {

    private const val TAG = "AppNoticeManager"
    private const val PREFS_NAME = "AppNoticePrefs"
    private const val KEY_DISMISSED_NOTICE_ID = "LAST_DISMISSED_NOTICE_ID"
    private const val RTDB_URL = "https://social-cookies-default-rtdb.asia-southeast1.firebasedatabase.app"

    /**
     * Entry point: checks for app updates first, and if none, checks for notices.
     */
    fun checkUpdatesAndNotices(activity: AppCompatActivity) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Attempt fetching via Firebase SDK or REST fallback within 7 seconds timeout
                val (updateInfo, noticeInfo) = withTimeoutOrNull(7000L) {
                    fetchConfigFromFirebase()
                } ?: fetchConfigFromRest()

                withContext(Dispatchers.Main) {
                    if (activity.isFinishing || activity.isDestroyed) return@withContext

                    val currentVersionCode = getAppVersionCode(activity)
                    Log.d(TAG, "Current Version: $currentVersionCode, Latest: ${updateInfo?.latestVersionCode}")

                    val hasUpdate = updateInfo != null && updateInfo.latestVersionCode > currentVersionCode
                    if (hasUpdate) {
                        showUpdateDialog(activity, updateInfo!!, noticeInfo)
                    } else if (noticeInfo != null && noticeInfo.enabled) {
                        checkAndShowNotice(activity, noticeInfo)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking updates and notices: ${e.message}", e)
            }
        }
    }

    private suspend fun fetchConfigFromFirebase(): Pair<AppUpdateInfo?, NoticeInfo?> = withContext(Dispatchers.IO) {
        var update: AppUpdateInfo? = null
        var notice: NoticeInfo? = null

        try {
            val db = FirebaseDatabase.getInstance(RTDB_URL)

            // Fetch app_update
            val updateRef = db.getReference("app_update")
            val updateSnapshot = kotlinx.coroutines.suspendCancellableCoroutine<DataSnapshot?> { cont ->
                updateRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (cont.isActive) cont.resumeWith(Result.success(snapshot))
                    }
                    override fun onCancelled(error: DatabaseError) {
                        Log.w(TAG, "Firebase update check cancelled: ${error.message}")
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                })
            }

            if (updateSnapshot != null && updateSnapshot.exists()) {
                update = updateSnapshot.getValue(AppUpdateInfo::class.java)
            }

            // Fetch notice
            val noticeRef = db.getReference("notice")
            val noticeSnapshot = kotlinx.coroutines.suspendCancellableCoroutine<DataSnapshot?> { cont ->
                noticeRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (cont.isActive) cont.resumeWith(Result.success(snapshot))
                    }
                    override fun onCancelled(error: DatabaseError) {
                        Log.w(TAG, "Firebase notice check cancelled: ${error.message}")
                        if (cont.isActive) cont.resumeWith(Result.success(null))
                    }
                })
            }

            if (noticeSnapshot != null && noticeSnapshot.exists()) {
                notice = noticeSnapshot.getValue(NoticeInfo::class.java)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Firebase SDK fetch encountered issue: ${e.message}, will try REST fallback")
        }

        Pair(update, notice)
    }

    /**
     * REST API fallback in case Google Play Services or Firebase SDK connectivity is restricted.
     */
    private fun fetchConfigFromRest(): Pair<AppUpdateInfo?, NoticeInfo?> {
        var update: AppUpdateInfo? = null
        var notice: NoticeInfo? = null

        try {
            val url = URL("$RTDB_URL/.json")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val responseText = reader.use { it.readText() }
                val rootJson = JSONObject(responseText)

                if (rootJson.has("app_update")) {
                    val uObj = rootJson.getJSONObject("app_update")
                    update = AppUpdateInfo(
                        latestVersionCode = uObj.optLong("latestVersionCode", 0L),
                        latestVersionName = uObj.optString("latestVersionName", ""),
                        minVersionCode = uObj.optLong("minVersionCode", 0L),
                        isForceUpdate = uObj.optBoolean("isForceUpdate", false),
                        updateTitle = uObj.optString("updateTitle", "New Update Available!"),
                        releaseNotes = uObj.optString("releaseNotes", ""),
                        downloadUrl = uObj.optString("downloadUrl", "")
                    )
                }

                if (rootJson.has("notice")) {
                    val nObj = rootJson.getJSONObject("notice")
                    notice = NoticeInfo(
                        enabled = nObj.optBoolean("enabled", false),
                        id = nObj.optString("id", ""),
                        title = nObj.optString("title", "Notice"),
                        message = nObj.optString("message", ""),
                        buttonText = nObj.optString("buttonText", "Got It"),
                        buttonUrl = nObj.optString("buttonUrl", ""),
                        canDismiss = nObj.optBoolean("canDismiss", true),
                        showOnce = nObj.optBoolean("showOnce", true)
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "REST fallback failed: ${e.message}")
        }

        return Pair(update, notice)
    }

    /**
     * Displays the App Update Dialog.
     */
    private fun showUpdateDialog(
        activity: AppCompatActivity,
        update: AppUpdateInfo,
        pendingNotice: NoticeInfo?
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_app_update, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val currentVersionCode = getAppVersionCode(activity)
        val isForce = update.isForceUpdate || (update.minVersionCode > 0 && currentVersionCode < update.minVersionCode)

        val tvTitle = view.findViewById<TextView>(R.id.tv_update_title)
        val tvVersionBadge = view.findViewById<TextView>(R.id.tv_version_badge)
        val tvNotes = view.findViewById<TextView>(R.id.tv_release_notes)
        val tvWarning = view.findViewById<TextView>(R.id.tv_force_update_warning)
        val btnUpdate = view.findViewById<MaterialButton>(R.id.btn_update_now)
        val btnLater = view.findViewById<MaterialButton>(R.id.btn_update_later)

        tvTitle.text = update.updateTitle.ifEmpty { "New Update Available!" }
        tvVersionBadge.text = if (update.latestVersionName.isNotEmpty()) "v${update.latestVersionName}" else "v${update.latestVersionCode}"
        tvNotes.text = update.releaseNotes.ifEmpty { "Performance improvements and bug fixes." }

        if (isForce) {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            tvWarning.visibility = View.VISIBLE
            btnLater.visibility = View.GONE
        } else {
            dialog.setCancelable(true)
            tvWarning.visibility = View.GONE
            btnLater.visibility = View.VISIBLE
            btnLater.setOnClickListener {
                dialog.dismiss()
                if (pendingNotice != null && pendingNotice.enabled) {
                    checkAndShowNotice(activity, pendingNotice)
                }
            }
        }

        btnUpdate.setOnClickListener {
            if (update.downloadUrl.isNotEmpty()) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(update.downloadUrl))
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(activity, "Could not open update link: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(activity, "No download link specified", Toast.LENGTH_SHORT).show()
            }
            if (!isForce) {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    /**
     * Checks if notice has already been dismissed when showOnce is active, and shows it if eligible.
     */
    private fun checkAndShowNotice(activity: AppCompatActivity, notice: NoticeInfo) {
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dismissedId = prefs.getString(KEY_DISMISSED_NOTICE_ID, "")

        if (notice.showOnce && notice.id.isNotEmpty() && notice.id == dismissedId) {
            Log.d(TAG, "Notice '${notice.id}' already dismissed, skipping.")
            return
        }

        showNoticeDialog(activity, notice)
    }

    /**
     * Displays the In-App Notice Dialog.
     */
    private fun showNoticeDialog(activity: AppCompatActivity, notice: NoticeInfo) {
        if (activity.isFinishing || activity.isDestroyed) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_notice, null)
        val dialog = AlertDialog.Builder(activity)
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val tvTitle = view.findViewById<TextView>(R.id.tv_notice_title)
        val tvMessage = view.findViewById<TextView>(R.id.tv_notice_message)
        val btnClose = view.findViewById<ImageButton>(R.id.btn_close_notice)
        val btnAction = view.findViewById<MaterialButton>(R.id.btn_notice_action)

        tvTitle.text = notice.title.ifEmpty { "Notice" }
        tvMessage.text = notice.message
        btnAction.text = notice.buttonText.ifEmpty { "Got It" }

        val markNoticeSeen = {
            if (notice.showOnce && notice.id.isNotEmpty()) {
                activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DISMISSED_NOTICE_ID, notice.id)
                    .apply()
            }
        }

        if (!notice.canDismiss) {
            dialog.setCancelable(false)
            dialog.setCanceledOnTouchOutside(false)
            btnClose.visibility = View.GONE
        } else {
            dialog.setCancelable(true)
            btnClose.visibility = View.VISIBLE
            btnClose.setOnClickListener {
                markNoticeSeen()
                dialog.dismiss()
            }
            dialog.setOnCancelListener {
                markNoticeSeen()
            }
        }

        btnAction.setOnClickListener {
            markNoticeSeen()
            dialog.dismiss()

            if (notice.buttonUrl.isNotEmpty()) {
                handleNoticeActionUrl(activity, notice.buttonUrl)
            }
        }

        dialog.show()
    }

    private fun handleNoticeActionUrl(activity: AppCompatActivity, targetUrl: String) {
        try {
            when {
                targetUrl.equals("app://settings", ignoreCase = true) -> {
                    activity.startActivity(Intent(activity, SettingsActivity::class.java))
                }
                targetUrl.equals("app://sheet", ignoreCase = true) -> {
                    activity.startActivity(Intent(activity, SheetActivity::class.java))
                }
                targetUrl.startsWith("http://", ignoreCase = true) || targetUrl.startsWith("https://", ignoreCase = true) -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                    activity.startActivity(intent)
                }
                else -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                    activity.startActivity(intent)
                }
            }
        } catch (e: Exception) {
            Toast.makeText(activity, "Unable to handle link: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun getAppVersionCode(context: Context): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
        } catch (e: Exception) {
            0L
        }
    }
}
