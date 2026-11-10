package top.kagg886.cctr.driver

import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.openqa.selenium.By
import org.openqa.selenium.Dimension
import org.openqa.selenium.OutputType
import org.openqa.selenium.WebElement
import org.openqa.selenium.edge.EdgeDriver
import top.kagg886.cctr.util.useTempFile
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.nio.file.Files
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO

fun exec(vararg cmd: String): String {
    val p = Runtime.getRuntime().exec(cmd)
    return SequenceInputStream(p.inputStream, p.errorStream).readAllBytes().decodeToString()
}

fun execWithArgs(cmd: Array<String>): String {
    val p = Runtime.getRuntime().exec(cmd)
    return SequenceInputStream(p.inputStream, p.errorStream).readAllBytes().decodeToString()
}

suspend fun EdgeDriver.captchaImage(html: String): BufferedImage {
    return CompletableFuture.supplyAsync {
        var rectangle: WebElement? = null
        useTempFile(".html") { tmp ->
            val doc: Document = Jsoup.parse("<div>$html</div>")
            doc.body().child(0).attr("id", "captcha")
            tmp.appendText(doc.html())
            //加载html
            get("file://" + tmp.absolutePath)
            rectangle = findElement(By.ById("captcha"))

            val viewPortHeight = this.executeScript("return window.innerHeight") as Long //视口大小
            val outerHeight = this.executeScript("return window.outerHeight") as Long //浏览器窗口高度
            val domHeight = this.executeScript("return document.getElementById('captcha').scrollHeight") as Long //元素实际高度

            if (domHeight > viewPortHeight) {
                with(this@captchaImage.manage().window()) {
                    size = Dimension(size.width, (domHeight + (outerHeight - viewPortHeight)).toInt())
                }
            }
        }
        ImageIO.read(ByteArrayInputStream(rectangle!!.getScreenshotAs(OutputType.BYTES)))
    }.asDeferred().await()
}

data class CapturedPdfAsset(
    val image: BufferedImage,
    val cssWidth: Int,
    val cssHeight: Int,
    val contentHeightCssPixels: Float = cssHeight.toFloat(),
    val bottomInsetCssPixels: Float = 0f,
)

/** Render only the complex fragment used by a selectable PDF. */
suspend fun EdgeDriver.capturePdfAsset(fragment: String): CapturedPdfAsset = withContext(Dispatchers.IO) {
    val file = Files.createTempFile("cctr-pdf-asset", ".html")
    try {
        val document = Jsoup.parseBodyFragment(fragment)
        document.select("meta, link, base, script, iframe, object, embed, form").remove()
        document.charset(Charsets.UTF_8)
        document.head().appendElement("meta").attr("http-equiv", "Content-Security-Policy")
            .attr("content", "default-src 'none'; img-src data: https: http:; style-src 'unsafe-inline'; font-src data:; form-action 'none'; base-uri 'none'")
        document.head().appendElement("style").text(
            "html,body{margin:0;background:transparent} " +
                "#pdf-asset{width:max-content;padding:4px} " +
                "#pdf-asset>img,#pdf-asset>svg{display:block} #pdf-asset>math{display:block math} " +
                "#pdf-asset>img:not([src^='data:image/svg+xml' i]){max-width:650px;height:auto}"
        )
        val body = document.body()
        val asset = org.jsoup.nodes.Element("div").attr("id", "pdf-asset")
        asset.appendChildren(body.childNodesCopy())
        body.empty().appendChild(asset)
        asset.select("img").removeAttr("srcset")
        Files.writeString(file, document.outerHtml(), Charsets.UTF_8)
        val timeouts = manage().timeouts()
        val oldPageTimeout = timeouts.pageLoadTimeout
        try {
            timeouts.pageLoadTimeout(Duration.ofSeconds(15))
            get(file.toUri().toASCIIString())
        } finally {
            timeouts.pageLoadTimeout(oldPageTimeout)
        }
        val dimensions = executeAsyncScript(
            """
            const done = arguments[arguments.length - 1];
            const asset = document.getElementById('pdf-asset');
            const images = Array.from(document.querySelectorAll('#pdf-asset img'));
            const ready = Promise.all([...images.map(image => image.decode()), document.fonts.ready])
              .then(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
              .then(() => {
                if (images.some(image => image.naturalWidth === 0)) return null;
                const rect = asset.getBoundingClientRect();
                const width = Math.ceil(Math.max(rect.width, asset.scrollWidth));
                const height = Math.ceil(Math.max(rect.height, asset.scrollHeight));
                const content = asset.firstElementChild?.getBoundingClientRect();
                const bottomInset = content ? Math.max(0, height - content.bottom) : 0;
                return [width, height, window.devicePixelRatio, content?.height ?? height, bottomInset];
              });
            const timeout = new Promise((_, fail) => setTimeout(fail, 10000));
            Promise.race([ready, timeout]).then(done, () => done(null));
            """.trimIndent()
        ) as? List<*> ?: error("题目图片或公式加载失败")
        val width = (dimensions[0] as Number).toInt()
        val height = (dimensions[1] as Number).toInt()
        val devicePixelRatio = (dimensions[2] as Number).toDouble()
        require(width in 9..4000 && height in 9..10000 && width * height <= 8_000_000) {
            "局部题目内容尺寸无效或过大"
        }
        require(devicePixelRatio > 0 && devicePixelRatio.isFinite()) { "局部渲染像素倍率无效" }
        val pixelsPerCssPixel = 2
        val scale = pixelsPerCssPixel / devicePixelRatio
        executeCdpCommand("Emulation.setDefaultBackgroundColorOverride", mapOf("color" to mapOf("r" to 0, "g" to 0, "b" to 0, "a" to 0)))
        try {
            val clip = mapOf("x" to 0, "y" to 0, "width" to width, "height" to height, "scale" to scale)
            val screenshot = executeCdpCommand(
                "Page.captureScreenshot",
                mapOf("format" to "png", "fromSurface" to true, "captureBeyondViewport" to true, "clip" to clip)
            )["data"] as? String ?: error("无法获取局部渲染结果")
            val image = requireNotNull(ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(screenshot)))) {
                "无法读取局部渲染结果"
            }
            check(image.width == width * pixelsPerCssPixel && image.height == height * pixelsPerCssPixel) {
                "局部渲染像素尺寸与公式边界不符"
            }
            CapturedPdfAsset(image, width, height, (dimensions[3] as Number).toFloat(), (dimensions[4] as Number).toFloat())
        } finally {
            executeCdpCommand("Emulation.setDefaultBackgroundColorOverride", emptyMap())
        }
    } finally {
        Files.deleteIfExists(file)
    }
}
