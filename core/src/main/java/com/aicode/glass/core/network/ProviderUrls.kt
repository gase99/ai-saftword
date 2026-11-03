package com.aicode.glass.core.network

import java.net.URI

/**
 * Resolves the exact HTTP endpoint used for a provider stream.
 *
 * User-supplied endpoints are normalised here so that an Ollama Cloud base such as
 * `https://ollama.com` reaches the OpenAI-compatible layer instead of a 404 route:
 *
 * | configured base                   | request URL                                    |
 * |-----------------------------------|------------------------------------------------|
 * | `https://ollama.com`              | `https://ollama.com/v1/chat/completions`       |
 * | `https://ollama.com/api`          | `https://ollama.com/v1/chat/completions`       |
 * | `https://ollama.com/api/chat`     | `https://ollama.com/v1/chat/completions`       |
 * | `https://ollama.com/v1`           | `https://ollama.com/v1/chat/completions`       |
 * | `http://127.0.0.1:11434`          | `http://127.0.0.1:11434/v1/chat/completions`   |
 * | `http://127.0.0.1:11434/v1`       | `http://127.0.0.1:11434/v1/chat/completions`   |
 * | `https://proxy.example/ollama`    | `https://proxy.example/ollama/chat/completions`|
 *
 * Only a bare host (or an Ollama `/api` prefix) gets `/v1` appended: an explicit path is
 * preserved verbatim so reverse proxies that already mount the OpenAI routes keep working.
 */
object ProviderUrls {
    const val OPENAI_CHAT_PATH = "/chat/completions"

    /** Base URL of the provider, without a trailing slash. */
    fun base(config: ProviderConfig): String = config.endpoint.trim().ifBlank { config.provider.baseUrl }.trimEnd('/')

    /** Versioned base actually used by the provider API. */
    fun apiBase(config: ProviderConfig): String = when (config.provider) {
        ProviderType.ANTHROPIC -> withVersion(base(config), "/v1")
        ProviderType.GEMINI -> withVersion(base(config), "/v1beta")
        else -> normalizeOpenAiBase(base(config))
    }

    fun normalizeOpenAiBase(base: String): String {
        var value = base.trim().trimEnd('/')
        // A fully-qualified chat URL is reduced to its base before the path is appended again.
        if (value.endsWith(OPENAI_CHAT_PATH)) value = value.removeSuffix(OPENAI_CHAT_PATH).trimEnd('/')
        // Ollama's native prefix (/api, /api/chat) does not host the OpenAI-compatible routes,
        // and `/api/v1` is the most common wrong guess (it answers 404).
        if (value.endsWith("/api/chat")) value = value.removeSuffix("/api/chat").trimEnd('/')
        if (value.endsWith("/api/v1")) value = value.removeSuffix("/api/v1").trimEnd('/') + "/v1"
        if (value.endsWith("/api")) value = value.removeSuffix("/api").trimEnd('/')
        return if (isBareHost(value)) "$value/v1" else value
    }

    /** Streaming URL for [config], including the Gemini `:streamGenerateContent` method suffix. */
    fun streamUrl(config: ProviderConfig): String = when (config.provider) {
        ProviderType.ANTHROPIC -> "${apiBase(config)}/messages"
        ProviderType.GEMINI -> "${apiBase(config)}/models/${config.model}:streamGenerateContent?alt=sse"
        else -> apiBase(config) + OPENAI_CHAT_PATH
    }

    /**
     * Ollama Cloud (`https://ollama.com`, `https://api.ollama.com`) is the only Ollama endpoint
     * that always requires a bearer token. A local server, or a self-hosted proxy you control,
     * may be keyless, so those keep working without a credential.
     */
    fun isOllamaCloud(base: String): Boolean = runCatching {
        val host = URI(base).host ?: return@runCatching false
        val normalized = host.lowercase().trimEnd('.')
        normalized == "ollama.com" || normalized.endsWith(".ollama.com")
    }.getOrDefault(false)

    /** Model-listing URL, used to explain a 404 for the configured model. */
    fun modelsUrl(config: ProviderConfig): String? = when (config.provider) {
        ProviderType.GEMINI -> "${apiBase(config)}/models?pageSize=200"
        ProviderType.OPENAI, ProviderType.CUSTOM, ProviderType.OLLAMA -> "${apiBase(config)}/models"
        ProviderType.ANTHROPIC -> null
    }

    private fun withVersion(base: String, version: String): String {
        val value = base.trimEnd('/')
        return if (isBareHost(value)) value + version else value
    }

    private fun isBareHost(url: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return path.isBlank() || path == "/"
    }
}
