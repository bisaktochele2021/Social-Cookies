package dev.daymond.socialcookies

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CookieDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: CookieRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(records: List<CookieRecord>)

    @Query("SELECT * FROM cookies ORDER BY timestamp DESC")
    fun getAllCookiesFlow(): Flow<List<CookieRecord>>

    @Query("SELECT * FROM cookies ORDER BY timestamp DESC")
    suspend fun getAllCookies(): List<CookieRecord>

    @Query("SELECT * FROM cookies WHERE userUid = :userUid ORDER BY timestamp DESC")
    fun getCookiesByUserFlow(userUid: String): Flow<List<CookieRecord>>

    @Query("SELECT * FROM cookies WHERE userUid = :userUid ORDER BY timestamp DESC")
    suspend fun getCookiesByUser(userUid: String): List<CookieRecord>

    @Query("SELECT * FROM cookies WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: String): CookieRecord?

    @Query("SELECT * FROM cookies WHERE userUid = :userUid AND uid = :uid LIMIT 1")
    suspend fun getByUserAndUid(userUid: String, uid: String): CookieRecord?

    @Query("SELECT * FROM cookies WHERE isSynced = 0")
    suspend fun getUnsyncedCookies(): List<CookieRecord>

    @Query("SELECT * FROM cookies WHERE userUid = :userUid AND isSynced = 0")
    suspend fun getUnsyncedCookiesByUser(userUid: String): List<CookieRecord>

    @Update
    suspend fun update(record: CookieRecord)

    @Delete
    suspend fun delete(record: CookieRecord)

    @Query("SELECT COUNT(*) FROM cookies WHERE timestamp >= :since")
    suspend fun getCountSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM cookies WHERE userUid = :userUid AND timestamp >= :since")
    suspend fun getCountSinceForUser(userUid: String, since: Long): Int

    @Query("DELETE FROM cookies")
    suspend fun deleteAll()

    @Query("DELETE FROM cookies WHERE userUid = :userUid")
    suspend fun deleteAllForUser(userUid: String)

    @Query("DELETE FROM cookies WHERE userUid = :userUid AND uid = :uid AND host = :host")
    suspend fun deleteByUserAndUidAndHost(userUid: String, uid: String, host: String)

    @Query("UPDATE cookies SET userUid = :userUid WHERE userUid = ''")
    suspend fun claimOrphanCookies(userUid: String): Int
}
