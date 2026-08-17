package com.fabrice.plansms.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledMessageDao {
    @Query("SELECT * FROM scheduled_messages ORDER BY targetDate ASC")
    fun observeAll(): Flow<List<ScheduledMessage>>

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun getById(id: Long): ScheduledMessage?

    @Query("SELECT * FROM scheduled_messages WHERE status = 'SCHEDULED'")
    suspend fun getScheduled(): List<ScheduledMessage>

    @Insert
    suspend fun insert(msg: ScheduledMessage): Long

    @Update
    suspend fun update(msg: ScheduledMessage)

    @Delete
    suspend fun delete(msg: ScheduledMessage)

    @Query("DELETE FROM scheduled_messages WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface TemplateDao {
    @Query("SELECT * FROM templates ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Template>>

    @Insert
    suspend fun insert(t: Template): Long

    @Update
    suspend fun update(t: Template)

    @Delete
    suspend fun delete(t: Template)
}

@Dao
interface SendLogDao {
    @Query("SELECT * FROM send_logs ORDER BY sentAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<SendLog>>

    @Insert
    suspend fun insert(log: SendLog): Long

    @Query("DELETE FROM send_logs")
    suspend fun clear()
}
