package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelSlotTest {

    @Test
    fun antigravityAlwaysChangesItsOwnModel() {
        assertEquals(ModelSlot.ANTIGRAVITY, modelSlotFor(AgentKind.ANTIGRAVITY, ProviderKind.ANTIGRAVITY_SERVER))
    }

    @Test
    fun claudeAccountLoginChangesTheSubscriptionModel() {
        assertEquals(ModelSlot.CLAUDE_SUBSCRIPTION, modelSlotFor(AgentKind.CLAUDE_CODE, ProviderKind.CLAUDE))
    }

    @Test
    fun claudeWithAnotherProviderChangesThatProvidersModel() {
        assertEquals(ModelSlot.PROVIDER, modelSlotFor(AgentKind.CLAUDE_CODE, ProviderKind.ANTHROPIC))
        assertEquals(ModelSlot.PROVIDER, modelSlotFor(AgentKind.CLAUDE_CODE, ProviderKind.CUSTOM))
    }

    @Test
    fun deepSeekAndCodexChangeTheModelOnTheirProvider() {
        assertEquals(ModelSlot.PROVIDER, modelSlotFor(AgentKind.DEEPSEEK_HARNESS, ProviderKind.DEEPSEEK))
        assertEquals(ModelSlot.PROVIDER, modelSlotFor(AgentKind.CODEX, ProviderKind.CHATGPT))
        assertEquals(ModelSlot.PROVIDER, modelSlotFor(AgentKind.CODEX, ProviderKind.CUSTOM))
    }
}
