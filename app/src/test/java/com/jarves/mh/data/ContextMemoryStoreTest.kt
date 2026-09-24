package com.jarves.mh.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContextMemoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var store: ContextMemoryStore

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("test_memory")
        store = ContextMemoryStore(baseDir)
    }

    @Test
    fun `load on non-existent project returns empty memory`() {
        val mem = store.load("project-1")
        assertEquals("project-1", mem.projectId)
        assertTrue(mem.entries.isEmpty())
    }

    @Test
    fun `upsert creates entry and persists to disk`() {
        val mem = store.upsert("p1", "project-language", "Kotlin", MemorySource.AUTO)
        assertEquals(1, mem.entries.size)
        assertEquals("project-language", mem.entries[0].key)
        assertEquals("Kotlin", mem.entries[0].value)
        assertEquals(MemorySource.AUTO, mem.entries[0].source)

        // Reload from disk
        val reloaded = store.load("p1")
        assertEquals(1, reloaded.entries.size)
        assertEquals("project-language", reloaded.entries[0].key)
        assertEquals("Kotlin", reloaded.entries[0].value)
    }

    @Test
    fun `upsert updates existing key case-insensitively`() {
        store.upsert("p1", "project-language", "Java", MemorySource.AUTO)
        val updated = store.upsert("p1", "PROJECT-LANGUAGE", "Kotlin", MemorySource.AUTO)

        assertEquals(1, updated.entries.size)
        assertEquals("project-language", updated.entries[0].key)
        assertEquals("Kotlin", updated.entries[0].value)
    }

    @Test
    fun `user source takes precedence over auto updates`() {
        store.upsert("p1", "project-language", "Kotlin", MemorySource.USER)
        val afterAuto = store.upsert("p1", "project-language", "Java", MemorySource.AUTO)

        assertEquals(1, afterAuto.entries.size)
        assertEquals("Java", afterAuto.entries[0].value)
        assertEquals(MemorySource.USER, afterAuto.entries[0].source)
    }

    @Test
    fun `delete removes specific entry`() {
        val m1 = store.upsert("p1", "k1", "v1", MemorySource.AUTO)
        val m2 = store.upsert("p1", "k2", "v2", MemorySource.USER)
        assertEquals(2, m2.entries.size)

        val entry1Id = m1.entries.first { it.key == "k1" }.id
        val afterDelete = store.delete("p1", entry1Id)

        assertEquals(1, afterDelete.entries.size)
        assertEquals("k2", afterDelete.entries[0].key)
    }

    @Test
    fun `clear removes all entries`() {
        store.upsert("p1", "k1", "v1", MemorySource.AUTO)
        store.upsert("p1", "k2", "v2", MemorySource.USER)

        val cleared = store.clear("p1")
        assertTrue(cleared.entries.isEmpty())

        val reloaded = store.load("p1")
        assertTrue(reloaded.entries.isEmpty())
    }

    @Test
    fun `clearAuto removes only auto entries`() {
        store.upsert("p1", "auto-k", "auto-v", MemorySource.AUTO)
        store.upsert("p1", "user-k", "user-v", MemorySource.USER)

        val cleared = store.clearAuto("p1")
        assertEquals(1, cleared.entries.size)
        assertEquals("user-k", cleared.entries[0].key)
        assertEquals(MemorySource.USER, cleared.entries[0].source)
    }

    @Test
    fun `eviction policy evicts oldest AUTO entry when exceeding 50 entries`() {
        // Insert 50 entries
        for (i in 1..50) {
            store.upsert("p1", "key-$i", "val-$i", MemorySource.AUTO)
            Thread.sleep(2)
        }
        val full = store.load("p1")
        assertEquals(50, full.entries.size)

        // Insert 51st entry
        val after51 = store.upsert("p1", "key-51", "val-51", MemorySource.AUTO)
        assertEquals(50, after51.entries.size)
        // key-1 was the oldest and should be evicted
        assertFalse(after51.entries.any { it.key == "key-1" })
        assertTrue(after51.entries.any { it.key == "key-51" })
    }

    @Test
    fun `renderMemoryBlock renders proper XML tag and entries`() {
        val emptyMem = ContextMemory("p1")
        assertEquals("", renderMemoryBlock(emptyMem))

        val mem = ContextMemory(
            projectId = "p1",
            entries = listOf(
                MemoryEntry(key = "project-language", value = "Kotlin", source = MemorySource.AUTO),
                MemoryEntry(key = "ui-framework", value = "Compose", source = MemorySource.USER),
            ),
        )
        val rendered = renderMemoryBlock(mem)
        assertTrue(rendered.startsWith("<persistent_memory>"))
        assertTrue(rendered.contains("- project-language: Kotlin"))
        assertTrue(rendered.contains("- ui-framework: Compose"))
        assertTrue(rendered.trimEnd().endsWith("</persistent_memory>"))
    }
}
