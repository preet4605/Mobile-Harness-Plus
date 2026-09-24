package com.jarves.mh.runtime

import android.content.Context
import android.util.Log
import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.AntigravityAccount
import com.jarves.mh.model.AntigravityAccountStatus
import com.jarves.mh.model.AntigravityLoadBalancingStrategy
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AntigravityAccountManager(
    private val context: Context? = null,
    private val preferences: AppPreferences? = null,
    private val customAccountsDir: File? = null,
    private val loadAccountsOverride: (() -> List<AntigravityAccount>)? = null,
    private val saveAccountsOverride: ((List<AntigravityAccount>) -> Unit)? = null,
    private val strategyOverride: (() -> AntigravityLoadBalancingStrategy)? = null,
    private val quotaFetcherOverride: ((String) -> Map<String, com.jarves.mh.model.ModelQuota>?)? = null,
    private val tokenRefresherOverride: ((String, String) -> String?)? = null,
) {
    constructor(context: Context, preferences: AppPreferences) : this(
        context = context,
        preferences = preferences,
        customAccountsDir = null,
        loadAccountsOverride = null,
        saveAccountsOverride = null,
        strategyOverride = null,
        quotaFetcherOverride = null,
        tokenRefresherOverride = null,
    )

    private val _accounts = MutableStateFlow<List<AntigravityAccount>>(emptyList())
    val accounts: StateFlow<List<AntigravityAccount>> = _accounts.asStateFlow()

    private val roundRobinIndex = AtomicInteger(0)

    init {
        migrateLegacyAccountIfNeeded()
        reload()
    }

    fun reload() {
        val current = loadAccountsOverride?.invoke() ?: preferences?.loadAntigravityAccounts() ?: emptyList()
        val recovered = current.map { acc ->
            if (acc.status == AntigravityAccountStatus.AUTH_ERROR && hasValidRefreshToken(acc.id)) {
                acc.copy(status = AntigravityAccountStatus.HEALTHY, failureMessage = null)
            } else {
                acc
            }
        }
        if (recovered != current) {
            saveAccountsOverride?.invoke(recovered) ?: preferences?.saveAntigravityAccounts(recovered)
        }
        _accounts.value = recovered
    }

    fun hasValidRefreshToken(accountId: String): Boolean {
        val tokenFile = getAccountTokenHostFile(accountId)
        if (!tokenFile.isFile) return false
        return runCatching {
            val root = JSONObject(tokenFile.readText())
            val tokenObj = root.optJSONObject("token")
            val rt = tokenObj?.optString("refresh_token")?.takeIf { it.isNotBlank() }
                ?: root.optString("refresh_token").takeIf { it.isNotBlank() }
            !rt.isNullOrBlank()
        }.getOrDefault(false)
    }

    fun accountsList(): List<AntigravityAccount> = _accounts.value

    fun getAccount(id: String): AntigravityAccount? = _accounts.value.firstOrNull { it.id == id }

    fun getPrimaryAccount(): AntigravityAccount? =
        _accounts.value.firstOrNull { it.isPrimary } ?: _accounts.value.firstOrNull()

    fun getAccountHomeGuestPath(accountId: String): String =
        "/root/.antigravity-accounts/$accountId"

    fun getAccountHomeHostDir(accountId: String): File =
        if (customAccountsDir != null) {
            File(customAccountsDir, accountId)
        } else {
            File(context?.filesDir, "runtime/ubuntu/root/.antigravity-accounts/$accountId")
        }

    fun getAccountTokenHostFile(accountId: String): File =
        File(getAccountHomeHostDir(accountId), ".gemini/antigravity-cli/antigravity-oauth-token")

    @Synchronized
    fun addAccount(
        id: String,
        email: String,
        label: String = "",
        isPrimary: Boolean = false,
    ): AntigravityAccount {
        val current = _accounts.value.toMutableList()
        val makePrimary = isPrimary || current.isEmpty()
        val updatedList = current.map {
            if (makePrimary) it.copy(isPrimary = false) else it
        }.filterNot { it.id == id }.toMutableList()

        val newAccount = AntigravityAccount(
            id = id,
            email = email,
            label = label,
            addedAt = System.currentTimeMillis(),
            lastUsedAt = System.currentTimeMillis(),
            status = AntigravityAccountStatus.HEALTHY,
            isPrimary = makePrimary,
        )
        updatedList.add(newAccount)
        saveAndPublish(updatedList)
        return newAccount
    }

    @Synchronized
    fun removeAccount(id: String): Boolean {
        val current = _accounts.value
        val target = current.firstOrNull { it.id == id } ?: return false
        val remaining = current.filterNot { it.id == id }.toMutableList()

        if (target.isPrimary && remaining.isNotEmpty()) {
            remaining[0] = remaining[0].copy(isPrimary = true)
        }

        // Delete guest storage for this account
        runCatching {
            getAccountHomeHostDir(id).deleteRecursively()
        }

        saveAndPublish(remaining)
        return true
    }

    @Synchronized
    fun setPrimary(id: String) {
        val current = _accounts.value
        if (current.none { it.id == id }) return
        val updated = current.map { it.copy(isPrimary = it.id == id) }
        saveAndPublish(updated)
    }

    @Synchronized
    fun setAccountEnabled(id: String, enabled: Boolean) {
        updateAccount(id) {
            it.copy(
                status = if (enabled) AntigravityAccountStatus.HEALTHY else AntigravityAccountStatus.DISABLED,
                failureMessage = null,
            )
        }
    }

    @Synchronized
    fun recordUsage(id: String) {
        updateAccount(id) {
            it.copy(
                lastUsedAt = System.currentTimeMillis(),
                status = if (it.status == AntigravityAccountStatus.QUOTA_EXHAUSTED) AntigravityAccountStatus.HEALTHY else it.status,
                quotaExhaustedUntil = if (it.status == AntigravityAccountStatus.QUOTA_EXHAUSTED) null else it.quotaExhaustedUntil,
                failureMessage = if (it.status == AntigravityAccountStatus.QUOTA_EXHAUSTED) null else it.failureMessage,
            )
        }
    }

    @Synchronized
    fun markQuotaExhausted(id: String, modelId: String? = null, cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS) {
        val until = System.currentTimeMillis() + cooldownMillis
        updateAccount(id) { acc ->
            val updatedQuotas = if (modelId != null) {
                acc.modelQuotas + (modelId to com.jarves.mh.model.ModelQuota(remainingFraction = 0f, resetTimeMillis = until))
            } else {
                acc.modelQuotas
            }
            acc.copy(
                status = AntigravityAccountStatus.QUOTA_EXHAUSTED,
                quotaExhaustedUntil = until,
                failureMessage = "Quota exhausted. Resets in ${cooldownMillis / (60 * 1000)}m",
                modelQuotas = updatedQuotas,
            )
        }
    }

    @Synchronized
    fun updateAccountModelQuotas(id: String, newQuotas: Map<String, com.jarves.mh.model.ModelQuota>) {
        updateAccount(id) { acc ->
            acc.copy(modelQuotas = acc.modelQuotas + newQuotas)
        }
    }

    @Synchronized
    fun updateAccountModelQuota(id: String, modelId: String, remainingFraction: Float, resetTimeMillis: Long? = null) {
        updateAccount(id) { acc ->
            val quota = com.jarves.mh.model.ModelQuota(
                remainingFraction = remainingFraction,
                resetTimeMillis = resetTimeMillis,
                lastFetchedMillis = System.currentTimeMillis()
            )
            acc.copy(modelQuotas = acc.modelQuotas + (modelId to quota))
        }
    }

    fun isTokenExpiredOrExpiringSoon(expiryStr: String?): Boolean {
        if (expiryStr.isNullOrBlank()) return false
        return runCatching {
            val expiryInstant = Instant.parse(expiryStr)
            Instant.now().plusSeconds(300).isAfter(expiryInstant)
        }.getOrDefault(false)
    }

    @Synchronized
    fun getAccountAccessToken(accountId: String, forceRefresh: Boolean = false): String? {
        val tokenFile = getAccountTokenHostFile(accountId)
        if (!tokenFile.isFile) return null
        return runCatching {
            val root = JSONObject(tokenFile.readText())
            val tokenObj = root.optJSONObject("token")
            val accessToken = tokenObj?.optString("access_token")?.takeIf { it.isNotBlank() }
                ?: root.optString("token").takeIf { it.isNotBlank() }
            val refreshToken = tokenObj?.optString("refresh_token")?.takeIf { it.isNotBlank() }
                ?: root.optString("refresh_token").takeIf { it.isNotBlank() }
            val expiryStr = tokenObj?.optString("expiry")?.takeIf { it.isNotBlank() }
                ?: root.optString("expiry").takeIf { it.isNotBlank() }

            val isExpired = isTokenExpiredOrExpiringSoon(expiryStr)
            val shouldRefresh = forceRefresh || accessToken.isNullOrBlank() || isExpired
            if (shouldRefresh && !refreshToken.isNullOrBlank()) {
                val refreshed = refreshAccessTokenWithRefreshToken(accountId, refreshToken, root)
                if (refreshed != null) {
                    return refreshed
                }
                if (forceRefresh || accessToken.isNullOrBlank() || isExpired) {
                    return null
                }
            }

            accessToken
        }.getOrNull()
    }

    @Synchronized
    fun refreshAccessTokenWithRefreshToken(
        accountId: String,
        refreshToken: String,
        existingRoot: JSONObject? = null,
    ): String? {
        if (tokenRefresherOverride != null) {
            val token = tokenRefresherOverride.invoke(accountId, refreshToken)
            if (token != null) {
                val acc = getAccount(accountId)
                if (acc?.status == AntigravityAccountStatus.AUTH_ERROR) {
                    resetStatus(accountId)
                }
            }
            return token
        }

        return runCatching {
            val connection = (URL(GOOGLE_OAUTH_TOKEN_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("User-Agent", "AntigravityCLI/1.1.27")
            }

            val postData = buildString {
                append("client_id=").append(URLEncoder.encode(ANTIGRAVITY_CLIENT_ID, "UTF-8"))
                append("&client_secret=").append(URLEncoder.encode(ANTIGRAVITY_CLIENT_SECRET, "UTF-8"))
                append("&refresh_token=").append(URLEncoder.encode(refreshToken, "UTF-8"))
                append("&grant_type=refresh_token")
            }

            connection.outputStream.use { it.write(postData.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                runCatching {
                    val errBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w("AntigravityAccountMgr", "Failed to refresh token for account $accountId (HTTP $code): $errBody")
                }
                if (code == 400 || code == 401) {
                    markAuthError(accountId, "Google OAuth token expired or revoked. Please re-authenticate.")
                }
                return null
            }

            val respBody = connection.inputStream.bufferedReader().use { it.readText() }
            val respJson = JSONObject(respBody)
            val newAccessToken = respJson.optString("access_token").takeIf { it.isNotBlank() } ?: return null
            val expiresIn = respJson.optLong("expires_in", 3600L)
            val newRefreshToken = respJson.optString("refresh_token").takeIf { it.isNotBlank() } ?: refreshToken
            val newExpiry = Instant.now().plusSeconds(expiresIn).toString()
            val newIdToken = respJson.optString("id_token").takeIf { it.isNotBlank() }

            val tokenFile = getAccountTokenHostFile(accountId)
            tokenFile.parentFile?.mkdirs()

            val root = existingRoot ?: runCatching {
                if (tokenFile.isFile) JSONObject(tokenFile.readText()) else JSONObject()
            }.getOrDefault(JSONObject())

            val tokenObj = root.optJSONObject("token") ?: JSONObject()
            tokenObj.put("access_token", newAccessToken)
            tokenObj.put("token_type", respJson.optString("token_type", "Bearer"))
            tokenObj.put("refresh_token", newRefreshToken)
            tokenObj.put("expiry", newExpiry)

            root.put("token", tokenObj)
            if (newIdToken != null) {
                root.put("id_token", newIdToken)
            }
            if (!root.has("auth_method")) {
                root.put("auth_method", "oauth")
            }

            tokenFile.writeText(root.toString(2))

            val ctx = context
            if (getAccount(accountId)?.isPrimary == true && ctx != null) {
                runCatching {
                    val legacyFile = File(ctx.filesDir, "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token")
                    if (legacyFile.isFile) {
                        tokenFile.copyTo(legacyFile, overwrite = true)
                    }
                }
            }

            val acc = getAccount(accountId)
            if (acc?.status == AntigravityAccountStatus.AUTH_ERROR) {
                resetStatus(accountId)
            }

            newAccessToken
        }.getOrNull()
    }

    suspend fun fetchAccountQuotas(accountId: String): Map<String, com.jarves.mh.model.ModelQuota>? = withContext(Dispatchers.IO) {
        if (quotaFetcherOverride != null) {
            return@withContext quotaFetcherOverride.invoke(accountId)
        }
        val token = getAccountAccessToken(accountId) ?: return@withContext null
        val (code, body) = executeQuotaHttpCall(token)
        if (code in 200..299) {
            return@withContext parseQuotaBuckets(body)
        }
        if (code == 401 || code == 403) {
            val refreshed = getAccountAccessToken(accountId, forceRefresh = true)
            if (refreshed != null && refreshed != token) {
                val (retryCode, retryBody) = executeQuotaHttpCall(refreshed)
                if (retryCode in 200..299) {
                    return@withContext parseQuotaBuckets(retryBody)
                }
            }
            markAuthError(accountId, "Authentication token expired. Please re-authenticate.")
            return@withContext null
        }
        null
    }

    private fun executeQuotaHttpCall(token: String): Pair<Int, String> = runCatching {
        val connection = (URL("https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuota").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "AntigravityCLI/1.1.27")
        }
        connection.outputStream.use { it.write("{}".toByteArray()) }
        val code = connection.responseCode
        val body = if (code in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        Pair(code, body)
    }.getOrElse { Pair(502, it.message.orEmpty()) }

    private fun parseQuotaBuckets(body: String): Map<String, com.jarves.mh.model.ModelQuota> = runCatching {
        val root = JSONObject(body)
        val buckets = root.optJSONArray("buckets") ?: return emptyMap()
        val result = mutableMapOf<String, com.jarves.mh.model.ModelQuota>()
        val now = System.currentTimeMillis()
        for (i in 0 until buckets.length()) {
            val item = buckets.optJSONObject(i) ?: continue
            val modelId = item.optString("modelId").takeIf { it.isNotBlank() } ?: continue
            val fraction = item.optDouble("remainingFraction", 1.0).toFloat().coerceIn(0f, 1f)
            val resetIso = item.optString("resetTime").takeIf { it.isNotBlank() }
            val resetMillis = resetIso?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            result[modelId] = com.jarves.mh.model.ModelQuota(
                remainingFraction = fraction,
                resetTimeMillis = resetMillis,
                lastFetchedMillis = now,
            )
        }
        result
    }.getOrDefault(emptyMap())

    suspend fun refreshAccountQuota(accountId: String): Boolean = withContext(Dispatchers.IO) {
        val quotas = fetchAccountQuotas(accountId) ?: return@withContext false
        updateAccountModelQuotas(accountId, quotas)
        val acc = getAccount(accountId)
        if (acc?.status == AntigravityAccountStatus.QUOTA_EXHAUSTED && quotas.values.any { !it.isExhausted }) {
            resetStatus(accountId)
        }
        true
    }

    suspend fun refreshAllAccountQuotas() = withContext(Dispatchers.IO) {
        _accounts.value.forEach { acc ->
            if (acc.status != AntigravityAccountStatus.DISABLED) {
                refreshAccountQuota(acc.id)
            }
        }
    }

    @Synchronized
    fun markAuthError(id: String, message: String) {
        updateAccount(id) {
            it.copy(
                status = AntigravityAccountStatus.AUTH_ERROR,
                failureMessage = message.take(240),
            )
        }
    }

    @Synchronized
    fun resetStatus(id: String) {
        updateAccount(id) {
            it.copy(
                status = AntigravityAccountStatus.HEALTHY,
                quotaExhaustedUntil = null,
                failureMessage = null,
            )
        }
    }

    /**
     * Selects an account to handle a task turn based on the configured strategy.
     * Excludes accounts passed in [excludeAccountIds] (e.g. accounts that just hit quota).
     */
    fun selectAccountForTurn(
        excludeAccountIds: Set<String> = emptySet(),
        stickyAccountId: String? = null,
    ): AntigravityAccount? {
        val current = _accounts.value
        if (current.isEmpty()) return null

        // 1. Determine routing strategy
        val strategy = strategyOverride?.invoke()
            ?: preferences?.antigravityLoadBalancingStrategy
            ?: AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED

        // If PRIMARY_ONLY and sticky account requested and valid/healthy, use it
        if (strategy == AntigravityLoadBalancingStrategy.PRIMARY_ONLY && !stickyAccountId.isNullOrBlank() && stickyAccountId !in excludeAccountIds) {
            val sticky = current.firstOrNull { it.id == stickyAccountId }
            if (sticky != null && sticky.isAvailableForRouting) {
                return sticky
            }
        }

        // 2. Refresh any accounts whose quota cooldown has expired
        val now = System.currentTimeMillis()
        val available = current.filter { acc ->
            acc.id !in excludeAccountIds && (
                acc.status == AntigravityAccountStatus.HEALTHY ||
                    (acc.status == AntigravityAccountStatus.QUOTA_EXHAUSTED &&
                        acc.quotaExhaustedUntil != null && now >= acc.quotaExhaustedUntil)
            )
        }

        if (available.isEmpty()) return null

        // 3. Apply routing policy
        return when (strategy) {
            AntigravityLoadBalancingStrategy.PRIMARY_ONLY -> {
                available.firstOrNull { it.isPrimary } ?: available.first()
            }
            AntigravityLoadBalancingStrategy.ROUND_ROBIN -> {
                val idx = Math.floorMod(roundRobinIndex.getAndIncrement(), available.size)
                available[idx]
            }
            AntigravityLoadBalancingStrategy.LEAST_RECENTLY_USED -> {
                available.minByOrNull { it.lastUsedAt } ?: available.first()
            }
        }
    }

    private fun updateAccount(id: String, transform: (AntigravityAccount) -> AntigravityAccount) {
        val current = _accounts.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = current.toMutableList()
        updated[index] = transform(updated[index])
        saveAndPublish(updated)
    }

    private fun saveAndPublish(updated: List<AntigravityAccount>) {
        saveAccountsOverride?.invoke(updated) ?: preferences?.saveAntigravityAccounts(updated)
        _accounts.value = updated
    }

    private fun migrateLegacyAccountIfNeeded() {
        val prefs = preferences ?: return
        val ctx = context ?: return
        val existingAccounts = prefs.loadAntigravityAccounts()
        if (existingAccounts.isNotEmpty()) return

        val legacyToken = File(
            ctx.filesDir,
            "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token",
        )
        if (legacyToken.isFile) {
            val legacyId = "primary"
            val targetDir = File(
                ctx.filesDir,
                "runtime/ubuntu/root/.antigravity-accounts/$legacyId/.gemini/antigravity-cli",
            )
            targetDir.mkdirs()
            runCatching {
                legacyToken.copyTo(File(targetDir, "antigravity-oauth-token"), overwrite = true)
            }
            val email = prefs.antigravityAccountEmail.takeIf(String::isNotBlank)
                ?: "Primary Google Account"
            val migrated = listOf(
                AntigravityAccount(
                    id = legacyId,
                    email = email,
                    label = "Primary",
                    addedAt = System.currentTimeMillis(),
                    lastUsedAt = System.currentTimeMillis(),
                    status = AntigravityAccountStatus.HEALTHY,
                    isPrimary = true,
                ),
            )
            saveAndPublish(migrated)
        }
    }

    companion object {
        const val DEFAULT_COOLDOWN_MILLIS = 60 * 60 * 1000L // 1 hour
        const val GOOGLE_OAUTH_TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
        const val ANTIGRAVITY_CLIENT_ID = "1071006060591-tmhssin2h21lcre235vtolojh4g403ep.apps.googleusercontent.com"
        const val ANTIGRAVITY_CLIENT_SECRET = "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"
    }
}
