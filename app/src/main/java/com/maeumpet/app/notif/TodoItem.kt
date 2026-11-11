package com.maeumpet.app.notif

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "todos", indices = [Index(value = ["sourceKey"], unique = true)])
data class TodoItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceKey: String,
    val createdAt: Long,
    val title: String,
    val sourceLabel: String,
    val sourceText: String,
    /** LocalDate.toEpochDay(), or null when no date is known. */
    val dueDay: Long?,
    /** CANDIDATE / OPEN / DONE / DISMISSED */
    val status: String = "CANDIDATE",
)

@Dao
interface TodoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: TodoItem): Long

    @Query("SELECT * FROM todos WHERE status != 'DISMISSED' ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TodoItem>>

    @Query("UPDATE todos SET title = :title, dueDay = :dueDay, status = CASE WHEN status = 'CANDIDATE' THEN 'OPEN' ELSE status END WHERE id = :id AND status != 'DISMISSED'")
    suspend fun save(id: Long, title: String, dueDay: Long?)

    @Query("UPDATE todos SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)

    @Query("UPDATE todos SET title = '', sourceText = '', sourceLabel = '', status = 'DISMISSED' WHERE id = :id")
    suspend fun dismiss(id: Long)
}
