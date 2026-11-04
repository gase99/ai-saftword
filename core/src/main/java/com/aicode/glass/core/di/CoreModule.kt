package com.aicode.glass.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Binds
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * A settings file left behind by an older or interrupted install must never abort startup:
 * DataStore would otherwise throw a CorruptionException from the eagerly collected settings flow.
 * Reset to defaults instead. API keys are *not* stored here - they live in the device-bound
 * keystore store (SecretStore) - so nothing sensitive is lost by replacing this file.
 */
private val Context.dataStore by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore
    /**
     * Building the transport performs no I/O, no DNS lookup and no thread creation, so creating it
     * while Hilt builds a ViewModel on the main thread is safe. Network work only starts when a
     * stream is actually collected (see StreamingAiClient).
     */
    @Provides @Singleton fun http(): okhttp3.OkHttpClient = com.aicode.glass.core.network.HttpClients.provider()
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): com.aicode.glass.core.data.GlassDatabase =
        androidx.room.Room.databaseBuilder(context, com.aicode.glass.core.data.GlassDatabase::class.java, "aicode-glass.db")
            // Version 1 databases written by earlier builds may not match the current entities.
            // Recreate them rather than crash the app on first open; see GlassDatabase for the
            // version history and docs/CRASH_TRIAGE.md before wiring real migrations.
            .fallbackToDestructiveMigration()
            .build()
    @Provides fun workspaces(db: com.aicode.glass.core.data.GlassDatabase) = db.workspaceDao()
    @Provides fun chat(db: com.aicode.glass.core.data.GlassDatabase) = db.chatDao()
    @Provides fun checkpoints(db: com.aicode.glass.core.data.GlassDatabase) = db.checkpointDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds @Singleton abstract fun ai(client: com.aicode.glass.core.network.ResilientAiClient): com.aicode.glass.core.network.AiClient
}
