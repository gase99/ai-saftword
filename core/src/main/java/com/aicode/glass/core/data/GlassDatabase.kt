package com.aicode.glass.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(version = 1, exportSchema = false, entities = [WorkspaceEntity::class, ConversationEntity::class,
    MessageEntity::class, ToolInvocationEntity::class, CheckpointEntity::class, CheckpointFileEntity::class])
abstract class GlassDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun chatDao(): ChatDao
    abstract fun checkpointDao(): CheckpointDao
}
