package top.kagg886.cctr.backend.task

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import top.kagg886.cctr.api.modules.Question
import top.kagg886.cctr.desktop.util.PdfExportDetails
import top.kagg886.cctr.desktop.util.PdfGroupSkippedException
import top.kagg886.cctr.desktop.util.QuestionPdfResult
import top.kagg886.cctr.desktop.util.writeAtomicFile
import top.kagg886.cctr.desktop.util.writeSelectableQuestionPdf
import top.kagg886.cctr.driver.CapturedPdfAsset
import top.kagg886.cctr.util.useTempDictionarySuspend
import top.kagg886.cctr.util.zip
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun writeQuestionPdfArchive(
    archive: File,
    groups: Iterable<Pair<PdfExportDetails, List<Question>>>,
    onGroupProcessed: suspend (Int) -> Unit = {},
    reportSkipped: suspend (List<String>) -> Unit,
    renderComplex: suspend (String) -> CapturedPdfAsset,
): Int {
    val skippedIds = linkedSetOf<String>()
    val failureMessages = mutableListOf<String>()
    val warningMessages = mutableListOf<String>()
    var exportedCount = 0
    val ordered = groups.toList()
    useTempDictionarySuspend { output ->
        // Each group owns its own PdfDocument, so groups can be laid out concurrently; results are
        // merged in input order so skipped IDs keep their deterministic reporting order.
        val results = coroutineScope {
            val dispatcher = Dispatchers.IO.limitedParallelism(minOf(maxParallelGroups, ordered.size).coerceAtLeast(1))
            ordered.map { (details, group) ->
                async(dispatcher) {
                    val pdf = output.resolve(details.title).resolve(details.chapter).resolve("${details.questionType}.pdf")
                    try {
                        val result = writeSelectableQuestionPdf(pdf, details, group, renderComplex)
                        onGroupProcessed(group.size)
                        Result.success(result)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: PdfGroupSkippedException) {
                        // 整组都是不受支持的内容：保留其它分组继续出包，并把题目 ID 全部上报。
                        runCatching { onGroupProcessed(group.size) }
                        Result.success(
                            QuestionPdfResult(
                                exportedCount = 0,
                                skippedIds = group.map(Question::id),
                                failureMessages = listOf("整组未导出：${error.message}"),
                            )
                        )
                    } catch (error: Exception) {
                        // Returning the failure keeps the cause chain intact; rethrowing across a
                        // dispatcher boundary makes kotlinx.coroutines copy the exception.
                        Result.failure(
                            IllegalStateException(
                                "PDF 排版失败：${details.title} / ${details.chapter} / ${details.questionType}；${error.message}",
                                error,
                            )
                        )
                    }
                }
            }.awaitAll()
        }
        results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { throw it }
        results.forEach { result ->
            val value = result.getOrThrow()
            exportedCount += value.exportedCount
            skippedIds.addAll(value.skippedIds)
            failureMessages.addAll(value.failureMessages)
            warningMessages.addAll(value.warningMessages)
        }
        val messages = warningSummaryMessages(warningMessages) +
            skippedPdfLogMessages(skippedIds) +
            failureLogMessages(failureMessages) +
            warningLogMessages(warningMessages)
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

/**
 * Groups write to separate PDF files, so they can be laid out in parallel. The bound keeps the
 * Edge capture pool (10 drivers) and peak image memory in check on small machines.
 */
private const val maxParallelGroups = 4

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

private fun failureLogMessages(failures: List<String>): List<String> = failures.map { failure ->
    val line = "排版失败：$failure"
    if (line.length <= 200) line else line.take(199) + "…"
}

private fun warningSummaryMessages(warnings: List<String>): List<String> =
    if (warnings.isEmpty()) emptyList() else listOf("${warnings.size} 处字符无法还原，已用 � 替代")

private fun warningLogMessages(warnings: List<String>): List<String> = warnings.map { warning ->
    val line = "占位：$warning"
    if (line.length <= 200) line else line.take(199) + "…"
}
