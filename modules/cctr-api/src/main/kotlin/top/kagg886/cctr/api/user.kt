package top.kagg886.cctr.api

import io.ktor.client.HttpClient
import java.io.Closeable

data class CCTRUser internal constructor(
    val schoolId: String,
    val userName: String,
    val passWord: String,
) : Closeable {
    internal lateinit var net: HttpClient
    internal lateinit var key: String

    override fun close() = net.close()

    companion object {
        suspend fun newCCTRUser(block: CCTRUserBuilder.() -> Unit): CCTRUser {
            val config = CCTRUserBuilder().apply(block)
            val session = config.loginSession ?: CCTRLoginSession()
            try {
                return session.login(config.schoolId, config.username, config.password, config.captcha)
            } catch (error: Throwable) {
                if (config.loginSession == null) session.close()
                throw error
            }
        }
    }
}

class CCTRUserBuilder {
    lateinit var schoolId: String
    lateinit var username: String
    lateinit var password: String
    var captcha: String = ""
    var loginSession: CCTRLoginSession? = null
}
