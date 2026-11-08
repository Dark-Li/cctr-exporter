package top.kagg886.cctr.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.serialization.kotlinx.serialization
import io.ktor.util.logging.KtorSimpleLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.kagg886.cctr.api.util.BaseResponse
import top.kagg886.cctr.api.util.CCTRException
import top.kagg886.cctr.api.util.RSA
import top.kagg886.cctr.api.util.buildURLParams
import java.io.Closeable

/** Owns the cookies binding a captcha image to its login attempt. */
class CCTRLoginSession internal constructor(
    private val apiUrl: String,
    private val client: HttpClient,
) : Closeable {
    constructor() : this("https://api.cctrcloud.net/mobile", newHTTPClient())
    internal constructor(apiUrl: String) : this(apiUrl, newHTTPClient())

    suspend fun refreshCaptcha(): ByteArray {
        val response = client.get("$apiUrl/index.php") {
            parameter("act", "seccode")
            parameter("op", "makecode")
            parameter("t", System.currentTimeMillis())
            header(HttpHeaders.CacheControl, "no-cache")
        }
        diagnostic("captcha.response", "http=${response.status.value}")
        if (!response.status.isSuccess()) throw CCTRException("验证码获取失败，请点击刷新")
        return response.body<ByteArray>().also {
            if (it.isEmpty()) throw CCTRException("验证码图片为空，请点击刷新")
        }
    }

    suspend fun login(schoolNumber: String, username: String, password: String, captcha: String): CCTRUser {
        if (captcha.isBlank()) throw CCTRException("请输入验证码")
        var stage = "school"
        diagnostic("school.start")
        try {
        val schoolResponse = client.post("$apiUrl/index.php?act=login&op=getschoolData") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(buildURLParams("number" to schoolNumber))
        }
        diagnostic("school.response", "http=${schoolResponse.status.value}")
        val school = schoolResponse.body<BaseResponse>()
        school.requireSuccess("学校代号查询失败")
        val schoolId = ((school.dataOrigin as? JsonObject)?.get("ID") as? JsonPrimitive)?.contentOrNull
            ?: throw CCTRException("学校信息返回异常")

        stage = "login"
        diagnostic("login.start")
        val loginResponse = client.post("$apiUrl/index.php?act=login") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(buildURLParams(
                "username" to username,
                "password" to RSA.encrypt(password),
                "schoolid" to schoolId,
                "client" to "web",
                "captcha" to captcha.trim(),
            ))
        }
        diagnostic("login.response", "http=${loginResponse.status.value}")
        val result = loginResponse.body<BaseResponse>()
        diagnostic("login.result", "code=${result.code}")
        result.requireSuccess("登录失败，请刷新验证码后重试")
        val key = ((result.dataOrigin as? JsonObject)?.get("key") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw CCTRException("登录信息返回异常")

        diagnostic("login.success")
        return CCTRUser(schoolNumber, username, password).also {
            it.net = client
            it.key = key
        }
        } catch (error: Exception) {
            val reason = if (error is CCTRException) safeLoginDiagnostic(error.message.orEmpty(), listOf(schoolNumber, username, password, captcha)) else "response-or-transport-error"
            diagnostic("$stage.failure", "type=${error.javaClass.name} reason=$reason")
            diagnostic("$stage.stack", error.stackTrace.joinToString("\n") { "at $it" })
            throw error
        }
    }

    override fun close() = client.close()
}

private fun diagnostic(stage: String, detail: String = "") {
    if (java.lang.Boolean.getBoolean("cctr.login.debug")) {
        KtorSimpleLogger("CCTRLoginDiagnostic").info("login-debug stage=$stage $detail")
    }
}

internal fun safeLoginDiagnostic(message: String, secrets: List<String>): String {
    var safe = message
    secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach {
        safe = safe.replace(it, "[已隐藏]", ignoreCase = true)
    }
    // Keep human-readable error text; suppress ASCII identifiers, tokens and ciphertext.
    return safe.replace(Regex("[A-Za-z0-9+/=_.:@-]+"), "[标识已隐藏]").take(500)
}

private fun BaseResponse.requireSuccess(fallback: String) {
    val error = (dataOrigin as? JsonObject)?.get("error")
    val message = when (error) {
        is JsonObject -> (error["msg"] as? JsonPrimitive)?.contentOrNull
        is JsonPrimitive -> error.contentOrNull
        else -> null
    }?.takeIf { it.isNotBlank() }
    val hasError = error != null && error != JsonNull &&
        !(error is JsonPrimitive && error.contentOrNull.isNullOrBlank())
    if (code != 200 || hasError) throw CCTRException(message ?: "$fallback（服务代码：$code）")
}

private fun newHTTPClient(): HttpClient = HttpClient {
    install(HttpCookies) { storage = AcceptAllCookiesStorage() }
    // Login bodies and Cookie headers contain credentials; do not log them.
    install(ContentNegotiation) {
        val json = Json { ignoreUnknownKeys = true }
        json(json)
        serialization(ContentType.Text.Html, json)
    }
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
    }
}
