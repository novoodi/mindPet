package com.maeumpet.app.notif

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Independent of notification retention; deleted entries remain tombstoned for backfill. */
@Entity(tableName = "expenses", indices = [Index(value = ["fingerprint"], unique = true), Index("postedAt")])
data class Expense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val postedAt: Long,
    val merchant: String,
    val amount: Long,
    val refund: Boolean,
    val appLabel: String,
    val deleted: Boolean = false,
)

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: Expense): Long

    @Query("SELECT * FROM expenses WHERE deleted = 0 AND postedAt >= :from AND postedAt < :until ORDER BY postedAt DESC, id DESC")
    fun observeMonth(from: Long, until: Long): Flow<List<Expense>>

    @Query("UPDATE expenses SET merchant = :merchant, amount = :amount WHERE id = :id")
    suspend fun edit(id: Long, merchant: String, amount: Long)

    @Query("UPDATE expenses SET deleted = 1, merchant = '', amount = 0, appLabel = '' WHERE id = :id")
    suspend fun delete(id: Long)
}
