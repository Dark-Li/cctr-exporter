# PDF 字体回退与逐题隔离

2026-11-18，在 `pdf-bux-fix`（基于 `main` = `9add8e4`）实现 [合并方案](../design/2026-11-18-pdf-font-fallback-merged.md)。工作区改动尚未提交；`PdfTaskExport.kt` 中已有的并行分组改动保持原样，本轮只在其上追加失败原因日志。

## 改动

| 文件 | 内容 |
| --- | --- |
| `PdfFontResolver.kt`（新增） | 逐字符字体解析：声明 Times → 思源 → Segoe UI Symbol → Arial Unicode MS；候选校验 family、`fsType`、字形；异常层级 `PdfQuestionSkippedException` → `PdfGlyphNotSupportedException` / `PdfContentNotSupportedException`；`GlyphRoute` |
| `PdfSourceFont.kt` | `normalizeFamily`（NFKC、去 Cf、折叠空白、去引号）；所有字体身份比较改用归一化名；`fsType` 解析抽成 `fontEmbeddingPermission`；错误类型改为 `PdfGlyphNotSupportedException` |
| `PdfTextDirection.kt` | 零宽控制集补充 U+00AD、U+200B、U+2061–U+2064；未验证格式控制与双向文本改抛 `PdfContentNotSupportedException` |
| `PdfHtmlContent.kt` | 正文改用 `PdfFontResolver`；新增 `runs`／`appendRuns`，标题、答案行、页眉页脚、`fit()` 共用；回退字体按码点校正 ToUnicode；方向样式拒绝改为内容级异常 |
| `SelectableQuestionPdf.kt` | `QuestionPdfResult.failureMessages`；逐题捕获 `PdfQuestionSkippedException` 并继续；题号按成功导出的题目连续编号；书签移到题目成功加入文档之后；全部失败时抛错保留旧文件 |
| `PdfTaskExport.kt` | 汇总 `failureMessages`，以“排版失败：…”写入任务日志，单行限 200 字符 |

## 实际行为

- 非私用区字符：先看声明字体（仅 Times 四字面），再看思源，最后按固定顺序回退 Segoe UI Symbol、Arial Unicode MS。命中即用原生文字，不使用截图。
- 私用区字符：维持原有严格策略（Symbol 映射、Symbol／Wingdings 局部截图）；来源不明仍报错，但只跳过该题。
- 单题失败：字形、未验证格式控制、双向文本、复杂连接语境都记录题目 ID 与原因并继续其余题目；截图、几何、结构异常仍快速失败。
- 全部题目失败：抛出汇总异常，`writeAtomicPdf` 不替换旧文件。

## 实施中发现并处理的问题

1. **斜体模拟会插入空格**：在回退 run 上调用 `Text.setItalic()` 后，`C∙D` 提取成 `C∙ D`，后续 run 前出现空隙。改为回退字体不模拟斜体，只有思源模拟；粗体不受影响。
2. **共享字形丢 ToUnicode**：Segoe UI Symbol 的 U+02D9 单独子集时提取为 U+FFFD。沿用思源 Han 的 `glyph.setChars` 做法，对所有回退字形校正，9 个新增用例逐码点校验。
3. **孤立书签**：原实现先加书签再构建题目；跳题会留下指向不存在锚点的条目。现改为 `document.add` 成功后再加。
4. **全失败覆盖旧文件**：若一题都排不出，仍会生成只有页眉的 PDF。现改为在原子写入的临时文件阶段抛错，旧文件保留，原有 `SourceFontQuestionPdfTest` 的断言继续成立。
5. **页脚文本被清空**：`pdfDrawableText` 最初写成 `buildString { codePoints()... }`，`codePoints()` 解析到 `StringBuilder` 自身（初始为空），导致页眉／页脚／续页标记全部为空。改为显式 `StringBuilder` 后恢复，长题续页用例重新通过。

## 测试调整

`DirectionAndNativeFontPdfTest.doesNotReuseNativeFontCacheForUnverifiedSameNameCustomFont` 原断言“整个导出抛错”；L4 后第 2 题（自定义同名 Times）被跳过、第 1 题正常导出。改为断言 `exportedCount=1`、`skippedIds=["42732303"]`、失败原因含“自定义字体尚未验证”，并检查 PDF 只含 First、不含 Second。

## 后续补丁：U+00AD 与内容级跳过

用户用第一版便携包重导题 `43909209`（机械设计 2026 / 齿轮传动 / 填空题）时报 `PDF 尚未验证文本格式控制；阶段=题干；码点=U+AD`。U+00AD 是软连字符，Word 的换行提示，属于 Cf 但不在原零宽白名单。

