package com.aicode.glass.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress

class LocalHttpInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request(); val url = request.url
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("URL credentials are not allowed")
        if (url.isHttps) return chain.proceed(request)
        val marker = request.header("X-AiCode-Local-HTTP")
        if (marker != "enabled" || !EndpointPolicy.isLocal(url.host)) throw IOException("Cleartext HTTP is restricted to explicitly enabled local endpoints")
        val addresses = InetAddress.getAllByName(url.host)
        if (addresses.isEmpty() || addresses.any { !EndpointPolicy.isLocal(it.hostAddress) }) throw IOException("Local endpoint resolved outside the private network")
        return chain.proceed(request.newBuilder().removeHeader("X-AiCode-Local-HTTP").build())
    }
}
