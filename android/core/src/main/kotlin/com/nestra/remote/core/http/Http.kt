package com.nestra.remote.core.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** One HTTP exchange. Header values may carry credentials: [toString] never prints them. */
class HttpRequest(val method: String, val url: String, val headers: Map<String, String> = emptyMap(), val body: String? = null) {
    override fun toString() = "HttpRequest($method $url, headers=${headers.keys}, body=${body?.length ?: 0} chars)"
}

class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: String) {
    /** Case-insensitive header lookup (all values). */
    fun header(name: String): List<String> = headers.entries.filter { it.key.equals(name, ignoreCase = true) }.flatMap { it.value }
    override fun toString() = "HttpResponse($status, ${body.length} chars)"
}

/** Network seam: production = [UrlConnectionTransport]; tests use an in-memory fake. */
fun interface HttpTransport {
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/**
 * HttpURLConnection transport (available on Android and the JVM, no dependency).
 * No redirects (a credential must never follow a redirect), no caches, no cookie store: the only cookie ever sent is one
 * the caller sets explicitly (to NESTRA Parent), and Set-Cookie answers are only read, never stored.
 * TLS: the platform trust store; certificate validation is never disabled.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000,
    private val maxBodyBytes: Int = 64 * 1024,
    private val userAgent: String = "NESTRA-Remote-Android/0.1.0",
) : HttpTransport {
    override fun execute(request: HttpRequest): HttpResponse {
        val c = URI(request.url).toURL().openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.requestMethod = request.method
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", "application/json")
            for ((k, v) in request.headers) c.setRequestProperty(k, v)
            if (request.body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = request.body.toByteArray(Charsets.UTF_8)
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            } else if (request.method == "POST") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val body = stream?.use { s ->
                val buf = s.readNBytesCompat(maxBodyBytes + 1)
                if (buf.size > maxBodyBytes) throw IOException("response too large")
                String(buf, Charsets.UTF_8)
            } ?: ""
            val headers = c.headerFields.filterKeys { it != null }.mapValues { it.value.toList() }
            return HttpResponse(status, headers, body)
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < n) {
            val r = read(buf, 0, minOf(buf.size, n - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }
}

/** Value of a cookie [name] from Set-Cookie headers (read only; never stored by the transport). */
fun HttpResponse.setCookieValue(name: String): String? =
    header("Set-Cookie").asSequence()
        .map { it.substringBefore(';').trim() }
        .filter { it.startsWith("$name=") }
        .map { it.substring(name.length + 1) }
        .lastOrNull { it.isNotEmpty() }
