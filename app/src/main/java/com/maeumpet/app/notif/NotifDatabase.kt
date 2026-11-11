package com.maeumpet.app.notif

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 알림 한 건. 원문은 보관 기간이 지나면 지운다. */
@Entity(tableName = "notifs", indices = [Index(value = ["key", "postedAt"], unique = true), Index("postedAt")])
data class NotifItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val packageName: String,
    val appLabel: String,
    val postedAt: Long,
    val category: String?,
    val title: String?,
    val text: String?,
    /** 대화형 알림이면 보낸 사람 */
    val sender: String?,
    val conversationTitle: String?,
    val isGroupConversation: Boolean,
    /** MessageCodec versioned records (also accepts legacy tab-delimited records). */
    val messages: String?,
    /** MESSAGE EMAIL MONEY DELIVERY CALENDAR PROMO OTHER */
    val kind: String,
    val amount: Long?,
    val isQuestion: Boolean,
    val importance: Int,
    val removedAt: Long? = null,
    /** NotificationListenerService.REASON_* */
    val removeReason: Int? = null,
    /** 중요도 학습용 특징 벡터 (ImportanceLearner.encode) */
    val features: String? = null,
    /** Kev: 카톡 외 알림이 광고·참여 유도일 확률. 돌리지 않았으면 null */
    val kevPromo: Float? = null,
    /** Kev: 카톡 외 알림을 오늘 안에 봐야 할 확률. 돌리지 않았으면 null */
    val kevTimely: Float? = null,
    /** 펫이 알림창에서 치운 시각 (AutoClear). 치우지 않았으면 null */
    val clearedAt: Long? = null,
    /** 펫이 알림을 미뤘으면(snooze) 다시 나타나는 시각 (AutoClear.shouldDefer). 미루지 않았으면 null */
    val deferredUntil: Long? = null,
) {
    val senderKey: String get() = "$packageName|${sender ?: conversationTitle ?: title ?: ""}"
    val clicked: Boolean get() = removeReason == 1
    /** 사용자가 밀어서 지움: 하나(REASON_CANCEL), 모두 지우기(CANCEL_ALL), 묶음째 지우기(GROUP_SUMMARY_CANCELED) */
    val dismissed: Boolean get() = removeReason == 2 || removeReason == 3 || removeReason == 12
    /** 앱이 스스로 알림을 지움(REASON_APP_CANCEL, _ALL). 메신저는 앱에서 읽었을 때 이렇게 지운다. 교체와 구분은 PetBriefing.handledTimes */
    val readInApp: Boolean get() = removeReason == 8 || removeReason == 9
}

/** 보낸 사람(앱|이름)별로 얼마나 자주 오고, 내가 얼마나 열어 보는지. 펫이 밤에 다시 계산한다. */
@Entity(tableName = "sender_stats")
data class SenderStat(
    @PrimaryKey @ColumnInfo(name = "sender_key") val key: String,
    val count: Int,
    val clicks: Int,
    val dismisses: Int,
    val lastAt: Long,
) {
    val clickRate: Float get() = if (clicks + dismisses == 0) 0.5f else clicks.toFloat() / (clicks + dismisses)
}

@Entity(tableName = "briefings")
data class Briefing(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val fromTs: Long,
    val toTs: Long,
    val total: Int,
    val text: String,
    val delivered: Boolean = false,
)

/** 약속 후보와 확정된 약속 */
@Entity(tableName = "plans")
data class Plan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    /** 누구와 (보낸 사람, 방 이름, 또는 "나") */
    val who: String,
    val text: String,
    val why: String,
    /** epoch millis, 모르면 null */
    val at: Long?,
    val hasExplicitTime: Boolean,
    /** PROPOSE CHANGE CANCEL */
    val type: String,
    /** CANDIDATE(펫이 물어보는 중) CONFIRMED DENIED DONE */
    val status: String = "CANDIDATE",
    val source: String,
)

@Dao
interface PlanDao {
    @Insert
    suspend fun insert(p: Plan): Long

