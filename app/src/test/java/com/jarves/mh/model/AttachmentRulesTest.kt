package com.jarves.mh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentRulesTest {

    private val chatId = "3f2b8c1e-7d4a-4b6e-9a1f-0c2d3e4f5a6b"
    private val lookup: (String) -> String? = { ext ->
        mapOf("txt" to "text/plain", "png" to "image/png", "pdf" to "application/pdf")[ext]
    }

    @Test
    fun namesKeepUnicodeSpacesAndUnusualExtensions() {
        assertEquals("報告 final v2.myfmt", cleanAttachmentName("報告 final v2.myfmt"))
        assertEquals("LICENSE", cleanAttachmentName("LICENSE"))
        assertEquals("archive.tar.gz", cleanAttachmentName(" archive.tar.gz "))
        assertEquals("a|b:c?.pdf", cleanAttachmentName("a|b:c?.pdf"))
    }

    @Test
    fun onlySeparatorsAndControlCharactersAreReplaced() {
        assertEquals("a_b_c_d.txt", cleanAttachmentName("a/b\\c\u0001d.txt"))
        assertEquals("tab_here.txt", cleanAttachmentName("tab\there.txt"))
    }

    @Test
    fun dotNamesAndBlanksAreUnusable() {
        assertEquals("", cleanAttachmentName(null))
        assertEquals("", cleanAttachmentName("   "))
        assertEquals("", cleanAttachmentName("."))
        assertEquals("", cleanAttachmentName(".."))
        assertEquals("..x", cleanAttachmentName("..x"))
    }

    @Test
    fun storedNameKeepsTheNameUnlessItExceedsTheLimit() {
        assertEquals("notes.myfmt", storedAttachmentName("notes.myfmt"))
        assertEquals(".myfmt", storedAttachmentName(".myfmt"))
        val longCjk = "報".repeat(120) + ".myfmt"
        val storedCjk = storedAttachmentName(longCjk)
        assertTrue(storedCjk.toByteArray().size <= 200)
        assertTrue(storedCjk.endsWith(".myfmt"))
        val emoji = "😀".repeat(200)
        val storedEmoji = storedAttachmentName(emoji)
        assertTrue(storedEmoji.toByteArray().size <= 200)
        assertTrue(storedEmoji.isNotEmpty())
    }

    @Test
    fun providerTypeWinsWhenSpecific() {
        assertEquals("image/png", resolveAttachmentMimeType("image/png", "jpg", lookup))
        assertEquals("text/plain", resolveAttachmentMimeType("TEXT/Plain; charset=utf-8", "", lookup))
    }

    @Test
    fun genericOrMissingTypeFallsBackToExtension() {
        assertEquals("text/plain", resolveAttachmentMimeType("application/octet-stream", "TXT", lookup))
        assertEquals("text/plain", resolveAttachmentMimeType(null, "txt", lookup))
        assertEquals("application/pdf", resolveAttachmentMimeType("", "pdf", lookup))
    }

    @Test
    fun unknownOrCustomExtensionsBecomeOctetStream() {
        assertEquals("application/octet-stream", resolveAttachmentMimeType(null, "myfmt", lookup))
        assertEquals("application/octet-stream", resolveAttachmentMimeType("application/octet-stream", "", lookup))
        assertEquals("application/octet-stream", resolveAttachmentMimeType("bad type)", "x") { "not a mime" })
    }

    @Test
    fun kindDecidesTheHintWithoutNeedingAKnownType() {
        assertEquals(AttachmentKind.IMAGE, attachmentKindOf("image/webp", "webp"))
        assertEquals(AttachmentKind.TEXT, attachmentKindOf("text/x-kotlin", "kt"))
        assertEquals(AttachmentKind.TEXT, attachmentKindOf("application/octet-stream", "kt"))
        assertEquals(AttachmentKind.TEXT, attachmentKindOf("application/ld+json", "jsonld"))
        assertEquals(AttachmentKind.ARCHIVE, attachmentKindOf("application/octet-stream", "zip"))
        assertEquals(AttachmentKind.ARCHIVE, attachmentKindOf("application/vnd.android.package-archive", "apk"))
        assertEquals(AttachmentKind.BINARY, attachmentKindOf("application/pdf", "pdf"))
        assertEquals(AttachmentKind.BINARY, attachmentKindOf("application/octet-stream", "myfmt"))
    }

    @Test
    fun sizeLimitIsOneHundredMegabytes() {
        assertEquals(100L * 1024L * 1024L, MAX_ATTACHMENT_BYTES)
        assertEquals("big.bin is larger than 100 MB", attachmentTooLargeMessage("big.bin"))
    }

    @Test
    fun onlyChatAttachmentFoldersAreStorage() {
        assertTrue(isAttachmentStoragePath("attachments/$chatId/report.pdf"))
        assertTrue(isAttachmentStoragePath("attachments/default/a.txt"))
        assertFalse(isAttachmentStoragePath("attachments/notes.md"))
        assertFalse(isAttachmentStoragePath("docs/attachments/$chatId/x.png"))
        assertFalse(isAttachmentStoragePath("attachmentsX/$chatId/x"))
    }

    @Test
    fun promptBlockRoundTripsOddNamesAndHintsAtShellInspection() {
        val files = listOf(
            ChatAttachment(
                displayName = "photo (final): v2.png",
                relativePath = "attachments/$chatId/photo (final): v2.png",
                mimeType = "image/png",
                sizeBytes = 12,
            ),
            ChatAttachment(
                displayName = "data.myfmt",
                relativePath = "attachments/$chatId/data.myfmt",
                mimeType = "application/octet-stream",
                sizeBytes = 3,
            ),
        )
        val block = AttachmentPrompt.render(files, "/workspace/demo")
        assertTrue(block.contains("- data.myfmt (application/octet-stream, 3 bytes, binary)"))
        assertTrue(block.contains("Inspect them with shell tools"))
        assertEquals(
            listOf(
                AttachmentPrompt.AttachedFile(
                    "photo (final): v2.png",
                    "/workspace/demo/attachments/$chatId/photo (final): v2.png",
                    "image/png",
                    AttachmentKind.IMAGE,
                ),
                AttachmentPrompt.AttachedFile(
                    "data.myfmt",
                    "/workspace/demo/attachments/$chatId/data.myfmt",
                    "application/octet-stream",
                    AttachmentKind.BINARY,
                ),
            ),
            AttachmentPrompt.parse("Look at these\n\n$block"),
        )
    }

    @Test
    fun parseReadsOnlyTheLastBlock() {
        val earlier = "<attached_files>\n- old.png (image/png, 1 bytes, image)\n  path: /workspace/x/attachments/c/old.png\n</attached_files>"
        val current = AttachmentPrompt.render(
            listOf(ChatAttachment(displayName = "new.png", relativePath = "attachments/c/new.png", mimeType = "image/png", sizeBytes = 1)),
            "/workspace/x",
        )
        assertEquals(
            listOf("/workspace/x/attachments/c/new.png"),
            AttachmentPrompt.parse("$earlier\n$current").map { it.path },
        )
    }

    @Test
    fun parseIgnoresTextWithoutAWellFormedBlock() {
        assertTrue(AttachmentPrompt.parse("no files here").isEmpty())
        assertTrue(AttachmentPrompt.parse("<attached_files>\n- broken line\n</attached_files>").isEmpty())
        assertTrue(AttachmentPrompt.parse("<attached_files>\n- a.png (image/png, 1 bytes, image)\n").isEmpty())
    }
}
