package com.wiggletonabbey.wigglebot.service

import android.content.Context
import com.wiggletonabbey.wigglebot.BuildConfig
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "agent_settings")

data class AgentSettings(
    val serverUrl: String = "https://wiggleton-server.tail22bb77.ts.net:11435",
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
)

private val KEY_SERVER_URL    = stringPreferencesKey("server_url")
private val KEY_SYSTEM_PROMPT = stringPreferencesKey("system_prompt")

class SettingsRepository(private val context: Context) {

    val settings: Flow<AgentSettings> = context.settingsDataStore.data.map { prefs ->
        AgentSettings(
            serverUrl = prefs[KEY_SERVER_URL]
                ?.takeIf { it.isNotBlank() }
                ?: BuildConfig.SERVER_URL.takeIf { it.isNotBlank() }
                ?: "https://wiggleton-server.tail22bb77.ts.net:11435",
            systemPrompt = prefs[KEY_SYSTEM_PROMPT] ?: DEFAULT_SYSTEM_PROMPT,
        )
    }

    suspend fun save(settings: AgentSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[KEY_SERVER_URL]    = settings.serverUrl
            prefs[KEY_SYSTEM_PROMPT] = settings.systemPrompt
        }
    }
}

const val DEFAULT_SYSTEM_PROMPT = """You are a helpful phone assistant running on an Android device. \
You can control apps, play music, open audiobooks, and perform device actions using the tools available to you.

When a user asks you to do something, figure out which tool(s) to call. \
If you're not sure whether an app is installed, call get_installed_apps first.

After executing tools, give a short, friendly confirmation of what you did. \
Don't over-explain. Be concise."""
