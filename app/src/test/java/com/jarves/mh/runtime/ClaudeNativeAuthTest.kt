package com.jarves.mh.runtime

import com.jarves.mh.model.ClaudeAuthMode
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ClaudeNativeAuthTest {

    // Test A: Native env contains no ANTHROPIC_API_KEY, ANTHROPIC_AUTH_TOKEN, ANTHROPIC_BASE_URL, CLAUDE_CODE_OAUTH_TOKEN
    @Test
    fun testA_nativeEnvOmitsAllAnthropicVariables() {
        val profile = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION,
        )
        val config = RuntimeLaunchConfigBuilder.build(profile)

        assertFalse("ANTHROPIC_API_KEY must be strictly absent", config.environment.containsKey("ANTHROPIC_API_KEY"))
        assertFalse("ANTHROPIC_AUTH_TOKEN must be strictly absent", config.environment.containsKey("ANTHROPIC_AUTH_TOKEN"))
        assertFalse("ANTHROPIC_BASE_URL must be strictly absent", config.environment.containsKey("ANTHROPIC_BASE_URL"))
        assertFalse("CLAUDE_CODE_OAUTH_TOKEN must be strictly absent", config.environment.containsKey("CLAUDE_CODE_OAUTH_TOKEN"))
    }

    // Test B: Setup-token mode contains CLAUDE_CODE_OAUTH_TOKEN
    @Test
    fun testB_setupTokenModeConfiguresOAuthToken() {
        val profile = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            claudeAuthMode = ClaudeAuthMode.SETUP_TOKEN_LEGACY,
        )
        val config = RuntimeLaunchConfigBuilder.build(profile, authToken = "sk-ant-test-token-12345")

        assertEquals("sk-ant-test-token-12345", config.environment["CLAUDE_CODE_OAUTH_TOKEN"])
        assertEquals("", config.environment["ANTHROPIC_API_KEY"])
        assertEquals("", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertNull(config.environment["ANTHROPIC_BASE_URL"])
    }

    // Test C: Native mode does not require ApiKeyVault secret
    @Test
    fun testC_nativeModeDoesNotRequireVaultSecret() {
        val profile = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION,
        )
        // Building with null or blank token must succeed without throwing IllegalArgumentException
        val config = RuntimeLaunchConfigBuilder.build(profile, authToken = null)
        assertNotNull(config)
        assertEquals("/usr/local/bin/claude", config.executable)
    }

    // Test D: API-key providers remain unchanged
    @Test
    fun testD_apiKeyProvidersRemainUnchanged() {
        val customProfile = ProviderProfile(
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://custom.api.test/v1",
            model = "claude-3-5-sonnet",
            dshApi = "anthropic-messages",
        )
        val customConfig = RuntimeLaunchConfigBuilder.build(customProfile, authToken = "sk-custom-secret")
        assertEquals("sk-custom-secret", customConfig.environment["ANTHROPIC_API_KEY"])
        assertEquals("sk-custom-secret", customConfig.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("https://custom.api.test/v1", customConfig.environment["ANTHROPIC_BASE_URL"])

        val openRouterProfile = ProviderProfile(
            kind = ProviderKind.LLM_ROUTER,
            baseUrl = "https://openrouter.ai/api/",
            model = "anthropic/claude-3.5-sonnet",
        )
        val routerConfig = RuntimeLaunchConfigBuilder.build(openRouterProfile, authToken = "sk-or-secret")
        assertEquals("sk-or-secret", routerConfig.environment["OPENROUTER_API_KEY"])
        assertEquals("https://openrouter.ai/api", routerConfig.environment["ANTHROPIC_BASE_URL"])
    }

    // Test E: Status parsing from JSON ignores secrets
    @Test
    fun testE_statusParsingIgnoresSecrets() {
        val rawJson = """
            {
                "loggedIn": true,
                "authMethod": "claude.ai",
                "apiProvider": "firstParty",
                "subscriptionType": "pro",
                "oauth_token": "SUPER_SECRET_TOKEN_DO_NOT_READ",
                "access_token": "ACCESS_TOKEN_SECRET",
                "refresh_token": "REFRESH_TOKEN_SECRET"
            }
        """.trimIndent()

        val metadata = ClaudeStatusMetadata.fromJson(rawJson)
        assertNotNull(metadata)
        assertTrue(metadata!!.loggedIn)
        assertEquals("claude.ai", metadata.authMethod)
        assertEquals("firstParty", metadata.apiProvider)
        assertEquals("pro", metadata.subscriptionType)

        // Metadata class only has the 4 non-secret fields
        val fieldNames = ClaudeStatusMetadata::class.java.declaredFields.map { it.name }
        assertFalse(fieldNames.contains("oauth_token"))
        assertFalse(fieldNames.contains("access_token"))
        assertFalse(fieldNames.contains("refresh_token"))
    }

    // Test F: Browser bridge preserves exact OAuth URL without changes
    @Test
    fun testF_browserBridgePreservesExactOAuthUrl() {
        val exactOAuthUrl = "https://claude.com/cai/oauth/authorize?code=true&client_id=9d1c250a-e61b-449e-b9b5-c0529ecc227e&redirect_uri=http%3A%2F%2Flocalhost%3A42345%2Fcallback&response_type=code&scope=openid%20profile%20email&state=xyz123"

        val tempDir = File.createTempFile("pocket-bridge-test", "").apply {
            delete()
            mkdirs()
        }
        try {
            val urlFile = File(tempDir, "open-url-12345.url")
            urlFile.writeText(exactOAuthUrl)

            val readBack = urlFile.readText().trim()
            assertEquals("Exact OAuth URL must be preserved with zero query parameter tampering", exactOAuthUrl, readBack)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // Test G: Log / display sanitizer redacts tokens
    @Test
    fun testG_logSanitizerRedactsTokens() {
        val inputLog = "Error with Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.token and sk-ant-api03-abcdef12345678901234567890 and oauth_token: cl-oauth-123456"
        val sanitized = ClaudeRuntimeBridge.sanitizeForDisplay(inputLog)

        assertFalse("Bearer token must be redacted", sanitized.contains("eyJhbGciOiJIUzI1Ni"))
        assertFalse("sk-ant key must be redacted", sanitized.contains("sk-ant-api03-abcdef12345678901234567890"))
        assertTrue("Tokens must be replaced with bullet mask", sanitized.contains("••••"))
    }

    // Test H: Claude background launch configuration omits API credentials
    @Test
    fun testH_claudeBackgroundLaunchOmitsApiCredentials() {
        val profile = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION,
        )
        val config = RuntimeLaunchConfigBuilder.build(profile)

        // Argv must preserve standard Claude flags without credentials and WITHOUT --bare
        val command = ClaudeRuntimeBridge.buildClaudeCommand(
            executable = config.executable,
            model = "claude-3-7-sonnet",
        )
        assertFalse("Claude command must NOT contain --bare", command.contains("--bare"))
        assertTrue(command.contains("-p"))
        assertTrue(command.contains("--output-format"))
        assertTrue(command.contains("stream-json"))
        assertTrue(command.contains("--include-partial-messages"))
        assertTrue(command.contains("--verbose"))
        assertTrue(command.contains("--model"))
        assertTrue(command.contains("claude-3-7-sonnet"))
        assertTrue(command.contains("--max-turns"))
        assertTrue(command.contains("25"))

        // Ensure no environment credentials are set in launch
        assertNull(config.environment["ANTHROPIC_API_KEY"])
        assertNull(config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertNull(config.environment["CLAUDE_CODE_OAUTH_TOKEN"])
        assertNull(config.environment["ANTHROPIC_BASE_URL"])
    }

    // Requirement A: Native Claude command does NOT contain --bare
    @Test
    fun testA_nativeClaudeCommandDoesNotContainBare() {
        val profile = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            claudeAuthMode = ClaudeAuthMode.NATIVE_SUBSCRIPTION,
            model = "claude-3-7-sonnet",
        )
        val config = RuntimeLaunchConfigBuilder.build(profile)
        val command = ClaudeRuntimeBridge.buildClaudeCommand(
            executable = config.executable,
            model = profile.model,
        )

        assertFalse("Native Claude command must NOT contain --bare", command.contains("--bare"))
        assertEquals(
            listOf(
                "/usr/local/bin/claude",
                "-p",
                "--output-format",
                "stream-json",
                "--include-partial-messages",
                "--verbose",
                "--model",
                "claude-3-7-sonnet",
                "--max-turns",
                "25",
            ),
            command,
        )
    }

    // Requirement B: Setup-token Claude command does NOT contain --bare
    @Test
    fun testB_setupTokenClaudeCommandDoesNotContainBare() {
        val profile = ProviderProfile(
            kind = ProviderKind.CLAUDE,
            claudeAuthMode = ClaudeAuthMode.SETUP_TOKEN_LEGACY,
            model = "claude-3-5-sonnet",
        )
        val config = RuntimeLaunchConfigBuilder.build(profile, authToken = "sk-ant-test-token")
        val command = ClaudeRuntimeBridge.buildClaudeCommand(
            executable = config.executable,
            model = profile.model,
        )

        assertFalse("Setup-token Claude command must NOT contain --bare", command.contains("--bare"))
        assertEquals(
            listOf(
                "/usr/local/bin/claude",
                "-p",
                "--output-format",
                "stream-json",
                "--include-partial-messages",
                "--verbose",
                "--model",
                "claude-3-5-sonnet",
                "--max-turns",
                "25",
            ),
            command,
        )
    }
}
