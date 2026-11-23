package com.mini.bot.feature.agent.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mini.bot.feature.agent.data.local.dao.AgentMessageDao
import com.mini.bot.feature.agent.data.local.dao.ChatSessionDao
import com.mini.bot.feature.agent.data.local.dao.CheckpointDao
import com.mini.bot.feature.agent.data.local.dao.LlmCallRecordDao
import com.mini.bot.feature.agent.data.local.dao.TodoItemDao
import com.mini.bot.feature.agent.data.local.entity.AgentMessageEntity
import com.mini.bot.feature.agent.data.local.entity.ChatSessionEntity
import com.mini.bot.feature.agent.data.local.entity.CheckpointEntity
import com.mini.bot.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.mini.bot.feature.agent.data.local.entity.LlmCallRecordEntity
import com.mini.bot.feature.agent.data.local.entity.TodoItemEntity
import com.mini.bot.feature.backup.data.local.dao.BackupTargetDao
import com.mini.bot.feature.backup.data.local.entity.BackupTargetEntity
import com.mini.bot.feature.settings.data.local.dao.AIProviderDao
import com.mini.bot.feature.settings.data.local.entity.AIProviderEntity
import com.mini.bot.feature.workspace.data.local.dao.RemoteConnectionDao
import com.mini.bot.feature.workspace.data.local.entity.RemoteConnectionEntity
import com.mini.bot.feature.workspace.data.local.entity.RemoteMountEntity

@Database(
    entities = [AgentMessageEntity::class, ChatSessionEntity::class, AIProviderEntity::class, RemoteConnectionEntity::class, RemoteMountEntity::class, TodoItemEntity::class, CheckpointEntity::class, CheckpointFileSnapshotEntity::class, LlmCallRecordEntity::class, BackupTargetEntity::class],
    version = AgentDatabase.SCHEMA_VERSION,
    exportSchema = true
)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentMessageDao(): AgentMessageDao
    abstract fun chatSessionDao(): ChatSessionDao
    abstract fun aiProviderDao(): AIProviderDao
    abstract fun remoteConnectionDao(): RemoteConnectionDao
    abstract fun todoItemDao(): TodoItemDao
    abstract fun checkpointDao(): CheckpointDao
    abstract fun llmCallRecordDao(): LlmCallRecordDao
    abstract fun backupTargetDao(): BackupTargetDao

    companion object {
        const val SCHEMA_VERSION = 67

        /** 数据库文件名（落在 `databases/` 下，另有 Room 默认 WAL 模式产生的 `-wal`/`-shm`）。 */
        const val DATABASE_NAME = "minibot_agent_db"
    }
}
