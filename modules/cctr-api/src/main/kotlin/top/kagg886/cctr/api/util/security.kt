package top.kagg886.cctr.api.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.*
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher


object RSA {
    //source from https://www.cctrcloud.net/exam/lib/config.js
    private const val PUB_KEY =
        """MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDO3r40irpueBqKoCSh01YG8qhXXH10pgZje98q7ZvjOWad/da8Eejrbs3uyNOEwYmFJckUA8X0MiGOr3Mlv1VAXTA9T9YxbBObkLNUMj2VWLbjgZNiDzZwqeprLVvIbQSLSEUseVf/EmdCeEgMOzxnahsKKIs7u+iN13xPFBY0kwIDAQAB"""

    internal val publicKey = KeyFactory.getInstance("RSA").generatePublic(
        X509EncodedKeySpec(Base64.getDecoder().decode(PUB_KEY))
    )

    private var cipher: Cipher = Cipher.getInstance("RSA")

    init {
        cipher.init(
            Cipher.ENCRYPT_MODE,
            publicKey
        )
    }

    fun encrypt(str: String): String = Base64.getEncoder().encodeToString(cipher.doFinal(str.toByteArray()))
}

fun ByteArray.decompress(): ByteArray {
    val bis = ByteArrayInputStream(Base64.getDecoder().decode(this))
    val gzipInputStream = GZIPInputStream(bis)
    val bos = ByteArrayOutputStream()
    val buffer = ByteArray(1024)
    var len: Int
    while ((gzipInputStream.read(buffer).also { len = it }) != -1) {
        bos.write(buffer, 0, len)
    }
    bos.close()
    gzipInputStream.close()
    return bos.toByteArray()
}
