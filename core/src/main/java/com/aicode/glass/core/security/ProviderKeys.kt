package com.aicode.glass.core.security

import com.aicode.glass.core.network.ProviderType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderKeys @Inject constructor(private val secrets: SecretStore) {
    suspend fun get(provider: ProviderType): List<String> {
        val name = "api_${provider.name}"
        val raw = secrets.get(name)
        val multiple = secrets.get(name + "_pool")
        val pool = if (multiple.isNotBlank()) runCatching { Json.decodeFromString(ListSerializer(String.serializer()), multiple) }.getOrDefault(emptyList()) else emptyList()
        return (pool + listOf(raw)).map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(8)
    }
    suspend fun add(provider: ProviderType, key: String) {
        if (key.isBlank()) return
        require(key.length <= 4096 && key.none { it.isWhitespace() }) { "Invalid API key format." }
        val keys = (get(provider) + key.trim()).distinct().takeLast(8)
        secrets.set("api_${provider.name}_pool", Json.encodeToString(ListSerializer(String.serializer()), keys))
        secrets.delete("api_${provider.name}")
    }
    suspend fun clear(provider: ProviderType) { secrets.delete("api_${provider.name}"); secrets.delete("api_${provider.name}_pool") }
    suspend fun count(provider: ProviderType) = get(provider).size
}
