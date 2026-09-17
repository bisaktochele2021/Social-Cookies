package dev.daymond.socialcookies

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * BackendApiManager manages secure communication between the Android application
 * and the custom PHP/MySQL backend server using Retrofit.
 */
object BackendApiManager {

    private const val TAG = "BackendApiManager"

    const val PREF_KEY_BACKEND_URL = "BACKEND_SERVER_API_URL"
    const val PREF_KEY_SCRIPT_PREFIX = "CACHED_SCRIPT_"

    private var retrofit: Retrofit? = null
    private var apiService: BackendApiService? = null

    fun getApiUrl(context: Context): String {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val saved = prefs.getString(PREF_KEY_BACKEND_URL, "")?.trim() ?: ""
        return if (saved.isNotEmpty()) saved else "https://app.devdaymond.com/api.php"
    }

    fun setApiUrl(context: Context, url: String) {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString(PREF_KEY_BACKEND_URL, url.trim()).apply()
    }

    private fun getApiKey(): String {
        return BuildConfig.BACKEND_API_KEY
    }

    fun isConfigured(context: Context): Boolean {
        val url = getApiUrl(context)
        val apiKey = getApiKey()
        return url.isNotEmpty() && !url.contains("example.com") && apiKey.isNotEmpty()
    }

    private fun getApiService(): BackendApiService {
        if (apiService == null) {
            val userAgent = System.getProperty("http.agent") ?: "Dalvik/2.1.0 (Linux; U; Android 14)"
            
            val authInterceptor = Interceptor { chain ->
                val originalRequest = chain.request()
                // Safely add api_key query param for both GET and POST requests
                val url = originalRequest.url().newBuilder()
                    .addQueryParameter("api_key", getApiKey())
                    .build()
                
                val newRequest = originalRequest.newBuilder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .header("X-API-KEY", getApiKey())
                    .build()
                chain.proceed(newRequest)
            }

            val client = OkHttpClient.Builder()
                .addInterceptor(authInterceptor)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl("https://app.devdaymond.com/") 
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                
            apiService = retrofit!!.create(BackendApiService::class.java)
        }
        return apiService!!
    }

