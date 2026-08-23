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

@Dao
interface ContactGroupDao {
    @Query("SELECT * FROM contact_groups ORDER BY name ASC")
    fun observeAll(): Flow<List<ContactGroup>>

    @Insert
    suspend fun insert(g: ContactGroup): Long

    @Query("DELETE FROM contact_groups WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface GroupMemberDao {
    @Query("SELECT * FROM group_members WHERE groupId = :groupId ORDER BY name ASC")
    fun observeMembers(groupId: Long): Flow<List<GroupMember>>

    @Query("SELECT * FROM group_members WHERE groupId = :groupId")
    suspend fun getMembers(groupId: Long): List<GroupMember>

    @Insert(onConflict = androidx.room.OnConflictStrategy.IGNORE)
    suspend fun insertAll(members: List<GroupMember>)

    @Query("DELETE FROM group_members WHERE groupId = :groupId AND phone = :phone")
    suspend fun delete(groupId: Long, phone: String)
}

@Dao
interface VoiceRecordingDao {
    @Query("SELECT * FROM voice_recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<VoiceRecording>>

    @Insert
    suspend fun insert(r: VoiceRecording): Long

    @Update
    suspend fun update(r: VoiceRecording)

    @Delete
    suspend fun delete(r: VoiceRecording)
}

@Dao
interface AutoReplyRuleDao {
    @Query("SELECT * FROM auto_reply_rules WHERE id = 1")
    fun observe(): Flow<AutoReplyRule?>

    @Query("SELECT * FROM auto_reply_rules WHERE id = 1")
    suspend fun get(): AutoReplyRule?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: AutoReplyRule)
}
