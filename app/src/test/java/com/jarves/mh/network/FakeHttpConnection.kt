package com.jarves.mh.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Scripted connection for tests. It never opens a socket; [disconnected] records whether
 * production code released the connection, and [readFailure] makes the body read throw.
 */
internal class FakeHttpConnection(
    private val status: Int,
    private val body: ByteArray = ByteArray(0),
    private val readFailure: IOException? = null,
) : HttpURLConnection(URL("https://fake.invalid/")) {
    val requestBody = ByteArrayOutputStream()
    var disconnected = false
        private set

    override fun connect() {}

    override fun disconnect() {
        disconnected = true
    }

    override fun usingProxy(): Boolean = false

    override fun getResponseCode(): Int = status

    override fun getContentLengthLong(): Long = body.size.toLong()

    override fun getOutputStream(): OutputStream = requestBody

    override fun getInputStream(): InputStream {
        if (status !in 200..299) throw IOException("Server returned HTTP response code: $status")
        return responseStream()
    }

    override fun getErrorStream(): InputStream? = if (status in 200..299) null else responseStream()

    private fun responseStream(): InputStream {
        val failure = readFailure ?: return ByteArrayInputStream(body)
        return object : InputStream() {
            override fun read(): Int = throw failure
        }
    }
}
