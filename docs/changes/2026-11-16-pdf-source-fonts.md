# PDF 源字体与私用区字符处理

PDF 保留可确定的来源字体身份。按祖先 `font face`、行内 `font-family` 和简单 CSS 规则识别字体，处理继承、覆盖及 `!important`。字体简写、复杂或条件字体规则无法确定时报告来源不明，避免把父层字体的编码用于子层。字体列表以首字体身份处理，后续存在 Symbol 不会使其它首字体按 Symbol 解码。

只有明确的 Symbol 来源采用本次核实的两条私用区映射。`F070` 对应 `π`，`F0BB` 对应 `≈`，输出为可复制的标准 Unicode。依据为 [Unicode 保存的 Adobe Symbol 映射](https://www.unicode.org/Public/MAPPINGS/VENDORS/ADOBE/symbol.txt)和 [Microsoft cmap 的 Symbol 编码说明](https://learn.microsoft.com/en-us/typography/opentype/spec/cmap)。自定义 `@font-face` 同名字体不套用该映射。

未能映射的局部字形仅在明确、可验证的 Symbol／Wingdings 字体中保真渲染。直接读取已知系统字体文件，核对 family、`canDisplay` 和非零 glyph，再核对 Edge 实际选用的 family。不能只把截图有墨或浏览器字体检查成功视为字形可用，因为缺字方框也可能满足这些条件。连续的同源字形只捕获一个片段，周围正文仍为原生 PDF 文字。局部字形沿测得的文字基线排版，保留粗斜体、上下标及下划线。

未知来源、字体缺失和 `.notdef` 明确失败，错误提供题号、来源阶段、码点、受限字体名称和来源类别。错误中不拼入题文或答文。具体真实题目证据与限制见 [私用区字符诊断](../design/2026-11-16-pdf-private-use-glyph-diagnosis.md)。

验证先用原实现跑两条失败回归，确认 Symbol 无法导出且错误缺少来源。最终相关 Gradle 共 28 项通过，便携包内共 22 项通过。新回归覆盖 CSS／face 继承、首字体及简写阻断、自定义同名字体、普通字母保留、选项和答案、连续局部字体片段、缺失字体及零字形拒绝。真实 Edge 独立验收使用合成题目，确认 Symbol 输出可提取 Unicode，Wingdings 保留其不同字形，邻文和书签可提取，局部图盒基线正确。

本轮仍没有两道真实报错题的源字体元数据。新包在明确来源时直接处理，在来源不明时提供具体诊断错误，真实题库需要用户重导确认。未登录账号或替用户触发请求。

Windows x64 测试版位于 `build/source-font-pdf-build/portable-final/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`，版本保持 1.1.5，完整目录 228,499,249 字节。运行时保留同目录 `app` 和 `runtime`。Gradle、包内验证及收据在 `build/source-font-pdf-build/`，实际 Edge 样例与独立证据在 `build/private-use-pdf-review/`。

最终包内 Edge 157.0.4341.60 样例为 build/private-use-pdf-review/final-product/product-source-font.pdf，同目录 page-1.png 是整页预览。连续 Wingdings 两码只捕获一次，局部图基线相对正文均为 -5.25 pt，与浏览器测得的 7 CSS px 一致。Symbol F0FF 在截图前被拒绝，错误带参考答案阶段及字体元数据，原 PDF 保留。最终日志见 build/private-use-pdf-review/final-product.log。
