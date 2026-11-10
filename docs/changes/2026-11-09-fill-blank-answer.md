# 填空题 PDF 答案修复与验证

## 处理规则

PDF 遇到题型为「填空题」时保留原题干与空格，单独排版「参考答案」。优先使用有可见内容的格式化答文，其次使用平台纯文本，最后按源顺序读取原答题条目的 HTML。原始条目缺少 `istrue` 也能导出。对这些条目不加选项字母，不推断空位或同义关系。只有三种来源都无可见内容才显示「平台未提供」。公式和图片使用现有的局部渲染流程。

问题证据和数据边界见 [填空题答案诊断](../design/2026-11-09-fill-blank-answer-diagnosis.md)。

## 离线验证

- 修复前的正式 Gradle 回归新增 3 项均失败。其中一项因旧版把填空题误排为选项，两项因旧版解码答题条目时要求 `istrue`。
- 修复后 `:test --tests FillBlankQuestionPdfTest --tests SelectableQuestionPdfTest --tests PdfExportLayoutTest` 共 15 项通过。测试从 PDF 提取中文答案并检查优先级、原序、无 A/B 标签、真缺省、公式对象和既有 PDF 行为。
- Windows x64 便携包用随包 Java 和应用 JAR 实际生成 PDF，填空回归 3 项通过。PDFBox 将三份样例各渲染为一页，逐页核对了题干空格、答案位置、原序和公式。
- 样例来自合成数据。应用登录、真实题库重导及需 Edge 的局部资源未在本轮运行。

## 工作区产物

- 启动器 `build/fill-blank-pdf-build/portable-final/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`。运行时保留同目录下 `app`、`runtime`。完整目录 228,438,038 字节。
- 样例 `build/fill-blank-pdf-validation/fill-blank.pdf`、`answer-priority.pdf`、`formula-answer.pdf`，PNG 位于同目录的 `rendered`。
- 构建和包内验证日志位于 `build/fill-blank-pdf-build/`，分别为 `gradle-red.log`、`gradle-final.log`、`jpackage-final.log`、`portable-final-smoke.log`，校验信息见 `build-receipt.json`。
