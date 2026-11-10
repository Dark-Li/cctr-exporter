# PDF 行内公式基线修复

## 对齐规则

和文字同一源行的行内公式，优先应用明确的 `vertical-align`。支持 `px`、`pt`、零值和 `baseline`，显式零值优先于 WIRIS。没有显式声明时，以可靠的 WIRIS 基线及源视口高度计算。对无基线的已识别位图公式只补偿实测底部防裁边，保留源图内部留白。

位图捕获同时返回实际公式内容高度和图盒底边。矢量没有截图留边。偏移按 iText 完成布局后的实际图高换算，涵盖窄表格内按剩余行宽的二次缩放。截图像素倍率与版面偏移分开。仅有需调整公式的段落使用 iText 行内固定偏移，使位移参与行高计算。普通配图、显式块级公式、没有同源行文字的公式以及普通文字段落沿用各自原规则。

实物量测与设计见 [行内公式基线诊断](../design/2026-11-10-pdf-inline-formula-baseline-diagnosis.md)。

## 验证

- 修复前正式回归中，路径 SVG 的 `vertical-align:-5px` 对应图盒底与文字基线差 0.00 pt，预期为 -3.75 pt。
- 修复后，`InlineFormulaBaselinePdfTest`、`SkippedSubjectPdfTest`、`SelectableQuestionPdfTest`、`FillBlankQuestionPdfTest` 和 `PdfExportLayoutTest` 共 24 项 Gradle 回归通过。新用例覆盖矢量、WIRIS 与外层尺寸换算、显式零值、包装基线、缺省位图、同一行多公式、普通配图、块级公式、窄表格和跨页。窄列宽式在同一行缩至约 200.75 pt，位移按 PDF 图盒的实际高度验证为约 -6.60 pt，并核对下一行净空。
- 1.1.4 便携包用随包 Java 和 JAR 跑过 18 项离线回归。用工作区已有的真实短符号和 MathML 分式捕获生成样例。测得图盒底分别相对正文基线下降 6.00 pt 和 15.75 pt，分式与下一行字形之间保留 3.08 pt，周围文字可提取。样例用嵌入字体渲染并目视检查。
- 本次重用工作区捕获，未启动系统 Edge、登录平台或重导 `task_72`、`task_73`。新捕获的内容盒尺寸及真实题库显示留待用户重导确认。

## 工作区产物

- Windows x64 便携测试版 `build/inline-formula-baseline-build/portable-aligned/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`，完整目录 228,472,532 字节，运行时保留同目录的 `app` 和 `runtime`。
- 样例 `build/inline-formula-baseline-validation/packaged/captured-baseline.pdf`，同目录有 `captured-baseline-1.png`。
- 构建及验证日志在 `build/inline-formula-baseline-build/` 下的 `gradle-aligned.log`、`portable-aligned-smoke.log`、`capture-aligned-smoke.log`。
