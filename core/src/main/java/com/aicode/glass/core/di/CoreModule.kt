package com.aicode.glass.core.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Binds
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): com.aicode.glass.core.data.GlassDatabase =
        androidx.room.Room.databaseBuilder(context, com.aicode.glass.core.data.GlassDatabase::class.java, "aicode-glass.db").build()
    @Provides fun workspaces(db: com.aicode.glass.core.data.GlassDatabase) = db.workspaceDao()
    @Provides fun chat(db: com.aicode.glass.core.data.GlassDatabase) = db.chatDao()
    @Provides fun checkpoints(db: com.aicode.glass.core.data.GlassDatabase) = db.checkpointDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkModule {
    @Binds @Singleton abstract fun ai(client: com.aicode.glass.core.network.StreamingAiClient): com.aicode.glass.core.network.AiClient
}
