# PDF 普通 Unicode 符号导出失败：诊断与回退方案

> 本文是初版诊断与单方方案。合并 codex 意见后的最终建议见 [合并修复方案](2026-11-18-pdf-font-fallback-merged.md)。

2026-11-18，分支 `pdf-bux-fix`（基于 `main` = `9add8e4`）。本文只记录诊断、证据与待批准方案；**尚未修改产品代码**。工作树中既有的并行分组改动（`PdfTaskExport.kt` 及 `2026-11-17-pdf-group-parallel.md`）保持原样，未纳入本轮。

## 现象

1.1.6 上两个题库各有一题导致整个任务失败：

- 题 `56556180`（单片机原理及应用 / 中断应用 / 应用题），阶段=题干：`PDF 字体不支持 U+223C；源字体=Calibri；来源=行内CSS`
- 题 `43443919`（机械设计 2026 / 带传动和链传动 / 计算题），阶段=参考答案：`PDF 字体不支持 U+2219；源字体=Times New Roman；来源=行内CSS`

两处报错都来自 `PdfSourceFont.error`，但触发路径不同：前者是字体确实没有该字形，后者是 Times New Roman **有** U+2219 却没有走到 Times 分支。

## 复现（合成题目，当前 main）

用 `writeSelectableQuestionPdf` 直接排版单题，逐条记录异常：

| 输入（行内 CSS / 字符） | 结果 |
| --- | --- |
| `font-family:Calibri` + U+223C | 失败：`U+223C；源字体=Calibri；需要核实源字体及字形` |
| `font-family:Times New Roman` + U+2219 | 成功 |
| `font-family:"Times New Roman",serif` + U+2219 | 成功 |
| `font-family:Times New Roman` + U+223C | 失败：`U+223C；源字体=Times New Roman` |
| `font-family:Calibri` + U+2219 | 失败（**Calibri 本身有该字形**） |
| `font-family:Times New Roman` + U+2322 | 失败 |
| 字体名中混入 U+200B / U+FEFF / 词间 U+00A0，再加 U+2219 | 失败，报错文本与截图的 `Times New Roman` 完全一致 |
| `font-family:Times New Roman` + `F070`（PUA） | 失败：`U+F070；源字体=Times New Roman` |

结论：截图二的 `U+2219 + Times New Roman` 不是 Times 缺字。只要字体名里出现不可见字符（零宽空格、BOM、不换行空格），`family.equals("Times New Roman")` 就不成立，Times 分支被整段跳过，字符落入“局部字形截图”分支；该分支只接受 Symbol／Wingdings，于是报“需要核实源字体及字形”。报错里 `\p{Cntrl}` 被过滤，肉眼看到的仍是 `Times New Roman`，所以极易误判为字体缺字。

本轮没有拿到这两题的原始题干／答案 HTML，因此无法指认具体是哪一个不可见字符。但该机制已用合成 HTML 逐条复现，且 L2 回退不依赖具体字符：即使字体名归一化后仍不匹配，字符也会由符号字体兜住。

## 字体覆盖实测（iText `containsGlyph`，本机 Windows 字体）

| 字体 | U+223C ∼ | U+2219 ∙ | U+2322 ⌢ |
| --- | --- | --- | --- |
| 内置 SourceHanSansCN-Regular | 否 | 否 | 否 |
| Times New Roman（四个字面） | 否 | **是** | 否 |
| Calibri（四个字面） | 否 | **是** | 否 |
| Segoe UI Symbol | 是 | 是 | 是 |
| Arial Unicode MS | 是 | 是 | 是 |

另取 50 个中文题库常见符号（数学运算符、几何符号、箭头、序号、单位符号）：思源缺 9 个，思源 ∪ Segoe UI Symbol 缺 0 个。

## 根因

1. **只有两档字体，没有 Unicode 回退。** `PdfHtmlContent` 只认“内置思源”和“源字体为 Times New Roman”两种选择。任何思源没有、且源字体不是 Times 的字符一律走 `renderSourceGlyph`，而 `PdfSourceFont.verifiedBrowserFamily` 只放行 Symbol／Wingdings。即使声明的 Calibri／Arial 本身有字形，也不会被使用（上表 `Calibri + U+2219`）。
2. **字体名按字面比较。** `PdfSourceFont.from` 只用 `trim()`，不处理零宽／格式字符与不换行空格；`nativeTimesBytes`、`mapSymbol`、`verifiedBrowserFamily`、`@font-face` 同名字体判断都是同一套字面比较。一个不可见字符就能让整条 Times 路径失效。
3. **把私用区策略误用到普通 Unicode。** PUA 的语义取决于来源字体，必须严格核验；普通 Unicode 码点的语义由 Unicode 定义，浏览器和 Word 都会做字体回退。当前把“未知来源字体”当成所有缺字字符的兜底，等于要求每道题的每个符号都必须由声明的字体提供。
4. **单题失败放大为整组、整任务失败。** `SelectableQuestionLayout.write` 逐题包装后重新抛出，`PdfTaskExport` 再包一层，最终整个归档任务失败；一千道题的题库会因为一个 `∙` 全部导不出。
5. **诊断信息不足。** `verifiedBrowserFamily` 对整个片段只报首码点，不报实际缺字的码点，也不列已尝试的字体，无法区分“字体缺字”和“字体名没匹配上”。

## 修复方案（待审查）

### 总原则

- **非私用区码点**：语义由 Unicode 确定，允许按固定顺序回退字体；仍要求字形存在且非 `.notdef`，文本保持可复制、可提取。
- **私用区码点**：语义依赖来源字体，维持现有严格策略（Symbol 的 `F070`／`F0BB` 映射、Symbol／Wingdings 的受验证局部截图），来源不明继续明确失败。
- 不静默替换字符、不删字符；任何降级都必须在任务日志中可见。

