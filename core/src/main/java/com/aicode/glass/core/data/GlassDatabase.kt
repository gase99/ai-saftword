package com.aicode.glass.core.data

import androidx.room.Database
import androidx.room.RoomDatabase

/*
 * Version history:
 *   1  Initial schema. Builds installed before the schema was frozen may carry a version 1
 *      database file whose identity hash does not match the entities below.
 *   2  Current schema. No explicit Migration(1, 2) exists yet, so the builder is configured with
 *      fallbackToDestructiveMigration(): an older database file is recreated instead of aborting
 *      the process with "Room cannot verify the data integrity".
 *
 * Note: a same-version schema mismatch cannot be recovered by the fallback, because Room only
 * runs migrations when the stored version differs. That is exactly why the version was bumped.
 *
 * Before a public release, replace the destructive fallback with explicit migrations
 * (see docs/CRASH_TRIAGE.md) and keep this comment updated.
 */
@Database(version = 2, exportSchema = true, entities = [WorkspaceEntity::class, ConversationEntity::class,
    MessageEntity::class, ToolInvocationEntity::class, CheckpointEntity::class, CheckpointFileEntity::class])
abstract class GlassDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun chatDao(): ChatDao
    abstract fun checkpointDao(): CheckpointDao
}
