package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidGradlePropertiesTest {
    @Test
    fun managedKeysAreReplacedAndUserLinesKept() {
        val merged = mergeManagedAndroidGradleProperties(
            listOf("org.gradle.jvmargs=-Xmx4g", "my.flag=true", " org.gradle.parallel : true"),
            "/aapt2",
        )

        assertEquals("my.flag=true", merged.first())
        assertEquals(1, merged.count { it.startsWith("org.gradle.jvmargs") })
        assertTrue("org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m -Dfile.encoding=UTF-8" in merged)
        assertTrue("org.gradle.parallel=false" in merged)
        assertTrue("android.aapt2FromMavenOverride=/aapt2" in merged)
    }

    @Test
    fun mergeIsIdempotent() {
        val once = mergeManagedAndroidGradleProperties(listOf("my.flag=true"), "/aapt2")

        assertEquals(once, mergeManagedAndroidGradleProperties(once, "/aapt2"))
    }

    @Test
    fun similarUserKeysAreNotManaged() {
        assertFalse(isManagedAndroidGradleProperty("org.gradle.jvmargsExtra=1"))
        assertFalse(isManagedAndroidGradleProperty("org.gradle.daemon.idletimeout=1000"))
        assertFalse(isManagedAndroidGradleProperty("# org.gradle.daemon=true"))
        assertTrue(isManagedAndroidGradleProperty("kotlin.compiler.execution.strategy=daemon"))
    }

    @Test
    fun buildsDisableReusableDaemonsByDefault() {
        val merged = mergeManagedAndroidGradleProperties(emptyList(), "/aapt2")

        assertTrue("org.gradle.daemon=false" in merged)
    }

    @Test
    fun existingDaemonOverridesAreRepairedWithoutRemovingOtherSettings() {
        val merged = mergeManagedAndroidGradleProperties(
            listOf(
                "org.gradle.daemon=true",
                " org.gradle.daemon : true",
                "org.gradle.daemon.idletimeout=1000",
                "my.flag=true",
            ),
            "/aapt2",
        )

        assertEquals(1, merged.count { it.trim().startsWith("org.gradle.daemon=") })
        assertTrue("org.gradle.daemon=false" in merged)
        assertFalse(" org.gradle.daemon : true" in merged)
        assertTrue("org.gradle.daemon.idletimeout=1000" in merged)
        assertTrue("my.flag=true" in merged)
        assertTrue(isManagedAndroidGradleProperty("org.gradle.daemon=false"))
    }
}
