# PDF 零宽连接控制的后续修复

用户在最新方向／字体测试版的隔离日志实例亲自复现后，题 `76142141` 的**参考答案**出现 `U+200C`，最深异常是 `PdfTextDirection.kt` 的通用 Cf 拒绝分支。证据为 `build/diagnostic-text-layout/session-20261117-122408/stderr.log`。没有读取题文、账号数据库或重新查询接口；此例不是可见字形缺失，也不是图片尺寸异常。

此前只对 LRM 分类而将其它 Cf 统一拒绝，遗漏了零宽连接控制。U+200C 是 ZWNJ，U+200D 是 ZWJ，影响连接／连字；U+2060 是 WORD JOINER，U+FEFF 在文本内部具有历史上的禁止换行用途。它们不是需要字体提供可见图形的普通字符，也不能全部删除。参见 [Unicode 第23章布局控制](https://www.unicode.org/versions/Unicode17.0.0/core-spec/chapter-23/)。

## 实施范围

在当前已支持的简单 LTR 中文、拉丁、希腊／数学语境中，这四类控制在 glyph 检查之前分类。绘制字符串不含它们的可见 glyph；原始控制与相邻可见 glyph 通过公开 `GlyphLine.setActualText` 关联，支持 ActualText 的提取器仍取得原始字符顺序。独立 `span` 的 ZWNJ／ZWJ附着同段相邻原生文字，保留前置、中间和后置控制及原样式，不生成空 GlyphLine 伪成功。分页和预测量通过局部 renderer 的 `getNextRenderer` 保持逐 glyph 映射，避免整段 ActualText 在续行重复。

WJ／FEFF在同一原生字体 run 内额外设置禁止断行位置；只把它们当零宽、不保留断行约束是不正确的。跨文字节点、跨字体或图片的禁止换行边界尚未实现，明确报错，不静默分开绑定的内容。段落起始 FEFF 可零宽承接到首个可见 glyph；其它 FEFF 保留其不换行用途。没有把所有 Cf 统一删除或放行。

连接语境按原 HTML 片段的可见文字验证，覆盖拆在不同 `span` 的控制与邻文。复杂 Indic／RTL 整形、emoji ZWJ 序列及其它未验证脚本明确拒绝，而非删连接符后伪装成功。当前仍为简单 LTR、无额外连字整形模式，不宣称完整支持所有语言／emoji。现有 Times 四字面、中文回退、来源限定 Symbol、填空答案、坏题干跳过及局部公式几何路径保持。

## 验证与交付

红阶段 `build/zero-width-pdf-build/gradle-red.log` 的新增两项按预期失败。正式回归使用 `& build/zero-width-pdf-build/gradle-local.ps1 -PdfTests`，最终结果和产物清单在同目录 `gradle-final.log`、`runtime-files.txt`。包内回归使用 `verify-final.ps1`，收据为 `build-receipt.json`。

最终正式 Gradle **36 项、0 失败**，包内运行时／应用 JAR **30 项通过**。新主 JAR SHA256 为 `E9FF937CC6C151E876EE6A72BFEEC00FEB2472E9D169A839850A91A9757784A9`，完整便携目录 **228,530,285 字节**。版本保持1.1.5；不能仅以版本号区分本轮包与先前测试版，应使用上述目录／收据。

针对回归覆盖参考答案四种控制、默认提取不丢邻文、ActualText 精确含控制、前中后独立 span、150 段跨页控制各出现一次、24pt 窄列 WJ／FEFF禁止中间断行、跨节点／字体禁止换行明确拒绝，以及 Indic／emoji 跨 span 不被静默破坏。独立成品验证还使用了240段、Times／思源混合字体及窄表格，证据位于 `build/pdf-format-controls-review/`。

最终包内独立复核通过，见 `build/pdf-format-controls-review/final-product.log`：两页240段的 ZWNJ／ZWJ各240次且无重复，WJ／FEFF窄表禁止断行正确，独立 span 控制保留，Indic／emoji跨 span明确拒绝。该轮没有重跑 Edge。

样例 `build/direction-font-pdf-validation/joining-controls.pdf` 为原生题干及含零宽控制的参考答案；`joining-spans.pdf`、`joining-paged.pdf` 分别覆盖 DOM 拆分和续页。新便携目录是 `build/zero-width-pdf-build/portable-final/CCTRCloudExportDesktop/`，版本继续为1.1.5，没有覆盖先前目录、提交、合并或停止用户正在操作的诊断实例。

本轮根因已由真实堆栈证实，但修后没有替用户登录／重触发真实题目。零宽控制的完整复制依赖阅读器采用 PDF ActualText；默认提取依然保留可见题文。若其它脚本确实需要复杂连接整形，仍应使用经过验证的整形链，不将当前的简单 LTR 支持外推。
