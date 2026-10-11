package com.jarves.mh.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Repairs only runtime-managed default certificate lookup paths. */
internal object RuntimeCertificates {
    fun repair(rootfs: File): Boolean = runCatching {
        val root = rootfs.canonicalFile.toPath()
        val bundle = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
        if (!bundle.isFile || bundle.length() == 0L || !bundle.canonicalFile.toPath().startsWith(root)) return false
        val directory = File(rootfs, "usr/lib/ssl")
        if (!directory.canonicalFile.toPath().startsWith(root)) return false
        check(directory.isDirectory || directory.mkdirs())
        mapOf("cert.pem" to bundle, "certs" to bundle.parentFile!!).forEach { (name, target) ->
            val path = File(directory, name).toPath()
            // An existing custom file or link belongs to the caller.
            if (!Files.exists(path, NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(path, directory.toPath().relativize(target.toPath()))
            }
        }
        File(directory, "cert.pem").isFile && File(directory, "certs").isDirectory
    }.getOrDefault(false)
}
