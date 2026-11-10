package top.kagg886.cctr.backend.task

import kotlinx.coroutines.CancellationException
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.writeAtomicFile
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import top.kagg886.cctr.driver.CapturedPdfAsset
import top.kagg886.cctr.util.useTempDictionarySuspend
import top.kagg886.cctr.util.zip
import java.io.File

internal suspend fun writeQuestionPdfArchive(
    archive: File,
    groups: Iterable<Pair<PdfExportDetails, List<Question>>>,
    onGroupProcessed: suspend (Int) -> Unit = {},
    reportSkipped: suspend (List<String>) -> Unit,
    renderComplex: suspend (String) -> CapturedPdfAsset,
): Int {
    val skippedIds = linkedSetOf<String>()
    var exportedCount = 0
    useTempDictionarySuspend { output ->
        for ((details, group) in groups) {
            val pdf = output.resolve(details.title).resolve(details.chapter).resolve("${details.questionType}.pdf")
            val result = try {
                writeSelectableQuestionPdf(pdf, details, group, renderComplex)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw IllegalStateException(
                    "PDF 排版失败：${details.title} / ${details.chapter} / ${details.questionType}；${error.message}",
                    error,
                )
            }
            exportedCount += result.exportedCount
            skippedIds.addAll(result.skippedIds)
            onGroupProcessed(group.size)
        }
        val messages = skippedPdfLogMessages(skippedIds)
        if (exportedCount > 0) {
            writeAtomicFile(archive) { temporary ->
                output.zip(temporary)
                if (messages.isNotEmpty()) reportSkipped(messages)
            }
        } else if (messages.isNotEmpty()) {
            reportSkipped(messages)
        }
    }
    check(exportedCount > 0) { "没有可导出的题目" }
    return skippedIds.size
}

internal fun skippedPdfSummary(count: Int) = "$count 道题未导出成功，已跳过"

private fun skippedPdfLogMessages(skippedIds: Set<String>): List<String> = buildList {
    if (skippedIds.isEmpty()) return@buildList
    add(skippedPdfSummary(skippedIds.size))
    val prefix = "已跳过题目 ID："
    // Loggers.message is VARCHAR(200). Keep each normal ID intact within the task log.
    val maxIdsLength = 200 - prefix.length
    var line = ""
    for (id in skippedIds.flatMap { it.chunked(maxIdsLength) }) {
        val separator = if (line.isEmpty()) "" else "、"
        if (line.length + separator.length + id.length > maxIdsLength) {
            add(prefix + line)
            line = id
        } else {
            line += separator + id
        }
    }
    if (line.isNotEmpty()) add(prefix + line)
}
