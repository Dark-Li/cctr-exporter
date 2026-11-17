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

data class CapturedPdfAsset @JvmOverloads constructor(
    val image: BufferedImage,
    val cssWidth: Int,
    val cssHeight: Int,
    val contentHeightCssPixels: Float = cssHeight.toFloat(),
    val bottomInsetCssPixels: Float = 0f,
    val baselineFromBottomCssPixels: Float? = null,
    val sourceCssScale: Float = 1f,
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
        val first = asset.children().firstOrNull()
        val kind = when { asset.selectFirst("[data-cctr-font-family]") != null -> "text-font"
            first?.normalName() in setOf("img", "svg", "math") -> first!!.normalName(); else -> "asset" }
        Files.writeString(file, document.outerHtml(), Charsets.UTF_8)
        val timeouts = manage().timeouts()
        val oldPageTimeout = timeouts.pageLoadTimeout
        try {
            timeouts.pageLoadTimeout(Duration.ofSeconds(15))
            get(file.toUri().toASCIIString())
        } catch (error: org.openqa.selenium.TimeoutException) {
            throw IllegalStateException("局部资源加载失败；类型=$kind；状态=page-load-timeout", error)
        } finally {
            timeouts.pageLoadTimeout(oldPageTimeout)
        }
        val dimensions = executeAsyncScript(
            """
            const done = arguments[arguments.length - 1];
            const asset = document.getElementById('pdf-asset');
            const images = Array.from(document.querySelectorAll('#pdf-asset img'));
            const measure = () => {
              const boxes = [];
              const visible = node => {
                for(let e = node.nodeType === 1 ? node : node.parentElement; e && e !== asset; e = e.parentElement) {
                  const style = getComputedStyle(e);
                  if(style.display === 'none' || style.visibility === 'hidden' || e.hidden) return false;
                }
                return true;
              };
              for(const node of asset.querySelectorAll('img,svg,math')) {
                if(visible(node)) {const box=node.getBoundingClientRect();if(box.width>0&&box.height>0)boxes.push(box);}
              }
              const walker = document.createTreeWalker(asset,NodeFilter.SHOW_TEXT);
              while(walker.nextNode()) {
                const node = walker.currentNode;
                if(!visible(node) || !node.textContent.replace(/[\s\u200e]/g,''))continue;
                if(['STYLE','SCRIPT'].includes(node.parentElement?.tagName))continue;
                const range=document.createRange();range.selectNodeContents(node);
                for(const box of range.getClientRects())if(box.width>0&&box.height>0)boxes.push(box);
              }
              const rect=asset.getBoundingClientRect();
              const left=boxes.length?Math.min(...boxes.map(b=>b.left)):0;
              const top=boxes.length?Math.min(...boxes.map(b=>b.top)):0;
              const right=boxes.length?Math.max(...boxes.map(b=>b.right)):0;
              const bottom=boxes.length?Math.max(...boxes.map(b=>b.bottom)):0;
              const baseline=asset.querySelector('[data-cctr-font-baseline]')?.getBoundingClientRect();
              window.__cctrPdfBoxes=boxes.map(box=>({left:box.left,top:box.top,width:box.width,height:box.height}));
              window.__cctrPdfRoot=[rect.left,rect.top,rect.right,rect.bottom];
              return [Math.max(rect.width,asset.scrollWidth,right+4),Math.max(rect.height,asset.scrollHeight,bottom+4),
                window.devicePixelRatio,right-left,bottom-top,Math.max(0,Math.max(rect.height,asset.scrollHeight,bottom+4)-bottom),
                baseline?baseline.top:null,left,top];
            };
            const ready = Promise.all([...images.map(image=>image.decode()),document.fonts.ready]).then(async()=>{
              if(images.some(image=>image.naturalWidth===0))return null;
              let previous='', result=null, stable=false;
              for(let attempt=0;attempt<4;attempt++) {
                await new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
                result=measure();const key=JSON.stringify(result);
                if(key===previous && result[3]>0 && result[4]>0){stable=true;break;}
                previous=key;await new Promise(resolve=>setTimeout(resolve,60));
              }
              window.__cctrPdfOriginalBoxes=window.__cctrPdfBoxes;
              window.__cctrPdfMeasure=measure;
              return [...result,stable,...window.__cctrPdfRoot];
            });
            const timeout = new Promise((_, fail) => setTimeout(fail, 10000));
            Promise.race([ready, timeout]).then(done, () => done(null));
            """.trimIndent()
        ) as? List<*> ?: error("局部资源加载失败；类型=$kind；状态=decode-or-font-wait")
        val rawWidth = (dimensions[0] as? Number)?.toDouble() ?: Double.NaN
        val rawHeight = (dimensions[1] as? Number)?.toDouble() ?: Double.NaN
        val devicePixelRatio = (dimensions[2] as? Number)?.toDouble() ?: Double.NaN
        val maximumWidth = first?.attr("data-cctr-pdf-max-css-width")?.toDoubleOrNull() ?: 650.0
        val contentWidth = (dimensions[3] as? Number)?.toDouble() ?: Double.NaN
        val contentHeight = (dimensions[4] as? Number)?.toDouble() ?: Double.NaN
        val bounds = pdfAssetCaptureBounds(rawWidth, rawHeight,
            (dimensions[7] as? Number)?.toDouble() ?: Double.NaN, (dimensions[8] as? Number)?.toDouble() ?: Double.NaN,
            contentWidth, contentHeight, (dimensions[10] as Number).toDouble(), (dimensions[11] as Number).toDouble(),
            (dimensions[12] as Number).toDouble(), (dimensions[13] as Number).toDouble())
        val plan = planPdfAssetCapture(bounds.width, bounds.height, contentWidth, contentHeight, devicePixelRatio,
            maximumWidth, kind, dimensions[9] == true || contentWidth <= 0 || contentHeight <= 0)
        val width = plan.width
        val height = plan.height
        val translateX = -bounds.left * plan.cssScale
        val translateY = -bounds.top * plan.cssScale
        if (plan.cssScale != 1.0 || translateX != 0.0 || translateY != 0.0) {
            val validation = executeAsyncScript(
                """
                const [s,x,y,done]=arguments;
                const asset=document.getElementById('pdf-asset');
                const original=window.__cctrPdfOriginalBoxes;
                asset.style.transformOrigin='0 0';asset.style.transform='matrix('+[s,0,0,s,x,y].join(',')+')';
                requestAnimationFrame(()=>requestAnimationFrame(()=>{
                  window.__cctrPdfMeasure();const current=window.__cctrPdfBoxes;
                  let delta=0;
                  if(original.length!==current.length)delta=Infinity;
                  else for(let i=0;i<original.length;i++){
                    const a=original[i],b=current[i];
                    delta=Math.max(delta,Math.abs(b.left-(a.left*s+x)),Math.abs(b.top-(a.top*s+y)),
                      Math.abs(b.width-a.width*s),Math.abs(b.height-a.height*s));
                  }
                  done([Number.isFinite(delta)&&delta<=0.25,Number.isFinite(delta)?delta:null,original.length,current.length]);
                }));
                """.trimIndent(), plan.cssScale, translateX, translateY,
            ) as? List<*>
            require(validation?.firstOrNull() == true) {
                "局部内容几何失败；原因=layout-context-changed；类型=$kind；CSS=${bounds.width}x${bounds.height}；" +
                    "原点=${bounds.left},${bounds.top}；DPR=$devicePixelRatio；scale=${plan.cssScale}；delta=${validation?.getOrNull(1)}"
            }
        }
        verifyPdfTextFont()
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
            CapturedPdfAsset(
                image, width, height, (contentHeight * plan.cssScale).toFloat(),
                (height - ((dimensions[8] as Number).toDouble() + contentHeight - bounds.top) * plan.cssScale).toFloat().coerceAtLeast(0f),
                (dimensions[6] as? Number)?.let { (height - (it.toDouble() - bounds.top) * plan.cssScale).toFloat() },
                plan.cssScale.toFloat(),
            )
        } finally {
            executeCdpCommand("Emulation.setDefaultBackgroundColorOverride", emptyMap())
        }
    } finally {
        Files.deleteIfExists(file)
    }
}

