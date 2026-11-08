package top.kagg886.cctr.api

import top.kagg886.cctr.api.util.RSA
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

class PasswordEncryptionTest {
    @Test
    fun `password encryption key matches current official login contract`() {
        // Independent fingerprint of exam_public_key from the official config.js,
        // verified on 2026-11-08. A stale key makes every valid password fail login.
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(RSA.publicKey.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("dc47a15d3a0a2ec7af0f0259e4711d7953285eb187fbb5df4da4e11a3be1ee62", fingerprint)
    }
}
