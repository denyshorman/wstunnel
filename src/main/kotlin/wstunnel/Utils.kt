package wstunnel

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.jvm.optionals.getOrNull

fun compiledBinaryPath(): Path? {
    System.getenv("WSTUNNEL_BINARY_PATH")?.let { pathStr ->
        val path = Paths.get(pathStr)
        if (Files.isRegularFile(path)) return path
    }

    if (System.getProperty("org.graalvm.nativeimage.imagecode") == "runtime") {
        System.getProperty("org.graalvm.nativeimage.exe")?.let { exePath ->
            return Paths.get(exePath)
        }
    }

    try {
        val location = object {}.javaClass.enclosingClass?.protectionDomain?.codeSource?.location
            ?: Server::class.java.protectionDomain.codeSource?.location

        if (location != null && location.protocol == "file") {
            val path = Paths.get(location.toURI())
            if (Files.isRegularFile(path)) return path
        }
    } catch (_: Exception) {
    }

    ProcessHandle.current().info().command().getOrNull()?.let { cmd ->
        val path = Paths.get(cmd)
        val name = path.fileName.toString().lowercase()
        val isJava = name == "java" || name == "java.exe" || name == "javaw.exe"

        if (!isJava && Files.isRegularFile(path)) {
            return path
        }
    }

    return null
}
