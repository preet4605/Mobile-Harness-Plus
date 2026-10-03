package com.jarves.mh.ui

import com.jarves.mh.data.AppPreferences
import com.jarves.mh.model.ActivityItem
import com.jarves.mh.model.ChatAttachment
import com.jarves.mh.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

class ChatMessageIdentityRegressionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val cleverKalamDuplicateId = "interrupted-ed075c74-0576-459f-afd8-89a0f3fbad24"

    /**
     * Builds a regression fixture reproducing the Clever Kalam transcript corruption pattern:
     * exactly 5 duplicate synthetic messages with ID "interrupted-ed075c74-0576-459f-afd8-89a0f3fbad24"
     * interspersed among user and assistant messages, with preserved workItems and metadata.
     */
    private fun createCleverKalamCorruptionFixture(): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()
        val baseTime = Instant.parse("2026-09-26T05:00:00Z")

        // 1. Initial user request
        messages.add(
            ChatMessage(
                id = "msg-user-1",
                fromUser = true,
                text = "Build Mobile Harness online debug APK",
                createdAt = baseTime,
            )
        )

        // 2. Assistant response
        messages.add(
            ChatMessage(
                id = "msg-assistant-1",
                fromUser = false,
                text = "Starting the build and test process...",
                createdAt = baseTime.plusSeconds(10),
            )
        )

        // 3. First interrupted message (Index 1143 in Clever Kalam pattern)
        messages.add(
            ChatMessage(
                id = cleverKalamDuplicateId,
                fromUser = false,
                text = "",
                createdAt = Instant.parse("2026-09-26T05:55:07.952633Z"),
                workItems = listOf(
                    ActivityItem("Task interrupted", "The agent process stopped before reporting completion."),
                    ActivityItem("git status", "On branch master", isComplete = true, isCommand = true),
                ),
                workedMillis = 3500L,
            )
        )

        // 4. Intermediate user message
        messages.add(
            ChatMessage(
                id = "msg-user-2",
                fromUser = true,
                text = "Continue",
                createdAt = baseTime.plusSeconds(60),
            )
        )

        // 5. Second interrupted message (Index 1145)
        messages.add(
            ChatMessage(
                id = cleverKalamDuplicateId,
                fromUser = false,
                text = "",
                createdAt = Instant.parse("2026-09-26T05:56:15.727154Z"),
                workItems = listOf(
                    ActivityItem("Task interrupted", "The agent process stopped before reporting completion."),
                ),
                workedMillis = 4200L,
            )
        )

        // 6. Third interrupted message (Index 1147)
        messages.add(
            ChatMessage(
                id = cleverKalamDuplicateId,
                fromUser = false,
                text = "",
                createdAt = Instant.parse("2026-09-26T05:58:21.889041Z"),
                workItems = listOf(
                    ActivityItem("Task interrupted", "The agent process stopped before reporting completion."),
                ),
                workedMillis = 5100L,
            )
        )

        // 7. Later user message
        messages.add(
            ChatMessage(
                id = "msg-user-3",
                fromUser = true,
                text = "Resume build",
                createdAt = Instant.parse("2026-10-02T11:00:00Z"),
            )
        )

        // 8. Fourth interrupted message (Index 1947)
        messages.add(
            ChatMessage(
                id = cleverKalamDuplicateId,
                fromUser = false,
                text = "",
                createdAt = Instant.parse("2026-10-02T11:12:16.906829Z"),
                workItems = listOf(
                    ActivityItem("Task interrupted", "The agent process stopped before reporting completion."),
                    ActivityItem("gradle assembleOnlineDebug", "Running assemble", isComplete = true, isCommand = true),
                ),
                workedMillis = 15000L,
            )
        )

        // 9. Fifth interrupted message (Index 1953)
        messages.add(
            ChatMessage(
                id = cleverKalamDuplicateId,
                fromUser = false,
                text = "",
                createdAt = Instant.parse("2026-10-02T14:11:58.710737Z"),
                workItems = listOf(
                    ActivityItem("Task interrupted", "The agent process stopped before reporting completion."),
                ),
                workedMillis = 8000L,
            )
        )

        return messages
    }

    @Test
    fun repeatedInterruptedMessageCreationProducesUniqueIds() {
        val createdCount = 100
        val liveItems = listOf(
            ActivityItem("Compiling Kotlin", "compiling sources", isComplete = true),
            ActivityItem("Executing tests", "running test suite", isComplete = false),
        )

        val messages = (1..createdCount).map {
            MainViewModel.createInterruptedMessage(
                startedAtMillis = System.currentTimeMillis() - 5000L,
                liveItems = liveItems,
            )
        }

        assertEquals(createdCount, messages.size)

        // 1. Verify every created ID is unique
        val uniqueIds = messages.map { it.id }.toSet()
        assertEquals("Every interrupted message must receive a unique ID", createdCount, uniqueIds.size)

        // 2. Verify all IDs follow canonical interrupted naming pattern with valid UUID
        for (msg in messages) {
            assertTrue("ID must start with interrupted- prefix: ${msg.id}", msg.id.startsWith("interrupted-"))
            val uuidPart = msg.id.removePrefix("interrupted-")
            val parsedUuid = UUID.fromString(uuidPart)
            assertNotNull(parsedUuid)
            assertFalse(msg.fromUser)
            assertTrue(msg.workItems.any { it.title == "Task interrupted" })
        }
    }

    @Test
    fun duplicatePersistedTranscriptIdsAreRepaired() {
        val fixture = createCleverKalamCorruptionFixture()
        assertEquals(9, fixture.size)

        // Count occurrences of the corrupted ID before repair
        val duplicateCountBefore = fixture.count { it.id == cleverKalamDuplicateId }
        assertEquals(5, duplicateCountBefore)

        val (repaired, wasRepaired) = AppPreferences.repairDuplicateMessageIds(fixture)

        assertTrue("Repair must indicate modifications were performed", wasRepaired)
        assertEquals("Total message count must remain unchanged", fixture.size, repaired.size)

        // Verify all repaired IDs are 100% distinct
        val uniqueIds = repaired.map { it.id }.toSet()
        assertEquals("All repaired message IDs must be unique", repaired.size, uniqueIds.size)

        // The first occurrence must preserve the original ID
        assertEquals(cleverKalamDuplicateId, repaired[2].id)

        // Subsequent occurrences must be deterministically disambiguated
        assertEquals("$cleverKalamDuplicateId-1", repaired[4].id)
        assertEquals("$cleverKalamDuplicateId-2", repaired[5].id)
        assertEquals("$cleverKalamDuplicateId-3", repaired[7].id)
        assertEquals("$cleverKalamDuplicateId-4", repaired[8].id)
    }

    @Test
    fun repairedIdsRemainStableAcrossReload() {
        val fixture = createCleverKalamCorruptionFixture()

        // 1. In-memory stability: running repair multiple times on the same input
        val (firstPass, firstModified) = AppPreferences.repairDuplicateMessageIds(fixture)
        assertTrue(firstModified)

        val (secondPass, secondModified) = AppPreferences.repairDuplicateMessageIds(firstPass)
        assertFalse("Second pass on repaired data must not modify anything", secondModified)
        assertEquals(firstPass.map { it.id }, secondPass.map { it.id })

        val (thirdPassFromRaw, _) = AppPreferences.repairDuplicateMessageIds(fixture)
        assertEquals("Repeated repair of raw corrupted fixture must produce identical IDs", firstPass.map { it.id }, thirdPassFromRaw.map { it.id })

        // 2. On-disk persistence stability via AppPreferences
        val chatsBaseDir = tempFolder.newFolder("chats")
        val preferences = AppPreferences(baseChatsDir = chatsBaseDir)

        val projectId = "fa51b2cf-9da2-4e05-9eaf-f643aae08eca"
        val chatId = "ed075c74-0576-459f-afd8-89a0f3fbad24"
        val projectFolder = File(chatsBaseDir, projectId).also { it.mkdirs() }
        val chatFile = File(projectFolder, "$chatId.json")

        // Write the raw corrupted JSON directly to disk simulating Clever Kalam's existing corrupted file
        val rawJsonArray = JSONArray()
        fixture.forEach { m ->
            rawJsonArray.put(JSONObject().apply {
                put("id", m.id)
                put("fromUser", m.fromUser)
                put("text", m.text)
                put("createdAt", m.createdAt.toString())
                put("workedMillis", m.workedMillis)
                put("workItems", JSONArray().apply {
                    m.workItems.forEach { item ->
                        put(JSONObject().apply {
                            put("title", item.title)
                            put("detail", item.detail)
                            put("isComplete", item.isComplete)
                            put("isCommand", item.isCommand)
                        })
                    }
                })
            })
        }
        chatFile.writeText(rawJsonArray.toString())

        // First load: detects duplicates, repairs them, and persists back to disk
        val loadedFirst = preferences.loadMessages(projectId, chatId)
        assertEquals(fixture.size, loadedFirst.size)
        assertEquals(fixture.size, loadedFirst.map { it.id }.toSet().size)

        // Second load: loads the persisted repaired file from disk
        val loadedSecond = preferences.loadMessages(projectId, chatId)
        assertEquals(loadedFirst.size, loadedSecond.size)
        assertEquals(
            "Repaired IDs must be completely stable across disk reload",
            loadedFirst.map { it.id },
            loadedSecond.map { it.id },
        )

        // Third load: verify raw messages from file have no duplicates
        val rawFromFile = preferences.readRawLegacyMessages(chatFile)
        assertEquals(
            "Persisted file on disk must now contain repaired unique IDs",
            loadedFirst.map { it.id },
            rawFromFile.map { it.id },
        )
    }

    @Test
    fun allOriginalMessagesContentAndWorkItemsArePreserved() {
        val fixture = createCleverKalamCorruptionFixture()
        val (repaired, _) = AppPreferences.repairDuplicateMessageIds(fixture)

        assertEquals("Message count must be strictly preserved", fixture.size, repaired.size)

        for (i in fixture.indices) {
            val original = fixture[i]
            val fixed = repaired[i]

            // ID may be modified on duplicates, but must never be blank
            assertTrue(fixed.id.isNotBlank())
            if (i == 2 || i < 2 || i == 3 || i == 6) {
                // Non-duplicates and first duplicate keep their exact ID
                assertEquals(original.id, fixed.id)
            } else {
                assertTrue(fixed.id.startsWith(original.id))
            }

            // All content must be 100% preserved
            assertEquals("fromUser preserved at index $i", original.fromUser, fixed.fromUser)
            assertEquals("text preserved at index $i", original.text, fixed.text)
            assertEquals("createdAt preserved at index $i", original.createdAt, fixed.createdAt)
            assertEquals("workedMillis preserved at index $i", original.workedMillis, fixed.workedMillis)
            assertEquals("activeSkill preserved at index $i", original.activeSkill, fixed.activeSkill)
            assertEquals("attachments preserved at index $i", original.attachments, fixed.attachments)

            // WorkItems must be completely intact
            assertEquals("workItems count preserved at index $i", original.workItems.size, fixed.workItems.size)
            for (w in original.workItems.indices) {
                assertEquals(original.workItems[w].title, fixed.workItems[w].title)
                assertEquals(original.workItems[w].detail, fixed.workItems[w].detail)
                assertEquals(original.workItems[w].isComplete, fixed.workItems[w].isComplete)
                assertEquals(original.workItems[w].isCommand, fixed.workItems[w].isCommand)
            }
        }
    }

    @Test
    fun chatTabCanRenderPreviouslyCorruptedTranscriptWithoutDuplicateKeyCrash() {
        val corruptedFixture = createCleverKalamCorruptionFixture()

        // 1. Verify that raw un-sanitized list would fail LazyColumn uniqueness invariant
        val rawKeys = corruptedFixture.map { it.id }
        assertNotEquals(
            "Raw fixture contains duplicate keys which would crash LazyColumn",
            rawKeys.size,
            rawKeys.toSet().size,
        )

        // Simulate Compose LazyColumn's key uniqueness validation
        fun simulateLazyColumnKeyValidation(keys: List<Any>): Boolean {
            val seen = HashSet<Any>(keys.size)
            for (key in keys) {
                if (!seen.add(key)) {
                    throw IllegalArgumentException("Key \"$key\" was already used. If you are using LazyColumn/Row please make sure you provide a unique key for each item.")
                }
            }
            return true
        }

        // Verify that the simulated Compose validator throws on the raw corrupted fixture
        var threwOnRaw = false
        try {
            simulateLazyColumnKeyValidation(rawKeys)
        } catch (e: IllegalArgumentException) {
            threwOnRaw = true
            assertTrue(e.message!!.contains("Key \"$cleverKalamDuplicateId\" was already used"))
        }
        assertTrue("Raw corrupted fixture must trigger the duplicate key exception", threwOnRaw)

        // 2. Run the ChatTab sanitization defensive guard
        val safeMessages = sanitizeChatTabMessages(corruptedFixture)

        // 3. Verify that safeMessages can be rendered by LazyColumn without throwing
        val safeKeys = safeMessages.map { it.id }
        val renderedWithoutCrash = simulateLazyColumnKeyValidation(safeKeys)
        assertTrue("ChatTab must successfully render safe messages without duplicate key exception", renderedWithoutCrash)
        assertEquals(corruptedFixture.size, safeMessages.size)
        assertEquals(safeKeys.size, safeKeys.toSet().size)
    }

    private fun assertNotNull(obj: Any?) {
        assertTrue(obj != null)
    }
}
