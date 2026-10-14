package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestSeccompTest {
    private fun source(name: String) = File("src/main/java/com/jarves/mh/runtime/$name").readText()

    @Test fun guestSeccompIsOnOnlyInDevBuilds() {
        val code = source("RuntimeInstaller.kt")
        assertTrue(code.contains("internal val PROOT_SECCOMP_ENABLED: Boolean = BuildConfig.DEBUG"))
        assertTrue(code.contains("if (!PROOT_SECCOMP_ENABLED) put(\"PROOT_NO_SECCOMP\", \"1\")"))
        assertFalse(code.contains("                put(\"PROOT_NO_SECCOMP\", \"1\")\n"))
    }
}