/** The caller also checks this exact font file's cmap before sending a source glyph. */
private fun EdgeDriver.verifyPdfTextFont() {
    val expected = executeScript(
        "return document.querySelector('#pdf-asset [data-cctr-font-family]')?.getAttribute('data-cctr-font-family') ?? null"
    ) as? String ?: return
    val document = executeCdpCommand("DOM.getDocument", emptyMap())["root"] as? Map<*, *>
        ?: error("无法验证局部源字体")
    val node = executeCdpCommand(
        "DOM.querySelector",
        mapOf("nodeId" to document["nodeId"], "selector" to "#pdf-asset [data-cctr-font-family]")
    )["nodeId"] as? Number ?: error("无法验证局部源字体")
    executeCdpCommand("CSS.enable", emptyMap())
    val fonts = executeCdpCommand("CSS.getPlatformFontsForNode", mapOf("nodeId" to node))["fonts"] as? List<*>
        ?: error("无法验证局部源字体")
    check(fonts.isNotEmpty() && fonts.all { value ->
        val face = value as? Map<*, *>
        face?.get("familyName")?.toString()?.equals(expected, true) == true &&
            (face["glyphCount"] as? Number)?.toInt()?.let { it > 0 } == true
    }) { "局部文字未使用已验证的源字体" }
}
