package com.aicode.glass.core.network

import java.net.InetAddress
import java.net.URI

/** TLS for remote services. Cleartext is an explicit, literal-address LAN/loopback exception. */
object EndpointPolicy {
    fun validate(endpoint: String, allowLocalHttp: Boolean = false): URI {
        val uri = runCatching { URI(endpoint) }.getOrElse { throw AiException("Invalid endpoint URL.") }
        if (uri.host.isNullOrBlank() || uri.userInfo != null || uri.query != null || uri.fragment != null || (uri.port != -1 && uri.port !in 1..65535)) throw AiException("Use a URL without credentials, query parameters or fragments.")
        if (uri.scheme == "https") return uri
        if (uri.scheme != "http" && uri.scheme != null) throw AiException("Only HTTPS or explicitly enabled local HTTP endpoints are supported.")
        if (!allowLocalHttp) throw AiException("Enable local HTTP in Settings to use a loopback or private-network server.")
        if (!isLocal(uri.host)) throw AiException("Local HTTP requires a loopback or private IP literal (or localhost), not a public domain.")
        return uri
    }
    /**
     * True for a plain-HTTP loopback/private-network endpoint. Such a server (a local Ollama
     * instance) is the only case where an empty API key is acceptable.
     */
    fun isLocalEndpoint(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host ?: return@runCatching false
        uri.scheme?.equals("http", true) == true && isLocal(host)
    }.getOrDefault(false)

    fun isLocal(host: String): Boolean {
        if (host.equals("localhost", true) || host == "10.0.2.2") return true
        return runCatching {
            // Reject DNS names before parsing; no DNS lookup or rebinding opportunity for HTTP.
            if (!host.matches(Regex("[0-9a-fA-F:.]+"))) return false
            val address = InetAddress.getByName(host).address
            if (address.size == 4) {
                val a = address[0].toInt() and 255; val b = address[1].toInt() and 255
                a == 127 || a == 10 || (a == 192 && b == 168) || (a == 172 && b in 16..31)
            } else address[0].toInt() and 254 == 252 || address.contentEquals(InetAddress.getByName("::1").address)
        }.getOrDefault(false)
    }
}
