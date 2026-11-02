package com.aicode.glass.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppPreferences(
    val language: String = "en", val darkTheme: Boolean = true,
    val activeWorkspace: String = "default", val activeConversation: Long = 0L,
    val provider: String = "OPENAI", val authorName: String = "AiCode Glass",
    val authorEmail: String = "developer@localhost", val gitRemote: String = ""
)

@Singleton
class SettingsRepository @Inject constructor(private val store: DataStore<Preferences>) {
    val preferences: Flow<AppPreferences> = store.data.map { p -> AppPreferences(
        language = p[Keys.language] ?: "en", darkTheme = p[Keys.dark] ?: true,
        activeWorkspace = p[Keys.workspace] ?: "default", activeConversation = p[Keys.conversation] ?: 0L,
        provider = p[Keys.provider] ?: "OPENAI", authorName = p[Keys.authorName] ?: "AiCode Glass",
        authorEmail = p[Keys.authorEmail] ?: "developer@localhost", gitRemote = p[Keys.remote] ?: ""
    ) }
    suspend fun setLanguage(value: String) = store.edit { it[Keys.language] = if (value == "fa") "fa" else "en" }
    suspend fun setDarkTheme(value: Boolean) = store.edit { it[Keys.dark] = value }
    suspend fun setWorkspace(value: String) = store.edit { it[Keys.workspace] = value }
    suspend fun setConversation(value: Long) = store.edit { it[Keys.conversation] = value }
    suspend fun setProvider(value: String) = store.edit { it[Keys.provider] = value }
    suspend fun setGitIdentity(name: String, email: String, remote: String) = store.edit {
        it[Keys.authorName] = name; it[Keys.authorEmail] = email; it[Keys.remote] = remote
    }
    private object Keys {
        val language = stringPreferencesKey("language"); val dark = booleanPreferencesKey("dark")
        val workspace = stringPreferencesKey("workspace"); val conversation = longPreferencesKey("conversation")
        val provider = stringPreferencesKey("provider"); val authorName = stringPreferencesKey("author_name")
        val authorEmail = stringPreferencesKey("author_email"); val remote = stringPreferencesKey("git_remote")
    }
}
