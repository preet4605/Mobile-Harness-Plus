package com.jarves.mh.runtime

import com.jarves.mh.model.AgentUsage
import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.UsageLimit
import com.jarves.mh.model.usageResetText
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Claude prints a `rate_limit_event` during a run. The app keeps the last one; this reads it. */
internal object ClaudeUsageReport {
    fun parse(raw: String?): AgentUsage {
        if (raw.isNullOrBlank()) {
            return AgentUsage(note = "Claude reports its limits during a run. Send a message, then refresh.")
        }
        val info = runCatching { JSONObject(raw).optJSONObject("rate_limit_info") }.getOrNull()
            ?: return AgentUsage(note = "Claude's last limit report could not be read.")
        val status = info.optString("status").ifBlank { "reported" }
        val resets = if (info.isNull("resetsAt")) null else info.optLong("resetsAt")
        val label = when (info.optString("rateLimitType")) {
            "five_hour" -> "5-hour limit"
            "seven_day" -> "Weekly limit"
            "", "null" -> "Limit"
            else -> info.optString("rateLimitType")
        }
        return AgentUsage(limits = listOf(UsageLimit(label, "Status: $status${usageResetText(resets)}")))
    }
}

/** DeepSeek's `GET /user/balance` for an API key. The reply is parsed defensively; nothing else is assumed. */
internal object DeepSeekBalanceReport {
    private const val BALANCE_URL = "https://api.deepseek.com/user/balance"
    private const val TIMEOUT_MS = 10_000

    /** Blocking, so call from IO. Returns a note instead of throwing. The key is never echoed. */
    fun fetch(apiKey: String): AgentUsage {
        val connection = try {
            (URL(BALANCE_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "application/json")
            }
        } catch (e: IOException) {
            return AgentUsage(note = "Could not reach DeepSeek.")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                AgentUsage(note = "DeepSeek returned HTTP $code for the balance check.")
            } else {
                parse(connection.inputStream.bufferedReader().use { it.readText() })
            }
        } catch (e: IOException) {
            AgentUsage(note = "Could not read the balance from DeepSeek.")
        } finally {
            connection.disconnect()
        }
    }

    fun parse(body: String): AgentUsage {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: return AgentUsage(note = "DeepSeek's balance reply was not recognised.")
        if (json.has("is_available") && !json.optBoolean("is_available")) {
            return AgentUsage(note = "DeepSeek reports this account as unavailable.")
        }
        val infos = json.optJSONArray("balance_infos")
        val parts = buildList {
            for (index in 0 until (infos?.length() ?: 0)) {
                val info = infos?.optJSONObject(index) ?: continue
                val total = info.optString("total_balance")
                if (total.isBlank()) continue
                add("${info.optString("currency").ifBlank { "Balance" }} $total")
            }
        }
        return if (parts.isEmpty()) {
            AgentUsage(note = "DeepSeek's balance reply had no amounts.")
        } else {
            AgentUsage(balance = parts.joinToString(" · "))
        }
    }
}

/** Antigravity's quotas are already kept per Google account; this lists the primary account's models. */
internal object AntigravityUsageReport {
    fun from(account: AntigravityAccount?): AgentUsage {
        if (account == null) return AgentUsage(note = "Connect a Google account to see model quotas.")
        val limits = account.modelQuotas.entries.sortedBy { it.key }.map { (model, quota) ->
            val resets = quota.resetTimeMillis?.let { it / 1000L }
            UsageLimit(model, "${quota.percentage}% left${usageResetText(resets)}")
        }
        return AgentUsage(
            limits = limits,
            note = if (limits.isEmpty()) "No model quotas reported yet." else null,
        )
    }
}