### L1 字体名归一化

新增统一函数（建议放在 `PdfSourceFont`）：去掉 Unicode 格式类字符（Cf：U+200B、U+FEFF、U+200E 等），把 U+00A0／U+202F／U+3000 视为空格，折叠连续空白、去首尾空白、去包裹引号，再按忽略大小写比较。`from()` 构造 `family` 时即归一化，`nativeTimesBytes`、`mapSymbol`、`verifiedBrowserFamily`、`@font-face` 同名判断全部改用归一化结果。错误信息仍输出归一化后的名字，避免“看起来一样却匹配不上”。

### L2 Unicode 回退链

新增受控回退（不扫描字体目录，保持确定性），顺序与现状一致，声明字体优先：

1. 声明的 Times New Roman 四字面（现状，经 L1 修正后才能真正命中）
2. 内置 SourceHanSansCN（现状）
3. **Segoe UI Symbol**（`%WINDIR%\Fonts\seguisym.ttf`）
4. **Arial Unicode MS**（`arialuni.ttf`，可选，存在才用）

每个候选都要核验：文件存在、family 与预期一致、`OS/2.fsType` 允许嵌入（沿用 `fsType and 0x0302 == 0`）、`containsGlyph` 为真且 glyph 不是 `.notdef`。按文档缓存、`setSubset(true)` 嵌入，只对缺失的码点生效；命中的码点仍走原生文字，不用截图。

ToUnicode 保真：复用现有思源 Han 字形的做法（`glyph.unicode != point` 时写回正确字符），并在测试中逐码点校验 `PdfTextExtractor` 的结果。实测 Segoe UI Symbol 的 U+223C／U+2219／U+2322 可正确提取；U+02D9 这类别名／无 unicode 字形在单独子集时会提取成 U+FFFD，必须一并校正并回归。

### L3 覆盖所有文本出口

不只正文 TextNode，还包括：标题、题号、`参考答案：…` 行、表格单元格、页眉页脚与 `fit()` 截断文本。做法是把 `PdfHtmlContent.pdfText` 拆成“按字体切 run”的 `appendPdfText(paragraph, value)`；`heading`／`answerLine` 改为先建空 `Paragraph` 再追加 run。页脚画布按 run 逐个 `setFontAndSize`，避免整串落回思源。

### L4 逐题隔离

新增专用异常类型（例如 `PdfGlyphNotSupportedException`，继承 `IllegalArgumentException`），只由字体／字形解析抛出。`SelectableQuestionLayout.write` 中，题目块**构建与预测量阶段**捕获该类型，记录 `(题目 ID, 原因)` 并继续下一题（此阶段尚未写入文档，不会留下半道题）。截图、几何、结构等其它异常仍快速失败，避免把真实渲染缺陷当成“可跳过”。`document.add(block)` 的布局期异常也保持快速失败。失败题目并入现有 `skippedIds` 通道，日志输出“N 道题未导出成功，已跳过”及 ID／原因摘要，继续沿用 `skippedPdfSummary` 的可见性。

### L5 诊断改进

- 报错定位到实际失败码点，而不是片段首码点。
- 错误里附“已尝试字体及各自原因”（文件缺失／family 不符／不允许嵌入／无字形）。
- 字体名带不可见字符时，错误中输出可辨识形式（例如 `Times\u00A0New\u00A0Roman`）。

## 不改变的行为

- PUA 的来源字体严格校验、Symbol 映射、Wingdings 局部截图与基线处理。
- 双向文本、未验证脚本、零宽控制的现有拒绝与 ActualText 处理。
- Times New Roman 只支持四个系统字面，不做任意字体目录搜索。
- 排版结构、分页、书签、原子写入与失败保留旧文件。

## 测试计划

1. 新增 `FontFallbackPdfTest`：Calibri+U+223C、Times+U+223C、Times+U+2219（含 U+200B／U+FEFF／U+00A0 变体）、U+2322、U+02D9；断言导出成功、`PdfTextExtractor` 提取到原码点、页面资源含预期字体。
2. PUA 回归：未知字体 + `F070` 仍失败且不泄漏题文；Symbol 映射、Wingdings 截图不变。
3. 隔离回归：一题含无法回退的字符时其余题目仍导出，`skippedIds` 含该题，日志有原因。
4. 标题／答案行／页脚含回退字符的用例。
5. 全量现有 PDF 测试（Selectable／FillBlank／SkippedSubject／SourceFont／Direction／AssetGeometry／Layout／InlineFormula）零失败。
6. 建议用用户账号对 `56556180`、`43443919` 各重导一次做真实验收（需用户自行触发，本轮不登录）。

## 风险与备选

- Segoe UI Symbol 在 Windows 7 及以上均有；若目标机器缺失，回退到 Arial Unicode MS，再缺则明确失败（L4 保证只影响单题）。若要求完全确定性，可改为内置 Noto Sans Symbols 2（OFL，约 1 MB），代价是安装包增大。
- 将声明字体（Calibri／Arial／宋体）原生嵌入能获得更高保真，但这些字体存在多码点共用同一 glyph 的情况（如 Calibri 的 U+2219 与 U+00B7），需要额外的 ToUnicode 校正；本轮先用符号回退字体，避免扩大范围。
- L4 把“整任务失败”改为“跳过 + 报告”，属于行为变化；若不接受，可只做 L1／L2／L3／L5，但单题异常仍会中止整个任务。
