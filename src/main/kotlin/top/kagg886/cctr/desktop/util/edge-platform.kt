package top.kagg886.cctr.desktop.util

import top.kagg886.cctr.driver.execWithArgs
import java.io.File
import java.lang.UnsupportedOperationException


enum class Platform {
    WINDOWS, LINUX
}

val os by lazy {
    with(System.getProperty("os.name").lowercase()) {
        if (this.contains("windows")) {
            return@with Platform.WINDOWS
        }
        if (this.contains("linux")) {
            return@with Platform.LINUX
        }
        throw UnsupportedOperationException("$this is not supported")
    }
}

fun getEdgeDownloadURL(version: String): String {
    val platformStr = when (os) {
        Platform.WINDOWS -> "win64"
        Platform.LINUX -> "linux64"
    }
    return "https://msedgedriver.microsoft.com/$version/edgedriver_$platformStr.zip"
}

private val edgeVersionRegex = Regex("""\d+\.\d+\.\d+\.\d+""")

fun getInstalledEdgeVersion(binary: String): String? = when (os) {
    Platform.WINDOWS -> getInstalledEdgeVersionOnWindows(binary)
    Platform.LINUX -> getInstalledEdgeVersionOnLinux(binary)
}

private fun getInstalledEdgeVersionOnWindows(binary: String): String? {
    val binaryFile = File(binary)
    if (binaryFile.exists()) {
        val escapedPath = binaryFile.absolutePath.replace("'", "''")
        val command = "(Get-Item -LiteralPath '$escapedPath').VersionInfo.ProductVersion"
        runCatching {
            execWithArgs(arrayOf("powershell", "-NoProfile", "-Command", command))
        }.getOrNull()?.let { output ->
            edgeVersionRegex.find(output)?.value?.let { return it }
        }
    }

    listOf(
        arrayOf("reg", "query", "HKCU\\Software\\Microsoft\\Edge\\BLBeacon", "/v", "version"),
        arrayOf("reg", "query", "HKLM\\Software\\Microsoft\\Edge\\BLBeacon", "/v", "version"),
        arrayOf("reg", "query", "HKLM\\Software\\WOW6432Node\\Microsoft\\Edge\\BLBeacon", "/v", "version"),
    ).forEach { command ->
        runCatching { execWithArgs(command) }.getOrNull()?.let { output ->
            edgeVersionRegex.find(output)?.value?.let { return it }
        }
    }

    return null
}

private fun getInstalledEdgeVersionOnLinux(binary: String): String? {
    if (binary != "unknown") {
        runCatching { execWithArgs(arrayOf(binary, "--version")) }.getOrNull()?.let { output ->
            edgeVersionRegex.find(output)?.value?.let { return it }
        }
    }

    listOf(
        arrayOf("microsoft-edge-stable", "--version"),
        arrayOf("microsoft-edge-beta", "--version"),
        arrayOf("microsoft-edge-dev", "--version"),
        arrayOf("microsoft-edge", "--version"),
    ).forEach { command ->
        runCatching { execWithArgs(command) }.getOrNull()?.let { output ->
            edgeVersionRegex.find(output)?.value?.let { return it }
        }
    }

    return null
}

val msedgedriverName by lazy {
    "msedgedriver".plus(if (os == Platform.WINDOWS) ".exe" else "")
}

fun getEdgeDriverFile(version: String): File = root_driver_file.resolve("${os}_${version}")

fun checkEdgeDriverExists(version: String): Boolean = getEdgeDriverFile(version).exists()
