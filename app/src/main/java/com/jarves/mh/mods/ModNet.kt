package com.jarves.mh.mods

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/** One `$.http.fetch` request after [ModNet.check] passed it. */
data class ModHttpRequest(val url: String, val method: String, val headers: Map<String, String>, val body: String?)

/**
 * `$.http.fetch` (spec v2 P10): https only, to the hosts the mod's `net.fetch` permission names
 * (`example.com`, or `*.example.com` for its subdomains). No cookies, no redirects followed (a 3xx
 * comes back as it is, with its `location`), a 256 KB body, a 1 MB answer and 15 s.
 */
object ModNet {
    const val TIMEOUT_MS = 15_000
    const val MAX_BODY = 256 * 1024
    const val MAX_RESPONSE = 1024 * 1024
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
    private val HEADER = Regex("^[A-Za-z0-9-]{1,64}$")
    private val BLOCKED_HEADERS = setOf(
        "host", "cookie", "cookie2", "connection", "content-length", "transfer-encoding", "te", "trailer",
        "upgrade", "keep-alive", "proxy-authorization", "proxy-connection", "expect",
    )

    fun check(url: String, init: JSONObject?, hosts: List<String>): ModHttpRequest {
        require(hosts.isNotEmpty()) { "net.fetch needs scope.hosts naming the hosts this mod may reach" }
        val uri = runCatching { URI(url) }.getOrNull()
        require(url.length <= 4_000 && uri != null && uri.scheme.equals("https", ignoreCase = true)) { "\$.http.fetch reaches https:// URLs only" }
        require(uri.rawUserInfo == null) { "URLs with a user name or password are refused" }
        require(uri.port == -1 || uri.port == 443) { "Only the standard https port is allowed" }
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: error("That URL has no host")
        require(!host.all { it.isDigit() || it == '.' } && ':' !in host && host != "localhost" && !host.endsWith(".localhost")) {
            "Addresses and localhost are refused; name a host"
        }
        require(hosts.any { allowed(it, host) }) { "$host is not one of the hosts this mod may reach (${hosts.joinToString()})" }
        val method = (init?.optString("method")?.takeIf { it.isNotBlank() } ?: "GET").uppercase(Locale.ROOT)
        require(method in METHODS) { "Method $method is not allowed" }
        val headers = LinkedHashMap<String, String>()
        init?.optJSONObject("headers")?.let { given ->
            given.keys().forEach { name ->
                require(HEADER.matches(name)) { "Bad header name $name" }
                require(name.lowercase(Locale.ROOT) !in BLOCKED_HEADERS) { "The $name header can't be set" }
                val value = given.opt(name)?.toString().orEmpty()
                require(value.length <= 8_192 && '\n' !in value && '\r' !in value) { "Bad value for header $name" }
                headers[name] = value
            }
        }
        require(headers.size <= 32) { "At most 32 headers" }
        val body = init?.opt("body")?.takeIf { it != JSONObject.NULL }?.let { if (it is String) it else it.toString() }
        require(body == null || method != "GET" && method != "HEAD") { "$method requests have no body" }
        require((body?.toByteArray()?.size ?: 0) <= MAX_BODY) { "The body is larger than ${MAX_BODY / 1024} KB" }
        return ModHttpRequest(uri.toASCIIString(), method, headers, body)
    }

    fun allowed(pattern: String, host: String): Boolean {
        val p = pattern.lowercase(Locale.ROOT).trimEnd('.')
        return if (p.startsWith("*.")) host.endsWith(p.removePrefix("*")) && host.length > p.length - 1 else host == p
    }

    /** Sends [request]; answers `{ status, ok, url, headers, text }`. Blocking; call it off the host thread. */
    fun send(request: ModHttpRequest): JSONObject {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.requestMethod = request.method
            connection.setRequestProperty("User-Agent", "MobileHarness-Mod")
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            request.body?.let { body ->
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val out = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    check(System.currentTimeMillis() < deadline) { "The request took longer than ${TIMEOUT_MS / 1000} s" }
                    val read = input.read(buffer)
                    if (read < 0) break
                    check(out.size() + read <= MAX_RESPONSE) { "The answer is larger than 1 MB" }
                    out.write(buffer, 0, read)
                }
            }
            val headers = JSONObject()
            connection.headerFields.entries.filter { it.key != null }.take(64).forEach { (name, values) ->
                val lower = name.lowercase(Locale.ROOT)
                if (lower != "set-cookie" && lower != "set-cookie2") headers.put(lower, values.joinToString(", ").take(4_000))
            }
            return JSONObject()
                .put("status", status)
                .put("ok", status in 200..299)
                .put("url", request.url)
                .put("headers", headers)
                .put("text", out.toByteArray().toString(Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
}
