package top.kagg886.cctr.backend.util

import io.ktor.util.logging.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.kagg886.cctr.backend.dao.Task
import top.kagg886.cctr.backend.service.LoggerService
import top.kagg886.cctr.backend.dao.Loggers
import top.kagg886.cctr.backend.dao.Logger
import java.time.LocalDateTime

private val logger = KtorSimpleLogger("TaskLogger")
private val scope = CoroutineScope(Dispatchers.IO + CoroutineExceptionHandler { _, error ->
    logger.error("TaskLogger 日志入库失败：${error.message}", error)
})

fun Task.log(level: Loggers.LoggerLevel, msg: String) {
    writeLogLine(level, msg)
    scope.launch { saveLog(level, msg) }
}

/** 失败原因必须先落库再更新任务状态，否则进程退出或写库异常时界面只能看到进度。 */
internal suspend fun Task.logAndSave(level: Loggers.LoggerLevel, msg: String) {
    writeLogLine(level, msg)
    saveLog(level, msg)
}

private fun Task.writeLogLine(level: Loggers.LoggerLevel, msg: String) {
    when(level) {
        Loggers.LoggerLevel.INFO -> logger.info("[Task:${id}]: $msg")
        Loggers.LoggerLevel.WARN -> logger.warn("[Task:${id}]: $msg")
        Loggers.LoggerLevel.ERROR -> logger.error("[Task:${id}]: $msg")
    }
}

private suspend fun Task.saveLog(level: Loggers.LoggerLevel, msg: String) {
    try {
        LoggerService.insert(logEntry(level, msg))
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        logger.error("[Task:$id]: 日志入库失败：${error.message}", error)
    }
}

internal suspend fun Task.warnAndSave(messages: List<String>) {
    LoggerService.insertAll(messages.map { logEntry(Loggers.LoggerLevel.WARN, it) })
    messages.forEach { logger.warn("[Task:$id]: $it") }
}

private fun Task.logEntry(level: Loggers.LoggerLevel, msg: String) =
    Logger(id = -1, taskId = id, level = level, message = msg, createTime = LocalDateTime.now())

fun Task.info(msg:String) = log(Loggers.LoggerLevel.INFO,msg)
fun Task.warn(msg:String) = log(Loggers.LoggerLevel.WARN,msg)
fun Task.error(msg:String) = log(Loggers.LoggerLevel.ERROR,msg)
