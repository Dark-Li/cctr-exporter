package top.kagg886.cctr.desktop.util

import java.io.File

val root_file = File(System.getProperty("user.home")).resolve("cctr-desktop").apply {
    if (!exists()) {
        parentFile.mkdirs()
    }
}

val root_driver_file = root_file.resolve("driver").apply {
    if (!exists()) {
        parentFile.mkdirs()
    }
}

val root_config_file = root_file.resolve("config").apply {
    if (!exists()) {
        parentFile.mkdirs()
    }
}

fun List<File>.convertToPDF(file: File): Boolean =
    convertToPDF(file, PdfExportDetails(file.nameWithoutExtension))
