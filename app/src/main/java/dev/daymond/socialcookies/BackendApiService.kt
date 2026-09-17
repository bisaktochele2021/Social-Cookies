package dev.daymond.socialcookies

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import retrofit2.http.Url

data class SyncUserRequest(
    val action: String = "sync_user",
    val firebase_uid: String,
    val email: String,
    val name: String,
    val auth_provider: String,
    val device_model: String
)

data class SyncCookiesRequest(
    val action: String = "sync_cookies",
    val firebase_uid: String,
    val cookies_list: List<CookieItem>
)

data class CookieItem(
    val host: String,
    val account_uid: String,
    val password: String,
    val cookies: String,
    val is_live: Int,
    val timestamp: Long
)

data class DeleteCookieRequest(
    val action: String = "delete_cookie",
    val firebase_uid: String,
    val account_uid: String,
    val host: String
)

data class ClearCookiesRequest(
    val action: String = "clear_cookies",
    val firebase_uid: String
)

data class SaveScriptRequest(
    val action: String = "save_script",
    val script_name: String,
    val platform: String,
    val code: String,
    val is_active: Int
)

interface BackendApiService {

    @POST
    suspend fun syncUser(
        @Url url: String,
        @Body request: SyncUserRequest
    ): Response<com.google.gson.JsonObject>

    @POST
    suspend fun syncCookies(
        @Url url: String,
        @Body request: SyncCookiesRequest
    ): Response<com.google.gson.JsonObject>

    @GET
    suspend fun getCookies(
        @Url url: String,
        @Query("action") action: String = "get_cookies",
        @Query("user_uid") userUid: String
    ): Response<com.google.gson.JsonObject>

    @POST
    suspend fun deleteCookie(
        @Url url: String,
        @Body request: DeleteCookieRequest
    ): Response<com.google.gson.JsonObject>

    @POST
    suspend fun clearCookies(
        @Url url: String,
        @Body request: ClearCookiesRequest
    ): Response<com.google.gson.JsonObject>

    @GET
    suspend fun getScript(
        @Url url: String,
        @Query("action") action: String = "get_script",
        @Query("script_name") scriptName: String,
        @Query("platform") platform: String
    ): Response<com.google.gson.JsonObject>

    @POST
    suspend fun saveScript(
        @Url url: String,
        @Body request: SaveScriptRequest
    ): Response<com.google.gson.JsonObject>
}
