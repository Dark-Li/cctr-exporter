# 题库 PDF 正文可复制改造

2026-11-09。按批准的[诊断与方案](../design/2026-11-09-selectable-pdf-content.md)实施。

PDF 在原题截图前分流，直接使用平台提供的题干、选项和答案。正文是可搜索和复制的 PDF 文字。选项保留平台的顺序和明确标签，缺标签时补上 A、B、C 等序号。正确答案独立列出，由平台的 `istrue` 决定。多选列出所有正确项，判断题保留对错语义，主观题优先使用格式化答文。原课程、章节、题型分组、A4、自然题序、页码及题目书签沿用，长题续页显示题号。

支持 HTML 段落、换行、列表、上下标、表格跨行跨列和内联图片。数据 URI 内的 PNG 等原图直接嵌入，普通 SVG 保持矢量。带自有字体的 SVG、MathML 或远端图片可由 Edge 渲染该局部，周围文字仍为 PDF 文本。表格列宽保持稳定，配图在单元格内等比缩放。资源无法加载时报告题目 ID，失败不会替换已有 PDF 或 ZIP。图片导出沿用原截图路径。

## 验证与交付

- Gradle 正式执行 `:test --tests SelectableQuestionPdfTest --tests PdfExportLayoutTest`，12 项通过，0 失败、0 跳过。覆盖题干、选项、答案提取，中文“页”和 HTML 实体，标号和去重，判断语义，主观题表格，公式前后文字和局部回退，宽图比例、长表分页、书签及坏资源不覆盖旧文件。构建与日志位于 `build/selectable-pdf-build/`。
- 离线样例保存在 `build/selectable-pdf-validation/`。`choices.pdf` 展示单选和多选，`answers.pdf` 展示判断和主观表格，`mixed.pdf` 展示矢量图和长题续页，`table-and-image.pdf` 展示表格内图片及长表。用 PDFBox 3.0.3 渲染并逐页检查 5 份样例的 10 页，截图位于同目录的 `final-render/`。42 行长表最终分成 3 页，两列正常横排，图像保持 4:1，末行及表后文字完整。90 段长题的续页题号和页码正常。
- Windows x64 便携版启动器为 `build/selectable-pdf-build/portable-final/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`。完整目录 228,426,817 字节，移动时要连同 `app` 与 `runtime` 保留。包内 Java 和 108 个应用及运行 JAR 通过启动依赖校验，并用成品 JAR 重新执行 6 项 PDF 测试，全部通过。校验收据位于 `build/selectable-pdf-build/build-receipt.json`。
- 局部 Edge 回退通过注入渲染结果验证了触发范围、图像保留及上下文可复制。实际 Edge 渲染和登录后的真实题库导出留待用户使用测试版复验。
