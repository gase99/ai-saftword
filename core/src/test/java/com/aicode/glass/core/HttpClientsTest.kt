package com.aicode.glass.core

import com.aicode.glass.core.network.HttpClients
import com.aicode.glass.core.network.LocalHttpInterceptor
import com.aicode.glass.core.network.ResilientAiClient
import com.aicode.glass.core.network.StreamingAiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport is created while Hilt builds ViewModels, i.e. on the main thread during app
 * startup. These assertions pin the contract: construction is pure in-memory work and the shared
 * timeouts are the ones the cloud providers need.
 */
class HttpClientsTest {
    @Test fun transportConstructionDoesNoIoAndUsesTheSharedTimeouts() {
        val client = HttpClients.provider()
        assertEquals(15_000, client.connectTimeoutMillis)
        assertEquals(90_000, client.readTimeoutMillis)
        assertEquals(240_000, client.callTimeoutMillis)
        assertFalse("Redirects must not be followed silently", client.followRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertTrue(client.interceptors.any { it is LocalHttpInterceptor })
        assertEquals("No connection may be opened at construction time", 0, client.connectionPool.connectionCount())

        // Building the client chain must not throw either: these are plain objects.
        val transport = StreamingAiClient(client)
        ResilientAiClient(transport)
    }
}
