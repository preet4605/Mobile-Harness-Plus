package com.jarves.mh.runtime

import com.jarves.mh.model.RiskLevel
import com.jarves.mh.model.ToolRequest
import org.json.JSONObject

/** The existing PermissionRequest hook channel, with bounded input and fail-closed decisions. */
internal object ClaudeApprovalProtocol {
    fun request(sessionId: String, approvalId: String, json: JSONObject): ToolRequest {
        val tool = json.optString("tool_name").takeIf { it.isNotBlank() } ?: error("Missing approval tool")
        val input = json.optJSONObject("tool_input") ?: error("Missing approval input")
        val command = input.optString("command").takeIf { it.isNotBlank() }
        return ToolRequest(approvalId, sessionId, tool.take(100),
            ClaudeRuntimeBridge.sanitizeForDisplay(input.optString("description").ifBlank { command ?: "$tool in the project" }),
            listOf("file_path", "path", "notebook_path").mapNotNull { input.optString(it).takeIf(String::isNotBlank) },
            command?.let { ClaudeRuntimeBridge.sanitizeForDisplay(it) }, RiskLevel.REVIEW)
    }

    val hookScript: String = """#!/bin/sh
set -eu
bridge=/pocket-bridge
approval_session=${'$'}{MH_APPROVAL_SESSION_ID:-}
deny() { printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"deny","message":"Approval unavailable"}}}'; }
case "${'$'}approval_session" in ''|*[!0-9a-f-]*) deny; exit 0;; esac
[ "${'$'}{#approval_session}" -eq 36 ] || { deny; exit 0; }
stem=${'$'}(mktemp "${'$'}bridge/${'$'}approval_session-XXXXXX")
trap 'rm -f "${'$'}stem" "${'$'}stem.request" "${'$'}stem.response"' EXIT
head -c 65537 > "${'$'}stem"
[ "${'$'}(wc -c < "${'$'}stem")" -le 65536 ] || { deny; exit 0; }
mkfifo "${'$'}stem.response"
mv "${'$'}stem" "${'$'}stem.request"
answer=${'$'}(timeout 120 cat "${'$'}stem.response") || { deny; exit 0; }
case "${'$'}answer" in
allow) printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}';;
*) deny;;
esac
"""
}
