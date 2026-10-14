package com.jarves.mh.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Controllable child used only with non-secret login fixtures. */
internal class AuthFixtureProcess(@Volatile var alive: Boolean = true, private val exit: Int = 0) : Process() {
    var destroyed = false
    private val input = ByteArrayOutputStream()
    override fun getOutputStream() = input
    override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
    override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
    override fun isAlive() = alive
    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException()
        return exit
    }
    override fun waitFor(): Int = exitValue()
    override fun destroy() { destroyed = true; alive = false }
    override fun destroyForcibly(): Process { destroy(); return this }
}
