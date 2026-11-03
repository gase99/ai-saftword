package com.aicode.glass.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Transport configuration shared by the app (Hilt module) and the live-provider tests.
 *
 * OkHttp defaults to a 10 second read timeout, which a cold cloud model routinely exceeds before
 * it emits the first SSE frame. The JVM tests used to build a bare `OkHttpClient()`, so Gemini and
 * Ollama Cloud "failed" with a connection error instead of a provider response.
 */
object HttpClients {
    fun provider(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .addInterceptor(LocalHttpInterceptor())
        .build()
}
