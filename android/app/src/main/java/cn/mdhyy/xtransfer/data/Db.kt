package cn.mdhyy.xtransfer.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

const val PUBLIC_CONVERSATION_ID = "__public__"
const val PUBLIC_CONVERSATION_NAME = "局域网广播"

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val platform: String? = null,
    val lastMessageAt: Long = 0,
    val lastReadAt: Long = 0,
    val createdAt: Long = 0
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId", "createdAt")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val direction: String,
    val kind: String,
    val channel: String,
    val fromId: String = "",
    val fromName: String? = null,
    val body: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val localPath: String? = null,
    val status: String,
    val received: Long = 0,
    val total: Long = 0,
    val speedBps: Long = 0,
    val errorCode: String? = null,
    val xlanItemId: String? = null,
    val offerId: String? = null,
    val relayFileId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val recalled: Boolean = false
)

data class ConversationRow(
    @Embedded val conversation: ConversationEntity,
    val unread: Int
)

@Dao
interface ConversationDao {
    @Query(
        """SELECT c.*, (SELECT COUNT(*) FROM messages m
             WHERE m.conversationId = c.id AND m.direction = 'in'
               AND m.createdAt > c.lastReadAt) AS unread
           FROM conversations c ORDER BY c.lastMessageAt DESC"""
    )
    fun observeRows(): Flow<List<ConversationRow>>

    @Query("SELECT * FROM conversations")
    suspend fun all(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE conversations SET platform = :platform WHERE id = :id")
    suspend fun setPlatform(id: String, platform: String?)

    @Query("UPDATE conversations SET lastMessageAt = MAX(lastMessageAt, :at) WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("UPDATE conversations SET lastMessageAt = 0 WHERE id = :id")
    suspend fun resetLastMessage(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query(
        """UPDATE conversations SET lastReadAt = MAX(lastReadAt, COALESCE(
             (SELECT MAX(createdAt) FROM messages WHERE conversationId = :id AND direction = 'in'), 0))
           WHERE id = :id"""
    )
    suspend fun markRead(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY createdAt ASC, updatedAt ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE kind != 'text' AND localPath IS NOT NULL")
    suspend fun allFileMessages(): List<MessageEntity>

    @Query(
        """SELECT * FROM messages WHERE conversationId = :convId AND kind = 'text'
             AND status != 'failed'
           ORDER BY createdAt DESC, updatedAt DESC LIMIT :limit"""
    )
    suspend fun recentTexts(convId: String, limit: Int): List<MessageEntity>

    @Query(
        """SELECT * FROM messages WHERE conversationId = :convId AND direction = 'out'
             AND kind = 'text' AND body = :body AND createdAt >= :since
           ORDER BY createdAt DESC LIMIT 1"""
    )
    suspend fun findRecentOutText(convId: String, body: String, since: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query(
        """UPDATE messages SET status = :status, localPath = COALESCE(:path, localPath),
             fileName = COALESCE(:name, fileName), fileSize = COALESCE(:size, fileSize), updatedAt = :now
           WHERE id = :id"""
    )
    suspend fun applyItemUpdate(
        id: String,
        status: String?,
        path: String?,
        name: String?,
        size: Long?,
        now: Long
    )

    @Query(
        """UPDATE messages SET received = :received, total = :total, speedBps = :speed,
             status = CASE WHEN status IN ('pending','done','failed','canceled','rejected') THEN status
                           ELSE 'active' END, updatedAt = :now
           WHERE id = :id"""
    )
    suspend fun setProgress(id: String, received: Long, total: Long, speed: Long, now: Long)

    @Query("UPDATE messages SET status = :status, errorCode = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?, now: Long)

    @Query(
        """UPDATE messages SET status = 'failed', errorCode = COALESCE(errorCode, 'INTERRUPTED'),
             updatedAt = :now WHERE direction = 'out' AND status IN ('queued', 'sending', 'accepted', 'active')"""
    )
    suspend fun failUnfinishedOutbound(now: Long)

    @Query("UPDATE messages SET localPath = :path, updatedAt = :now WHERE id = :id")
    suspend fun setLocalPath(id: String, path: String, now: Long)

    @Query("UPDATE messages SET recalled = 1, updatedAt = :now WHERE id = :id")
    suspend fun setRecalled(id: String, now: Long)

    @Query(
        """UPDATE messages SET status = 'queued', received = 0, speedBps = 0, errorCode = NULL,
             total = COALESCE(fileSize, total), updatedAt = :now WHERE id = :id"""
    )
    suspend fun resetForRetry(id: String, now: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE conversationId = :id")
    suspend fun deleteByConversation(id: String)

    @Query("SELECT id FROM messages WHERE conversationId = :id")
    suspend fun idsByConversation(id: String): List<String>
}

@Database(entities = [ConversationEntity::class, MessageEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN recalled INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN relayFileId TEXT")
    }
}
