# PDF 分组并行排版

## 处理规则

同一任务的 PDF 组（课程 / 章节 / 题型）原先逐个串行排版，主耗时集中在 Edge 局部捕获与 iText 排版。现改为按组并行：

- 并发上限 `maxParallelGroups = 4`（`PdfTaskExport.kt`），由 `Dispatchers.IO.limitedParallelism` 约束，目的是同时受控于 10 个 Edge driver 的连接池与内存峰值。
- 每组持有独立的 `PdfDocument`，不做跨组共享；`writeSelectableQuestionPdf` 内部的字体、`PdfHtmlContent`、图片对象均为组内私有。
- 组内仍然严格串行：题干、选项、答案依赖同一 `Document` 的布局顺序，iText 的 `Document`/`PdfDocument` 不是线程安全的，不能并行。
- 结果按 `groups` 的输入顺序合并，跳题 ID 的日志顺序、`exportedCount`、`onGroupProcessed` 的语义保持不变。
- 失败通过 `Result` 值返回，出了 `coroutineScope` 再抛出。若在 `async` 内直接抛异常，kotlinx.coroutines 的栈恢复会复制异常对象（Gradle 测试默认 `-ea`，即 debug 模式），使 `cause` 链多一层，`SkippedSubjectPdfTest` 的 `cause?.cause is EOFException` 断言失效。
- `CancellationException` 仍在 `async` 内直接抛出，保证取消能传播并取消其余组。

## 已知边界

- 只有 1 个组时无加速；组数 ≥ 4 时捕获与排版阶段可重叠。
- 任一组失败后会等待其余在跑的组结束再抛出第一个失败组的异常（输入顺序），不再立即中断；归档文件依旧走 `writeAtomicFile`，失败不会覆盖上一次的 zip。
- 峰值内存约为 4 份并发文档 + 各自的局部截图，暂未做动态降级。

## 离线验证

- 编译：`JAVA_HOME=<jdk11> ./gradlew :compileKotlin --offline`，通过。
- 回归：`./gradlew :test --offline --tests SkippedSubjectPdfTest --tests FillBlankQuestionPdfTest --tests SelectableQuestionPdfTest --tests PdfAssetGeometryTest --tests PdfExportLayoutTest`，共 23 项通过（5 + 3 + 7 + 2 + 6），0 失败。
- 未执行真实题库端到端导出：需登录会话与 Edge driver，本轮只覆盖合成样例与错误路径。加速比尚未实测。

## 后续可选优化（本轮未做）

1. 组内预取：先收集整组待捕获片段，并发截图后按原序交给 iText，可覆盖单组场景；需要区分“收集”和“排版”两趟遍历。
2. 片段缓存：以 fragment 字符串为 key 复用截图，需按像素总量设上限，否则大图会顶高内存。
3. 字体：`createQuestionPdfFont(cached = false)` 每份 PDF 重新加载并子集化，跨文档复用需先验证 `glyph.setChars` 的全局副作用（`PdfHtmlContent.kt:64-68`）。
