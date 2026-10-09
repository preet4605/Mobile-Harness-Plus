package com.jarves.mh.network

import java.io.IOException
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubClientTest {
    @Test
    fun failedApiRequestReleasesConnection() {
        val connection = FakeHttpConnection(401, """{"message":"Bad credentials"}""".toByteArray())
        val error = runCatching { GitHubClient(openConnection = { connection }).account("token") }.exceptionOrNull()
        assertTrue("expected the API message, got $error", error?.message == "Bad credentials")
        assertTrue("connection left open after an HTTP error", connection.disconnected)
    }

    @Test
    fun failedBodyReadReleasesConnection() {
        val connection = FakeHttpConnection(200, readFailure = IOException("connection reset"))
        val error = runCatching { GitHubClient(openConnection = { connection }).account("token") }.exceptionOrNull()
        assertTrue("expected the read failure, got $error", error is IOException)
        assertTrue("connection left open after a read failure", connection.disconnected)
    }

    @Test
    fun failedFormPostReleasesConnection() {
        val connection = FakeHttpConnection(200, readFailure = IOException("read timed out"))
        runCatching { GitHubClient(openConnection = { connection }).pollDeviceToken("client", "device") }
        assertTrue("connection left open after a form post failure", connection.disconnected)
    }

    @Test
    fun oversizedResponseIsRejectedInsteadOfParsed() {
        val padding = "x".repeat(9 * 1024 * 1024)
        val connection = FakeHttpConnection(200, """{"login":"octocat","avatar_url":"","padding":"$padding"}""".toByteArray())
        val error = runCatching { GitHubClient(openConnection = { connection }).account("token") }.exceptionOrNull()
        assertTrue("expected a size-limit failure, got $error", error is IOException && error.message?.contains("limit") == true)
        assertTrue("connection left open after an oversized response", connection.disconnected)
    }
}
