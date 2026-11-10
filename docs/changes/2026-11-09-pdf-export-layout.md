# 题库 PDF 排版优化记录

2026-11-09 完成离线验证。设计和实施清单见[设计](../design/2026-11-08-pdf-export-layout.md)与[实施计划](../plans/2026-11-08-pdf-export-layout.md)。

导出的 PDF 使用 A4 纵向版式。首页列出课程、章节、题型与题数，正文显示连续题号和原题 ID，每题带可跳转的书签，页脚以 `1 / 8` 的形式显示当前页和总页数。题图按 ID 自然排序，等比适配页宽，窄图设有放大上限。短题在页尾放不下时整体移到下一页，长题在空间足够时接排，优先在空白行切页并显示续题标记。连续长图按整数源像素行分段。

零题或坏图会让 PDF 导出任务失败。写入先使用同目录的临时文件，成功后替换目标。失败会清理临时目录并关闭进度通道。题库 ZIP 沿用课程、章节和题型目录。

PDF 的标题和页眉字体使用仓库附带的思源黑体 CN Regular，以子集形式嵌入。字体文件为 8,429,224 字节，SHA-256 为 `E2BC8A2E7F37474B774FFF8DB758681ECE40BB6947A90D571BCE9DD60671A8E4`，许可随字体放在 `src/main/resources/fonts/OFL.txt`。来源见 [Adobe 思源黑体发布仓库](https://github.com/adobe-fonts/source-han-sans/tree/release/SubsetOTF/CN)与[原始 OFL 许可](https://github.com/adobe-fonts/source-han-sans/blob/release/LICENSE.txt)。

## 验证结果

- 工作区内运行 `& 'build/validation-tools/pdf/validate.ps1'`，复用本地 Kotlin 1.9.22 编译器、Java 17 运行时与项目已缓存依赖，以 `-jvm-target 11` 编译 `PdfExport.kt`、两个 `file.kt`、`TaskManager.kt` 和测试，并实际执行 `PdfExportLayoutTest`。退出码 0，6 项通过。日志位于 `build/pdf-validation/tests-latest.log`。
- 测试覆盖 A4 尺寸、自然题序、数字页码、书签目标页、过长中文标题、实际字体嵌入、透明尾白长图、无空白长图的连续源像素行、空输入和坏图清理。首轮使用旧转换实现时 4 项按预期失败。
- 使用 PDFBox 3.0.3 把 5 道合成题生成的 PDF 渲染为 8 张 PNG，逐页检查。第 1 页的题干、选项、答案和图表可辨认，第 2 至 5 页长题的内容按行顺接，第 5 页接排下一道超长题，第 8 页保留连续图的末端标记和完整宽图。独立审查复查了首页、接排页和末页。PDFBox 报出 CFF 字形指令警告，渲染退出码为 0，样例 8 页的可见中文均正常。
- `git diff --check` 通过。首轮为离线局部编译与样例验证，后续完成了下方的正式 Gradle 构建和便携包验证。

## 样例与边界

- 最终样例 `build/pdf-validation/representative.pdf`，8 页，904,159 字节。渲染图位于 `build/pdf-validation/final-render/page-1.png` 至 `page-8.png`。
- 题干、选项和答案仍沿用原截图方式，因此题图内的文字无法全文搜索，特宽题图会随 A4 页宽等比缩小。
- 思源黑体中个别同形字共用字形，iText 的 PDF 文本映射在部分提取器中可能返回部首代码。页码采用数字形式，标题与题号的显示、书签及样例标题提取已实际验证。

## Windows 便携测试版

2026-11-09 按当前源码生成 1.1.3 Windows x64 测试版。启动文件为 `build/pdf-test-build/portable/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`，移动时应保留同目录下的 `app` 和 `runtime`。整个目录 228,347,525 字节，自带 Java 运行时。

- 使用工作区内 Gradle 8.5、Microsoft OpenJDK 11.0.33 和项目依赖构建应用 JAR。正式 Gradle 构建输出 `BUILD SUCCESSFUL`，生成主应用及运行依赖共 108 个 JAR。工具 ZIP 校验值见 [Gradle 校验和](https://gradle.org/release-checksums/)及 [Microsoft OpenJDK 下载页](https://learn.microsoft.com/en-us/java/openjdk/download)。
- 使用工作区原有 JDK 17 `jpackage --type app-image` 生成便携目录。构建及打包脚本、日志、SHA-256 与文件清单位于 `build/pdf-test-build/`，收据文件为 `build-receipt.json`。
- 正式 Gradle `:test --tests PdfExportLayoutTest` 结果为 6 项通过、0 失败、0 跳过。报告在 `build/test-results/test/TEST-PdfExportLayoutTest.xml`。
- 校验 x64 启动器、启动类与 108 个 JAR 的启动路径。主应用 JAR 和 `util` JAR 与构建输入哈希一致，包内 PDF 类、字体与授权文件齐全。用包内 Java 和 JAR 导出离线冒烟 PDF，验证了 4 页、2 条书签、中文标题与页码。未执行 UI 启动或真实账号导出。