    @Query("SELECT * FROM plans WHERE status = 'CANDIDATE' ORDER BY createdAt DESC")
    suspend fun candidates(): List<Plan>

    @Query("SELECT * FROM plans WHERE status = 'CANDIDATE' ORDER BY createdAt DESC LIMIT 10")
    fun observeCandidates(): Flow<List<Plan>>

    @Query("SELECT * FROM plans WHERE status = 'CONFIRMED' AND at IS NOT NULL AND at >= :from AND at < :to ORDER BY at")
    suspend fun confirmedBetween(from: Long, to: Long): List<Plan>

    @Query("SELECT * FROM plans WHERE status = 'CONFIRMED' AND at >= :from AND at < :to ORDER BY at")
    fun observeDay(from: Long, to: Long): Flow<List<Plan>>

    @Query("SELECT * FROM plans WHERE status = 'CONFIRMED' AND at >= :from ORDER BY at LIMIT 5")
    fun observeUpcoming(from: Long): Flow<List<Plan>>

    @Query("SELECT COUNT(*) FROM plans WHERE text = :text AND createdAt > :since")
    suspend fun countSame(text: String, since: Long): Int

    @Query("UPDATE plans SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun get(id: Long): Plan?
}

@Dao
interface NotifDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: NotifItem): Long

    @Query("SELECT * FROM notifs WHERE postedAt > :since AND postedAt <= :until ORDER BY postedAt DESC")
    suspend fun between(since: Long, until: Long): List<NotifItem>

    @Query("SELECT * FROM notifs ORDER BY postedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<NotifItem>>

    @Query("SELECT * FROM notifs WHERE postedAt >= :from AND postedAt < :to ORDER BY postedAt DESC")
    fun observeDay(from: Long, to: Long): Flow<List<NotifItem>>

    @Query("SELECT COUNT(*) FROM notifs WHERE postedAt > :since")
    suspend fun countSince(since: Long): Int

    /** 모델이 바뀐 뒤 다시 판단한 결과로 덮어쓴다 (KevRescore) */
    @Query("UPDATE notifs SET kind = :kind, importance = :importance, features = :features, isQuestion = :isQuestion, kevPromo = :promo, kevTimely = :timely WHERE id = :id")
    suspend fun rescore(id: Long, kind: String, importance: Int, features: String, isQuestion: Boolean, promo: Float?, timely: Float?)

    @Query("UPDATE notifs SET clearedAt = :at WHERE id = :id")
    suspend fun setCleared(id: Long, at: Long)

    @Query("UPDATE notifs SET deferredUntil = :until WHERE id = :id")
    suspend fun setDeferred(id: Long, until: Long)

    /** 펫이 치우거나 미룬 알림, 최근 것부터 */
    @Query("SELECT * FROM notifs WHERE (clearedAt IS NOT NULL OR deferredUntil IS NOT NULL) AND postedAt >= :since ORDER BY postedAt DESC")
    fun observeTidied(since: Long): Flow<List<NotifItem>>

    /**
     * 펫이 미뤄 둔(snooze, REASON_SNOOZED 18) 알림이 같은 내용으로 다시 올라오면, 새로 저장하지 않고 원래 기록을 알림창에 있는 상태로 되돌린다.
     * 되돌린 행 수(0 또는 1)를 돌려준다.
     */
    @Query("""UPDATE notifs SET removedAt = NULL, removeReason = NULL WHERE id = (SELECT id FROM notifs WHERE `key` = :key
        AND deferredUntil IS NOT NULL AND removeReason = 18 AND IFNULL(title, '') = IFNULL(:title, '') AND IFNULL(text, '') = IFNULL(:text, '')
        ORDER BY postedAt DESC LIMIT 1)""")
    suspend fun reactivateDeferred(key: String, title: String?, text: String?): Int

    /** 이미 되살린 미룬 알림이 다시 한 번 올라온 것인가 (시스템이 돌려줄 때 두 번 게시한다, 실측) */
    @Query("""SELECT COUNT(*) FROM notifs WHERE `key` = :key AND deferredUntil IS NOT NULL AND deferredUntil <= :now AND removedAt IS NULL
        AND IFNULL(title, '') = IFNULL(:title, '') AND IFNULL(text, '') = IFNULL(:text, '')""")
    suspend fun countReturnedActive(key: String, now: Long, title: String?, text: String?): Int

    @Query("DELETE FROM notifs WHERE text LIKE '%민감한 알림 콘텐츠 숨김%'")
    suspend fun deleteRedacted(): Int

    @Query("UPDATE notifs SET removedAt = :at, removeReason = :reason WHERE `key` = :key AND removedAt IS NULL")
    suspend fun markRemoved(key: String, at: Long, reason: Int): Int

    @Query("SELECT * FROM notifs WHERE `key` = :key AND removedAt IS NULL ORDER BY postedAt DESC LIMIT 1")
    suspend fun latestActive(key: String): NotifItem?

    @Query("DELETE FROM notifs WHERE postedAt < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("SELECT * FROM notifs WHERE postedAt > :since")
    suspend fun since(since: Long): List<NotifItem>

    /** 열었거나(1) 지웠거나(2,3, 묶음째 12) 라벨이 생긴 알림 */
    @Query("SELECT * FROM notifs WHERE postedAt > :since AND removeReason IN (1, 2, 3, 12) AND features IS NOT NULL")
    suspend fun labeledSince(since: Long): List<NotifItem>
}

@Dao
interface SenderStatDao {
    @Query("SELECT * FROM sender_stats WHERE sender_key = :key")
    suspend fun get(key: String): SenderStat?

    @Upsert
    suspend fun upsert(stat: SenderStat)

    @Query("DELETE FROM sender_stats")
    suspend fun clear()
}

@Dao
interface BriefingDao {
    @Query("SELECT * FROM briefings WHERE id = :id")
    suspend fun get(id: Long): Briefing?

    @Insert
    suspend fun insert(b: Briefing): Long

    @Query("SELECT * FROM briefings ORDER BY createdAt DESC LIMIT 1")
    suspend fun latest(): Briefing?

    @Query("SELECT * FROM briefings ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(): Flow<Briefing?>

    @Query("UPDATE briefings SET delivered = 1 WHERE id = :id")
    suspend fun markDelivered(id: Long)
}

@Database(entities = [NotifItem::class, SenderStat::class, Briefing::class, Plan::class, Expense::class, TodoItem::class], version = 7, exportSchema = false)
abstract class NotifDatabase : RoomDatabase() {
    abstract fun notifs(): NotifDao
    abstract fun senderStats(): SenderStatDao
    abstract fun briefings(): BriefingDao
    abstract fun plans(): PlanDao
    abstract fun expenses(): ExpenseDao
    abstract fun todos(): TodoDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifs ADD COLUMN deferredUntil INTEGER")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifs ADD COLUMN clearedAt INTEGER")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notifs ADD COLUMN kevPromo REAL")
                db.execSQL("ALTER TABLE notifs ADD COLUMN kevTimely REAL")
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS todos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceKey TEXT NOT NULL, createdAt INTEGER NOT NULL, title TEXT NOT NULL, sourceLabel TEXT NOT NULL, sourceText TEXT NOT NULL, dueDay INTEGER, status TEXT NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_todos_sourceKey ON todos (sourceKey)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, fingerprint TEXT NOT NULL, postedAt INTEGER NOT NULL, merchant TEXT NOT NULL, amount INTEGER NOT NULL, refund INTEGER NOT NULL, appLabel TEXT NOT NULL, deleted INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_expenses_fingerprint ON expenses (fingerprint)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_postedAt ON expenses (postedAt)")
            }
        }
        @Volatile
        private var instance: NotifDatabase? = null

        fun get(context: Context): NotifDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, NotifDatabase::class.java, "maeumpet_notif.db")
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .build()
                    .also { instance = it }
            }
    }
}
