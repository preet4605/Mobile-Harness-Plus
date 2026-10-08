package com.jarves.mh.runtime

import com.jarves.mh.model.AttachmentPrompt
import com.jarves.mh.model.ChatAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexImageArgsTest {

    private val chatId = "3f2b8c1e-7d4a-4b6e-9a1f-0c2d3e4f5a6b"
    private val workspace = "/workspace/demo"
    private val route = CodexRoute.ChatGptLogin("gpt-5")

    private fun block(root: String, vararg files: Pair<String, String>): String =
        AttachmentPrompt.render(
            files.map { (name, mime) ->
                ChatAttachment(displayName = name, relativePath = "attachments/$chatId/$name", mimeType = mime, sizeBytes = 1)
            },
            root,
        )

    @Test
    fun imagesBecomeOneDashIFlagEachInAttachmentOrderBeforeTheStdinPrompt() {
        val prompt = "compare these\n\n" + block(
            workspace,
            "a.png" to "image/png",
            "doc.pdf" to "application/pdf",
            "b.jpeg" to "image/jpeg",
        )
        val images = CodexLaunchBuilder.imagePaths(prompt, workspace)
        assertEquals(
            listOf("$workspace/attachments/$chatId/a.png", "$workspace/attachments/$chatId/b.jpeg"),
            images,
        )
        val argv = CodexLaunchBuilder.command(route, workspace, "/tmp/out.txt", null, images)
        val first = argv.indexOf(images[0])
        assertEquals("-i", argv[first - 1])
        assertEquals("-i", argv[argv.indexOf(images[1]) - 1])
        assertEquals(images[1], argv[argv.indexOf(images[1])])
        assertEquals("-", argv.last())
        assertFalse(argv.contains("doc.pdf"))
    }

    @Test
    fun noImagesLeavesTheCommandUnchanged() {
        val plain = CodexLaunchBuilder.command(route, workspace, "/tmp/out.txt")
        assertEquals(plain, CodexLaunchBuilder.command(route, workspace, "/tmp/out.txt", null, emptyList()))
        assertFalse(plain.contains("-i"))
        assertEquals("-", plain.last())
    }

    @Test
    fun unsupportedImagesAreNotPassedAsImages() {
        val prompt = block(
            workspace,
            "icon.svg" to "image/svg+xml",
            "scan.heic" to "image/heic",
            "pic.gif" to "image/gif",
        )
        assertEquals(listOf("$workspace/attachments/$chatId/pic.gif"), CodexLaunchBuilder.imagePaths(prompt, workspace))
    }

    @Test
    fun imagesOutsideThisProjectsFolderAreNotPassed() {
        val prompt = block("/workspace/other", "elsewhere.png" to "image/png")
        assertTrue(CodexLaunchBuilder.imagePaths(prompt, workspace).isEmpty())
    }

    @Test
    fun hugeOrUnknownNamesDoNotBreakParsing() {
        val odd = "😀".repeat(150) + ".myfmt"
        val prompt = block(workspace, odd to "application/octet-stream", "x.png" to "image/png")
        assertEquals(1, CodexLaunchBuilder.imagePaths(prompt, workspace).size)
        assertEquals(2, AttachmentPrompt.parse(prompt).size)
    }
}
