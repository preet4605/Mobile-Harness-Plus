package com.jarves.mh.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClaudeApprovalProtocolTest {
    @Test fun approvalCarriesSessionToolPathsAndRedactsDisplaySecrets() {
        val request = ClaudeApprovalProtocol.request("bridge-session", "approval", JSONObject("""{"tool_name":"Bash","tool_input":{"command":"echo sk-1234567890123456","file_path":"/workspace/a"}}"""))
        assertEquals("bridge-session", request.sessionId)
        assertEquals("Bash", request.toolName)
        assertEquals(listOf("/workspace/a"), request.affectedPaths)
        assertFalse(request.commandPreview!!.contains("sk-1234567890123456"))
    }
    @Test fun malformedRequestsFailClosedAndHookHasTimeoutAndSessionOwnership() {
        assertThrows(IllegalStateException::class.java) { ClaudeApprovalProtocol.request("s", "a", JSONObject()) }
        assertTrue(ClaudeApprovalProtocol.hookScript.contains("timeout 120"))
        assertTrue(ClaudeApprovalProtocol.hookScript.contains("MH_APPROVAL_SESSION_ID"))
        assertTrue(ClaudeApprovalProtocol.hookScript.contains("65536"))
        assertTrue(ClaudeApprovalProtocol.hookScript.contains("mkfifo"))
    }
}