两层问题：

1. 白名单只覆盖 ZWNJ／ZWJ／WJ／FEFF，遗漏 SHY、ZWSP 和 OMML 常见的不可见数学运算符。
2. 该错误由 `pdfLtrText` 的 `require` 抛普通 `IllegalArgumentException`，不在 L4 捕获范围，因此单题问题仍然升级为整任务失败。

修复分两步。第一步先补 U+00AD、U+200B、U+2061–U+2064 并引入内容级异常；随后按用户要求改成**类别判定**，避免继续“出现一个补一个”：

- **零宽判定改为按 Unicode 类别**：`General_Category=Cf` 且不参与双向重排的字符，一律按零宽控制处理，附着到相邻可见字形并写 ActualText。不再维护字符清单，SHY、ZWSP、WJ、ZWJ/ZWNJ、BOM、不可见数学运算符、标签字符、行间注解等都是同一条规则。
- **只对双向控制保留显式集合**：LRM 丢弃；RLM、ALM、U+202A–202E、U+2066–2069 仍视为当前渲染器不支持，但只跳过该题。这个集合由 Unicode 双向算法固定，不会随题库增长。
- **内容级统一异常**：新增 `PdfQuestionSkippedException` 基类；字形失败（`PdfGlyphNotSupportedException`）与内容失败（`PdfContentNotSupportedException`：未验证格式控制、双向文本、复杂连接语境、方向样式、跨节点/跨字体禁断行）分别继承；`SelectableQuestionLayout.write` 捕获基类，只跳过该题。
- **内容守卫全部归位**：`PdfHtmlContent` 与 `PdfFormatText` 中与字符/控制相关的 `require` 改为内容级异常；表格跨度、图片尺寸等结构问题仍快速失败。

新增回归：`keepsSoftHyphenAndZeroWidthSpaceWithoutFailing`（SHY、ZWSP、U+2061）、`keepsSoftHyphenInFillBlankAnswers`（填空题题干与答案）、`handlesAnyInvisibleFormatCharacterByCategory`（从未列入清单的 U+FFF9 与 U+E0020）、`skipsOnlyQuestionWithUnsupportedDirectionText`（三道题中间一道希伯来文）。全量 PDF 回归变为 **51 项、0 失败**。

第三版便携包主 JAR：`CCTRCloudExportDesktop-1.1.6-41c816efc2232daa753a34e2989a4.jar`，SHA256 `73a31d7a44af8f6dbccfcd5bec05d65bec4c5caa5cec1e9ed1e2ecc790837987`；包内端到端冒烟导出 4 题（含 U+00AD／U+200B／U+2061，以及未列入清单的 U+FFF9／U+E0020），202,736 字节。

## 验证

- 新增 `FontFallbackPdfTest` 12 项：Calibri／Times + U+223C、Times + U+2219（含 U+200B、词间 NBSP）、U+2322、U+02D9、粗斜体、窄表格、标题／答案行／页脚、SHY／ZWSP／U+2061、填空题 SHY、U+FFF9／U+E0020 任意 Cf、无字体覆盖诊断、逐题隔离、截图失败快速失败。
- 全量 PDF 回归：**51 项、0 失败**（`Direction 7 / FillBlank 3 / FontFallback 12 / InlineFormula 4 / AssetGeometry 3 / Layout 6 / Selectable 7 / SkippedSubject 5 / SourceFont 4`）。
- 生成物抽查：`build/font-fallback-pdf-validation/fallback.pdf` 嵌入 `SourceHanSansCN`、`SegoeUISymbol`、`TimesNewRomanPSMT`；`A ∼ B` 与 `P = F ∙ v` 的三个 run 基线同为 y=501.64 / 379.66，字号 11；`times-normalized.pdf` 只嵌入思源与 Times，U+2219 由 Times 提供。
- `PDFTest` 是依赖真实账号与 Edge 的手工用例（占位账号、非 headless），不在本轮回归内；未登录、未重导真实题目。

## 未完成与边界

- 题 `43909209`、`56556180`、`43443919` 尚未用真实账号重新导出，需用户自行验收显示、提取与书签。
- 未构建安装包；未实施策略 B（随包 Noto 字体）。
- 系统字体也覆盖不到的字符（如 CJK 扩展 B U+2B739）仍会跳过该题并在日志中列出已尝试字体。
- 只含一道题的导出组若该题被跳过，仍会因“没有可导出的题目”使任务失败；这是保留“全失败不覆盖旧文件”的代价。
