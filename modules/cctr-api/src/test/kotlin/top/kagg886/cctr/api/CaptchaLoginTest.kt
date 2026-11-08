package top.kagg886.cctr.api

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.kagg886.cctr.api.util.CCTRException
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptchaLoginTest {
    @Test
    fun `captcha cookie and entered code are used by login`() = runBlocking {
        withLoginServer { session, requests ->
            assertContentEquals(byteArrayOf(1, 2, 3), session.refreshCaptcha())
            val user = session.login("school-number", "student", "test-password", "A7b9")

            assertEquals("test-key", user.key)
            val login = requests.last()
            assertEquals("A7b9", login.form["captcha"])
            assertEquals("resolved-school", login.form["schoolid"])
            assertEquals("student", login.form["username"])
            assertEquals("web", login.form["client"])
            assertTrue(login.cookie.contains("PHPSESSID=captcha-1"))
            assertTrue(login.form.getValue("password") != "test-password")
        }
    }

    @Test
    fun `refresh replaces the challenge cookie before another attempt`() = runBlocking {
        withLoginServer { session, requests ->
            session.refreshCaptcha()
            session.refreshCaptcha()
            session.login("school-number", "student", "test-password", "A7b9")

            assertTrue(requests.last().cookie.contains("PHPSESSID=captcha-2"))
        }
    }

    @Test
    fun `blank code is rejected before sending credentials`() = runBlocking {
        withLoginServer { session, requests ->
            assertFailsWith<CCTRException> {
                session.login("school-number", "student", "test-password", " ")
            }
            assertTrue(requests.isEmpty())
        }
    }

    @Test
    fun `server captcha error remains visible and session can refresh`() = runBlocking {
        withLoginServer { session, requests ->
            session.refreshCaptcha()
            val error = assertFailsWith<CCTRException> {
                session.login("school-number", "student", "test-password", "wrong")
            }
            assertEquals("验证码错误", error.message)
            session.refreshCaptcha()
            session.login("school-number", "student", "test-password", "A7b9")
            assertTrue(requests.last().cookie.contains("PHPSESSID=captcha-2"))
        }
    }

    @Test
    fun `structured login error preserves the server message`() = runBlocking {
        withLoginServer("""{"code":400,"datas":{"error":{"code":"1001","msg":"学校编码、账号或密码错误"}}}""") { session, _ ->
            val error = assertFailsWith<CCTRException> {
                session.login("school-number", "student", "test-password", "A7b9")
            }
            assertEquals("学校编码、账号或密码错误", error.message)
        }
    }

    @Test
    fun `successful status containing error is rejected even if a key is present`() = runBlocking {
        withLoginServer("""{"code":200,"datas":{"key":"invalid-key","error":{"code":"1002","msg":"账号已被停用"}}}""") { session, _ ->
            val error = assertFailsWith<CCTRException> {
                session.login("school-number", "student", "test-password", "A7b9")
            }
            assertEquals("账号已被停用", error.message)
        }
    }

    @Test
    fun `diagnostic error text hides credentials and identifiers`() {
        val message = safeLoginDiagnostic("账号student，密码测试密码，验证码A7b9，token=secret-token；验证码输入错误", listOf("student", "测试密码", "A7b9"))
        assertTrue(message.contains("验证码输入错误"))
        listOf("student", "测试密码", "A7b9", "secret-token").forEach { assertTrue(!message.contains(it)) }
    }

    private suspend fun withLoginServer(loginResponse: String? = null, block: suspend (CCTRLoginSession, MutableList<Request>) -> Unit) {
        val requests = mutableListOf<Request>()
        var challenge = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/index.php") { exchange ->
            val query = parseForm(exchange.requestURI.rawQuery.orEmpty())
            val form = parseForm(exchange.requestBody.bufferedReader().readText())
            requests += Request(form, exchange.requestHeaders.getFirst("Cookie").orEmpty())
            val body = when {
                query["act"] == "seccode" && query["op"] == "makecode" -> {
                    challenge++
                    exchange.responseHeaders.set("Content-Type", "image/png")
                    exchange.responseHeaders.set("Set-Cookie", "PHPSESSID=captcha-$challenge; Path=/")
                    byteArrayOf(1, 2, 3)
                }
                query["op"] == "getschoolData" -> {
                    exchange.responseHeaders.set("Content-Type", "text/html")
                    """{"code":200,"datas":{"ID":"resolved-school"}}""".toByteArray()
                }
                else -> {
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    if (loginResponse != null) {
                        loginResponse.toByteArray()
                    } else if (form["captcha"] == "A7b9") {
                        """{"code":200,"datas":{"key":"test-key"}}""".toByteArray()
                    } else {
                        """{"code":400,"datas":{"error":"验证码错误"}}""".toByteArray()
                    }
                }
            }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            CCTRLoginSession("http://127.0.0.1:${server.address.port}").use { session ->
                block(session, requests)
            }
        } finally {
            server.stop(0)
        }
    }

    private fun parseForm(value: String): Map<String, String> = value.split('&')
        .filter { it.isNotBlank() }
        .associate {
            val pair = it.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }

    private data class Request(val form: Map<String, String>, val cookie: String)
}
