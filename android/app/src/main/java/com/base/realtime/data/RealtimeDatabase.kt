package com.base.realtime.data

import android.content.Context
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Entity(
    tableName = "outbox",
    indices = [Index(value = ["dedupeKey"], unique = true)],
)
data class OutboxEntity(
    @PrimaryKey val id: String,
    val dedupeKey: String?,
    val body: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val createdAt: Long,
)

@Entity(tableName = "inbox")
data class InboxEntity(
    @PrimaryKey val id: String,
    val type: String,
    val body: String,
    val createdAt: Long,
)

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox WHERE nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: OutboxEntity)

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM outbox WHERE dedupeKey = :key")
    suspend fun deleteByKey(key: String)

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun count(): Int

    @Query("UPDATE outbox SET attempts = :attempts, nextAttemptAt = :nextAt WHERE id = :id")
    suspend fun schedule(id: String, attempts: Int, nextAt: Long)
}

@Dao
interface InboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: InboxEntity): Long

    @Query("SELECT COUNT(*) FROM inbox")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM inbox WHERE id = :id")
    suspend fun countId(id: String): Int

    @Query("SELECT * FROM inbox ORDER BY createdAt ASC LIMIT 100")
    suspend fun pending(): List<InboxEntity>

    @Query("DELETE FROM inbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM inbox WHERE id = (SELECT id FROM inbox ORDER BY createdAt ASC LIMIT 1)")
    suspend fun deleteOldest()
}

@Database(
    entities = [OutboxEntity::class, InboxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RealtimeDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao
    abstract fun inboxDao(): InboxDao

    companion object {
        @Volatile private var instance: RealtimeDatabase? = null

        fun get(context: Context): RealtimeDatabase {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RealtimeDatabase::class.java,
                    "realtime.db",
                )
                    .setDriver(AndroidSQLiteDriver())
                    .setQueryCoroutineContext(Dispatchers.IO)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
