# PDF 导出跳过无法解码题干的题目

## 行为

PDF 先按原分组去重和排序，再解码 `subjecthtml_svg`。缺失、空白、无效 Base64 或损坏 gzip 的题目在 PDF 写入前被跳过。正常题仍按连续题号排版，题数、书签和可复制正文只包含有效题。题干有效但答文、资源、浏览器或写入失败时沿用任务失败处理。图片导出不使用这套跳过规则。

有可导出题目时，先完成临时 ZIP，再在同一数据库事务写入任务日志的「N 道题未导出成功，已跳过」和分条 ID，成功后才原子替换目标 ZIP。数量按唯一题目 ID 汇总。结束提示标明部分导出，任务状态沿用成功以便下载。整组全坏时不写该组 PDF。全部无有效题时仍记录跳过明细，任务失败并保留旧 ZIP。若报告写入失败，也保留旧 ZIP。

原故障见 [题干 EOF 诊断](../design/2026-11-10-pdf-subject-eof-diagnosis.md)。

## 验证

- 修复前，新增合成回归用一个字节的 gzip 标识复现同一 `EOFException`，夹在坏题前后的有效题未能导出。
- 修复后 `:test --tests SkippedSubjectPdfTest --tests SelectableQuestionPdfTest --tests FillBlankQuestionPdfTest --tests PdfExportLayoutTest` 共 21 项通过。覆盖字段缺省、无效 Base64、损坏 gzip、好坏混合、全坏组、全坏任务、非题干异常、取消和日志写入失败。
- 新便携包的 Java 和 JAR 通过 15 项离线测试。另使用实际归档逻辑和任务日志服务连接隔离 SQLite，导出一个含 2 道有效题的 PDF，并从应用使用的日志读取方法取回摘要及全部 25 个跳过 ID。整组坏题没有 ZIP 条目。PDFBox 渲染并逐页检查了样例，题号和题数正确。
- 本次使用合成数据，未启动新包 GUI、登录平台或重导真实题库。原诊断实例保持独立。

## 工作区产物

- 启动器 `build/skipped-subject-pdf-build/portable-final/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`。Windows x64，完整便携目录 228,462,454 字节，运行时保留同目录 `app`、`runtime`。
- 样例 PDF、预览和 ZIP 位于 `build/skipped-subject-pdf-validation/packaged/`，文件分别为 `app-visible.pdf`、`app-visible-page-1.png` 和 `app-visible.zip`。
- 正式回归、包内回归和实际任务日志验证位于 `build/skipped-subject-pdf-build/` 下的 `gradle-final.log`、`portable-final-smoke.log` 和 `task-log-smoke.log`。
