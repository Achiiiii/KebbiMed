package tw.kebbi.medreminder.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** 一包藥（occurrence）和它在凱比上的狀態。 */
@Entity(tableName = "occurrence")
data class OccurrenceEntity(
    @PrimaryKey val id: String,
    val date: String,
    val slot: String,
    val slotLabel: String,
    val scheduledAt: Long,
    val lateDeadline: Long,
    /** List<OccItem> 的 JSON。 */
    val itemsJson: String,
    val status: String,
    val remindedAt: Long?,
    val secondReminder: Boolean,
    val snoozeUntil: Long?,
    val takenAt: Long?,
    val late: Boolean,
    val overdueSeen: Boolean,
)

/**
 * 要送給後端的東西（服藥事件、申請、照片…）。先存這裡，再送；送不出去就留著等有網路（第 2.6 節）。
 * - `kind`：見 [OutboxKind]。
 * - `key`：eventId 或 requestId（去重用）。
 * - `payload`：要送的 JSON。照片的檔案路徑放在 `filePath`。
 */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val key: String,
    val payload: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val filePath: String? = null,
)

object OutboxKind {
    const val DOSE_EVENT = "dose_event"
    const val APP_EVENT = "app_event"
    const val CHANGE_REQUEST = "change_request"
    const val UPLOAD = "upload"
    const val SCHEDULE_ACK = "schedule_ack"
}

@Dao
interface OccurrenceDao {
    @Query("SELECT * FROM occurrence ORDER BY scheduledAt")
    fun all(): List<OccurrenceEntity>

    @Upsert
    fun upsert(items: List<OccurrenceEntity>)

    @Query("DELETE FROM occurrence WHERE id IN (:ids)")
    fun delete(ids: List<String>)

    @Query("DELETE FROM occurrence")
    fun clear()
}

@Dao
interface OutboxDao {
    @Insert
    fun insert(item: OutboxEntity): Long

    @Query("SELECT * FROM outbox WHERE kind = :kind ORDER BY id LIMIT :limit")
    fun next(kind: String, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE kind = :kind AND `key` = :key LIMIT 1")
    fun find(kind: String, key: String): OutboxEntity?

    @Query("DELETE FROM outbox WHERE id IN (:ids)")
    fun delete(ids: List<Long>)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id IN (:ids)")
    fun bumpAttempts(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM outbox")
    fun count(): Int

    @Query("DELETE FROM outbox")
    fun clear()
}

@Database(entities = [OccurrenceEntity::class, OutboxEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun occurrences(): OccurrenceDao
    abstract fun outbox(): OutboxDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** 資料量很小（8 天 × 最多 8 包），允許在主執行緒存取，讓鬧鐘、通知按鈕的處理簡單一點。 */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "kebbi_med.db")
                .allowMainThreadQueries()
                .build().also { instance = it }
        }
    }
}