    suspend fun syncUser(
        context: Context,
        user: FirebaseUser,
        provider: String = "password",
        customName: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext false

        try {
            val displayName = customName?.takeIf { it.isNotBlank() }
                ?: user.displayName?.takeIf { it.isNotBlank() }
                ?: user.email?.substringBefore("@") 
                ?: "User"

            val request = SyncUserRequest(
                firebase_uid = user.uid,
                email = user.email ?: "",
                name = displayName,
                auth_provider = provider,
                device_model = "${Build.MANUFACTURER} ${Build.MODEL}"
            )

            val response = getApiService().syncUser(getApiUrl(context), request)
            val success = response.isSuccessful && response.body()?.get("status")?.asString == "success"
            if (success) {
                Log.d(TAG, "Successfully synced user ${user.uid} with backend.")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing user to backend: ${e.message}", e)
            false
        }
    }

    suspend fun syncCookies(
        context: Context,
        records: List<CookieRecord>
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext Result.success(0)
        if (!isConfigured(context)) return@withContext Result.failure(Exception("Backend API is not configured."))

        val sharedPref = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val prefUid = sharedPref.getString("ACTIVE_USER_UID", "")
        val currentUser = FirebaseAuth.getInstance().currentUser
        val userUid = records.firstOrNull { it.userUid.isNotEmpty() }?.userUid
            ?: currentUser?.uid
            ?: (if (!prefUid.isNullOrEmpty()) prefUid else "anonymous_user")

        try {
            val items = records.map {
                CookieItem(
                    host = it.host,
                    account_uid = it.uid,
                    password = it.password,
                    cookies = it.cookies,
                    is_live = if (it.isLive) 1 else 0,
                    timestamp = it.timestamp
                )
            }

            val request = SyncCookiesRequest(
                firebase_uid = userUid,
                cookies_list = items
            )

            val response = getApiService().syncCookies(getApiUrl(context), request)
            if (response.isSuccessful && response.body()?.get("status")?.asString == "success") {
                val dataObj = response.body()?.getAsJsonObject("data")
                val count = dataObj?.get("synced_count")?.asInt ?: records.size
                Result.success(count)
            } else {
                val msg = response.body()?.get("message")?.asString ?: "Unknown backend error"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed syncing cookies to backend: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getCookies(
        context: Context,
        userUid: String
    ): Result<List<CookieRecord>> = withContext(Dispatchers.IO) {
        val effectiveUid = userUid.takeIf { it.isNotEmpty() }
            ?: FirebaseAuth.getInstance().currentUser?.uid
            ?: context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).getString("ACTIVE_USER_UID", "") 
            ?: ""

        if (effectiveUid.isEmpty()) return@withContext Result.success(emptyList())
        if (!isConfigured(context)) return@withContext Result.failure(Exception("Backend API is not configured."))

        try {
            val response = getApiService().getCookies(getApiUrl(context), userUid = effectiveUid)
            if (response.isSuccessful && response.body()?.get("status")?.asString == "success") {
                val dataObj = response.body()?.getAsJsonObject("data")
                val cookiesArr = dataObj?.getAsJsonArray("cookies")
                val list = mutableListOf<CookieRecord>()
                
                cookiesArr?.forEach { element ->
                    val item = element.asJsonObject
                    val record = CookieRecord(
                        userUid = effectiveUid,
                        host = item.get("host")?.asString ?: "instagram.com",
                        uid = item.get("account_uid")?.asString ?: "",
                        cookies = item.get("cookies")?.asString ?: "",
                        password = item.get("password")?.asString ?: "",
                        timestamp = item.get("client_timestamp")?.asLong ?: System.currentTimeMillis(),
                        isSynced = true,
                        isLive = item.get("is_live")?.asInt == 1
                    )
                    list.add(record)
                }
                Result.success(list)
            } else {
                val msg = response.body()?.get("message")?.asString ?: "Failed to retrieve cookies"
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed fetching cookies from backend: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun deleteCookie(
        context: Context,
        userUid: String,
        accountUid: String,
        host: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (userUid.isEmpty() || accountUid.isEmpty()) return@withContext false
        if (!isConfigured(context)) return@withContext false

        try {
            val request = DeleteCookieRequest(
                firebase_uid = userUid,
                account_uid = accountUid,
                host = host
            )
            val response = getApiService().deleteCookie(getApiUrl(context), request)
            response.isSuccessful && response.body()?.get("status")?.asString == "success"
        } catch (e: Exception) {
            Log.e(TAG, "Failed deleting cookie from backend: ${e.message}", e)
            false
        }
    }

    suspend fun clearCookies(
        context: Context,
        userUid: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (userUid.isEmpty()) return@withContext false
        if (!isConfigured(context)) return@withContext false

        try {
            val request = ClearCookiesRequest(firebase_uid = userUid)
            val response = getApiService().clearCookies(getApiUrl(context), request)
            response.isSuccessful && response.body()?.get("status")?.asString == "success"
        } catch (e: Exception) {
            Log.e(TAG, "Failed clearing cookies from backend: ${e.message}", e)
            false
        }
    }

    suspend fun fetchRemoteScript(
        context: Context,
        scriptName: String = "autoworker_js",
        platform: String = "all"
    ): String? = withContext(Dispatchers.IO) {
        if (!isConfigured(context)) return@withContext null

        try {
            val response = getApiService().getScript(getApiUrl(context), scriptName = scriptName, platform = platform)
            if (response.isSuccessful && response.body()?.get("status")?.asString == "success") {
                val dataObj = response.body()?.getAsJsonObject("data")
                val hasCustom = dataObj?.get("has_custom")?.asBoolean ?: false
                if (hasCustom) {
                    val code = dataObj?.get("code")?.asString ?: ""
                    if (code.isNotBlank()) {
                        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("${PREF_KEY_SCRIPT_PREFIX}${scriptName}_${platform}", code)
                            .putLong("${PREF_KEY_SCRIPT_PREFIX}${scriptName}_${platform}_time", System.currentTimeMillis())
                            .apply()
                        return@withContext code
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed fetching remote script: ${e.message}", e)
        }
        return@withContext null
    }

    fun getCachedScript(
        context: Context,
        scriptName: String = "autoworker_js",
        platform: String = "all"
    ): String? {
        val prefs = context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val platformSpecific = prefs.getString("${PREF_KEY_SCRIPT_PREFIX}${scriptName}_${platform}", null)
        if (!platformSpecific.isNullOrBlank()) return platformSpecific
        
        val allPlatform = prefs.getString("${PREF_KEY_SCRIPT_PREFIX}${scriptName}_all", null)
        if (!allPlatform.isNullOrBlank()) return allPlatform
        
        return null
    }

    suspend fun saveRemoteScript(
        context: Context,
        scriptName: String = "autoworker_js",
        code: String,
        platform: String = "all",
        isActive: Boolean = true
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured(context) || code.isBlank()) return@withContext false

        try {
            val request = SaveScriptRequest(
                script_name = scriptName,
                platform = platform,
                code = code,
                is_active = if (isActive) 1 else 0
            )
            val response = getApiService().saveScript(getApiUrl(context), request)
            response.isSuccessful && response.body()?.get("status")?.asString == "success"
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving remote script: ${e.message}", e)
            false
        }
    }
}
