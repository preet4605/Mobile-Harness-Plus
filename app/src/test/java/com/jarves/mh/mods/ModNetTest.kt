package com.jarves.mh.mods

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModNetTest {

    private fun refusal(url: String, init: JSONObject? = null, hosts: List<String> = listOf("api.example.com", "*.example.org")): String =
        runCatching { ModNet.check(url, init, hosts) }.exceptionOrNull()?.message ?: "allowed"

    @Test
    fun `only https to named hosts, without addresses, odd ports or smuggled headers`() {
        assertEquals("allowed", refusal("https://api.example.com/v1?q=1"))
        assertEquals("allowed", refusal("https://a.b.example.org/x"))
        assertTrue(ModNet.allowed("*.example.org", "cdn.example.org"))
        assertFalse(ModNet.allowed("*.example.org", "example.org"))
        assertFalse(ModNet.allowed("*.example.org", "evilexample.org"))
        assertTrue(refusal("https://example.org/").contains("is not one of the hosts"))
        assertTrue(refusal("https://api.example.com.evil.test/").contains("is not one of the hosts"))
        assertTrue(refusal("http://api.example.com/").contains("https://"))
        assertTrue(refusal("https://127.0.0.1/").contains("Addresses and localhost"))
        assertTrue(refusal("https://localhost/").contains("Addresses and localhost"))
        assertTrue(refusal("https://api.example.com:8443/").contains("standard https port"))
        assertTrue(refusal("https://user:pw@api.example.com/").contains("user name or password"))
        assertTrue(refusal("https://api.example.com/", JSONObject().put("headers", JSONObject().put("Host", "x"))).contains("can't be set"))
        assertTrue(refusal("https://api.example.com/", JSONObject().put("headers", JSONObject().put("X-A", "a\r\nb"))).contains("Bad value"))
        assertTrue(refusal("https://api.example.com/", JSONObject().put("body", "x")).contains("have no body"))
        assertTrue(refusal("https://api.example.com/", JSONObject().put("method", "TRACE")).contains("not allowed"))
        assertTrue(refusal("https://api.example.com/", hosts = emptyList()).contains("scope.hosts"))
    }
}
