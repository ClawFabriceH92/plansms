package com.fabrice.plansms.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ScheduledMessage::class, Template::class, SendLog::class, ContactGroup::class, GroupMember::class, AutoReplyRule::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun scheduledMessageDao(): ScheduledMessageDao
    abstract fun templateDao(): TemplateDao
    abstract fun sendLogDao(): SendLogDao
    abstract fun contactGroupDao(): ContactGroupDao
    abstract fun groupMemberDao(): GroupMemberDao
    abstract fun autoReplyRuleDao(): AutoReplyRuleDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "plansms.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { instance = it }
            }

        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN channel TEXT NOT NULL DEFAULT 'SMS'")
                db.execSQL("ALTER TABLE scheduled_messages ADD COLUMN groupId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS contact_groups (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS group_members (groupId INTEGER NOT NULL, phone TEXT NOT NULL, name TEXT NOT NULL DEFAULT '', PRIMARY KEY(groupId, phone))")
                db.execSQL("CREATE TABLE IF NOT EXISTS auto_reply_rules (id INTEGER PRIMARY KEY NOT NULL, enabled INTEGER NOT NULL DEFAULT 0, replyText TEXT NOT NULL, mode TEXT NOT NULL, numbers TEXT NOT NULL DEFAULT '', delayMinutes INTEGER NOT NULL DEFAULT 0, onlyWhenIdle INTEGER NOT NULL DEFAULT 0)")
            }
        }
    }
}
