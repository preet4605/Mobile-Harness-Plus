package com.jarves.mh.mods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModModuleSourceTest {

    @Test
    fun `exports become assignments and line numbers stay the mod's own`() {
        val wrapped = ModModuleSource.wrap(
            """
            export async function register(on, options) {}
            export const helper = 1, other = 2
            export class Thing {}
            export default function fallback() {}
            const a = 1
            export { a, a as b }
            """.trimIndent(),
        )
        assertTrue(wrapped.startsWith("__mh.define(function (exports, module) { 'use strict'; async function register(on, options) {}\n"))
        assertTrue(wrapped, "\nconst helper = 1, other = 2\n" in wrapped)
        assertTrue(wrapped, "\nexports.default = function fallback() {}\n" in wrapped)
        assertTrue(wrapped, "exports.a = a; exports.b = a;" in wrapped)
        assertTrue(wrapped, "exports.register = register;exports.helper = helper;exports.Thing = Thing;" in wrapped)
        assertFalse(wrapped.contains("export "))
        assertTrue(wrapped.endsWith("\n});\n'ok'"))
    }

    @Test
    fun `imports are refused with their line`() {
        val error = runCatching { ModModuleSource.wrap("// mod\nimport { x } from './x.js'\n") }.exceptionOrNull()
        assertEquals("Line 2: a mod can't import other files. Put all of its code in the hooks module.", error?.message)
        // A dynamic import() is left alone; the engine refuses it when it runs.
        assertTrue(ModModuleSource.wrap("const later = () => import('./x.js')").contains("import('./x.js')"))
    }
}
