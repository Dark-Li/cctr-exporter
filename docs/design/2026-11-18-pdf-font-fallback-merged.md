# PDF 字体回退与逐题隔离：合并修复方案

- 日期：2026-11-18
- 分支：`pdf-bux-fix`（基于 `main` = `9add8e4`）
- 状态：**已实施**（2026-11-18），实现与验证记录见 [变更记录](../changes/2026-11-18-pdf-font-fallback.md)。已决策：字体策略采用 A（系统字体回退），接受 L4（逐题跳过 + 报告）。工作树中既有的并行分组改动（`PdfTaskExport.kt`、`2026-11-17-pdf-group-parallel.md`）不属于本轮，保持原样。
- 本文合并两份方案：本地的字体回退方案，以及 codex 的“随包数学字体”方案。取舍见第 10 节。

## 结论摘要

1. 两例报错分属两类：U+223C 是**字体确实缺字**；U+2219 是**字体名未归一化导致 Times 分支被跳过**，Times 本身有该字形。
2. 只加回退字体会把第二例“盖住”，但字体身份错误仍在：Times 拉丁段落会静默落回思源，PUA／Symbol 仍可能被同一个不可见字符打断。因此必须先做字体名归一化。
3. 回退链按“声明字体优先”排序：Times → 思源 → Segoe UI Symbol → Arial Unicode MS。字体策略采用 A，本轮不随包字体。
4. 单题字形失败采用**逐题跳过 + 明确报告**（已确认）；截图、几何、结构类异常仍快速失败。
5. 所有文本出口（正文、标题、参考答案行、表格、页眉页脚）统一走同一套字体解析，避免只修正文。

## 1. 现象与证据

1.1.6 上两个题库各有一题导致整个导出任务失败：

- 题 `56556180`（应用题，阶段=题干）：`PDF 字体不支持 U+223C；源字体=Calibri；来源=行内CSS`
- 题 `43443919`（计算题，阶段=参考答案）：`PDF 字体不支持 U+2219；源字体=Times New Roman；来源=行内CSS`

合成题复现（当前 `main`，iText `containsGlyph`）：

| 输入 | 结果 |
| --- | --- |
| `Calibri` + U+223C | 失败：`U+223C；源字体=Calibri` |
| `Times New Roman` + U+2219 | **成功** |
| `Calibri` + U+2219 | 失败（Calibri 本身有该字形） |
| `Times New Roman` + U+223C | 失败（Times 确实没有） |
| `Times New Roman` + U+2322 | 失败 |
| Times 字体名中混入 U+200B／U+FEFF／词间 U+00A0，再加 U+2219 | 失败，报错文本与截图一致 |

字体覆盖：

| 字体 | U+223C | U+2219 | U+2322 |
| --- | --- | --- | --- |
| 内置 SourceHanSansCN | 否 | 否 | 否 |
| Times New Roman（四字面） | 否 | **是** | 否 |
| Calibri（四字面） | 否 | **是** | 否 |
| Segoe UI Symbol | 是 | 是 | 是 |
| Arial Unicode MS | 是 | 是 | 是 |

50 个常见题库符号中，思源缺 9 个；思源 ∪ Segoe UI Symbol 缺 0 个。

## 2. 根因

- **R1 只有两档字体**：`PdfHtmlContent` 只认内置思源和声明为 Times New Roman 的字体；其它声明字体（Calibri／Arial／宋体）不会被加载。
- **R2 缺字后走错分支**：思源与 Times 都没有的字符进入 `renderSourceGlyph`，而 `verifiedBrowserFamily` 只接受 Symbol／Wingdings，于是对普通 Unicode 字符也报“需要核实源字体及字形”。
- **R3 字体名按字面比较**：`PdfSourceFont.from` 只做 `trim()`。零宽字符、BOM、词间 NBSP 会让 `"Times New Roman"` 不相等，Times 分支整段跳过；报错会把不可见字符过滤掉，看起来仍是 `Times New Roman`，极易误判为缺字。同一问题还会让整段 Times 拉丁文静默落回思源。
- **R4 失败被放大**：单题异常 → 整组失败 → 整个归档任务失败。
- **R5 诊断不足**：只报片段首码点，不列已尝试字体，无法区分“缺字”和“身份没匹配上”。

## 3. 方案总览

| 层次 | 内容 | 主要文件 | 验收 |
| --- | --- | --- | --- |
| L1 | 字体名归一化 | `PdfSourceFont.kt` | 三种不可见字符变体不再误判 |
| L2 | 分层字体解析与回退 | 新增 `PdfFontResolver.kt`；`PdfHtmlContent.kt` | 两例导出成功，提取码点正确 |
| L3 | 全文本出口覆盖 | `PdfHtmlContent.kt`、`SelectableQuestionPdf.kt` | 标题／答案行／页脚用例通过 |
| L4 | 逐题隔离与报告 | `SelectableQuestionPdf.kt`、`PdfTaskExport.kt` | 一题失败不影响其余题目 |
| L5 | 诊断与 ToUnicode | `PdfSourceFont.kt`、`PdfFontResolver.kt` | 报错含实际码点与尝试记录；提取不出现 FFFD |

## 4. 详细设计

### 4.1 L1 字体名归一化

新增 `normalizeFamily(raw: String): String`，在 `PdfSourceFont.from` 构造 `family` 时应用一次，所有比较都用归一化结果：

1. `Normalizer.normalize(raw, Form.NFKC)`（全角字母、NBSP 等兼容字符归一）。
2. 删除 Unicode 格式类字符（`\p{Cf}`：U+200B、U+FEFF、U+200E 等）。
3. 合并连续空白为单个空格，去首尾空白。
4. 去掉包裹的 `'`、`"`、`‘’“”`。

以下位置统一改用归一化后的名字：`nativeTimesBytes`、`mapSymbol`、`verifiedBrowserFamily`、`@font-face` 同名判断。错误信息输出归一化后的名字，零宽字符不会再让日志看起来“字体名一样却匹配不上”。

> 归一化只影响字体身份比较，不改变题文内容。

### 4.2 L2 候选字体与解析顺序

按字符逐个解析，顺序如下（命中即停）：

1. 声明的 Times New Roman 四字面（现状，修正后才能可靠命中）
2. 内置 SourceHanSansCN
3. Segoe UI Symbol（`%WINDIR%\Fonts\seguisym.ttf`）
4. Arial Unicode MS（`arialuni.ttf`，存在才用）

每个候选在首次使用前核验：

- 文件／资源存在；
- `Font.createFont` 读出的 family 与预期一致；
- `OS/2.fsType and 0x0302 == 0`（允许嵌入、允许子集）；
- 目标码点 `containsGlyph` 为真，且 `getGlyph(point).code != 0`。

通过后 `PdfFontFactory.createFont(bytes, IDENTITY_H, FORCE_EMBEDDED, false)` + `setSubset(true)`，按 `PdfDocument` 缓存，不跨文档共享。

回退字体只有常规字面：粗体 run 沿用 `Text.setBold()`；斜体**不在回退字体上模拟**——实测合成斜体会在后续 run 前插入空隙并带进文本提取（`C∙D` 提取成 `C∙ D`），符号保持常规倾斜，Times 仍使用真实斜体字面。

**字体策略（已定：A）**

- **A（采用）**：只用系统字体（3、4）。零安装体积，覆盖 50 个抽样符号的 100%。
- **B（本轮不做，保留为后续增强）**：把 Noto Sans Math（或 Noto Sans Symbols 2）放进 `src/main/resources/fonts/`，插到 Segoe UI Symbol 之前，作为新的第 3 档。代码按资源是否存在决定是否启用，后续加字体不改逻辑。触发条件：出现系统字体也覆盖不到的字符，或需要跨机器完全一致的 PDF 外观。启用前需实测覆盖矩阵、OFL 许可与 `fsType`。

> 不建议在本轮加载任意声明字体（Calibri／Arial／宋体）。这些字体存在多码点共用同一 glyph（如 Calibri 的 U+2219 与 U+00B7），需要额外的 ToUnicode 校正；TTC（宋体、微软雅黑）iText 8.0.4 也不能直接按字节加载。

### 4.3 L2 逐字符路由

```kotlin
internal sealed interface GlyphRoute {
    data class Native(val font: PdfFont) : GlyphRoute      // 原生文字
    data object SourceGlyph : GlyphRoute                   // PUA：现有受验证截图
    data class Unsupported(val reason: String) : GlyphRoute
}

internal class PdfFontResolver(bundled: PdfFont, pdf: PdfDocument) {
    fun route(point: Int, source: PdfSourceFont?, bold: Boolean, italic: Boolean): GlyphRoute
}
```

`PdfHtmlContent.visit(TextNode)` 改为：

- `Native` → `flushSource()`，切换 run 字体，追加码点；
- `SourceGlyph` → `flushNative()`，进入现有 `sourceRun`／`renderSourceGlyph`；
- `Unsupported` → 抛 `PdfGlyphNotSupportedException`（携带码点、阶段、源字体、已尝试候选及原因）。

PUA 规则不变：只有 Symbol／Wingdings 的已核实映射与受验证局部截图；非 PUA 才允许回退。

### 4.4 L3 全文本出口

`PdfHtmlContent.pdfText` 拆成按字体切 run 的辅助方法：

```kotlin
data class PdfTextRun(val font: PdfFont, val text: String)
fun runs(value: String, bold: Boolean = false): List<PdfTextRun>
```

改造点：

- `heading()`、`answerLine()`：先建空 `Paragraph`，再逐 run `add(Text(...))`；
- 正文 `pendingLabel`、`pdfText("\n")`：保持单 run 时直接返回 `Text`；
- `addPageFurniture()`／`canvasText()`：按 run 逐个 `setFontAndSize` 并推进 x，`fit()` 用同一套测量；
- 表格、列表沿用现有 `addNodes`，自动获得新逻辑。

### 4.5 L5 ToUnicode 保真

回退字体的字形可能与其他码点共用，或没有 unicode。沿用现有思源 Han 字形的做法：取到 glyph 后若 `glyph.unicode != point`，调用 `glyph.setChars(Character.toChars(point))`，保证 `PdfTextExtractor` 提取回原码点。

实测风险：Segoe UI Symbol 的 U+223C／U+2219／U+2322 可正确提取；U+02D9 在单独子集时会提取成 U+FFFD。必须用逐码点提取测试覆盖，不能只看“导出成功”。

### 4.6 L4 逐题隔离

新增 `PdfQuestionSkippedException : IllegalArgumentException` 作为内容级失败的基类：`PdfGlyphNotSupportedException`（字体／字形）与 `PdfContentNotSupportedException`（未验证格式控制、双向文本、复杂连接语境、方向样式）分别继承。`SelectableQuestionLayout.write` 改为：

```kotlin
var exported = 0
for (question in questions) {
    val block = try {
        questionBlock(exported + 1, question)
    } catch (error: PdfQuestionSkippedException) {
        failures += question.id to error.message
        continue
    }
    exported++
    measureAndAdd(block)          // 布局期异常仍快速失败
    addOutline(exported)          // 书签移到成功之后
}
```

要点：

- **书签顺序**：当前 `questionBlock` 在构建内容前就 `addOutline`；跳过题目会留下指向不存在锚点的孤立书签。必须改为内容构建成功后再加。
- **题号连续**：按成功导出的题目编号，标题中的真实 ID 保留可追溯性；跳过题记入现有 `skippedIds` 通道。
- **结果传递**：`QuestionPdfResult` 增加 `failureMessages`（题目 ID + 原因），由 `PdfTaskExport` 写入任务日志；不改现有 `skippedIds` 的对外语义。
- **日志**：沿用 `skippedPdfSummary` 与“已跳过题目 ID：”，追加失败原因（每行限长，沿用现有 200 字符切分）。
- **不吞其它异常**：截图、几何、页面结构错误照旧抛出，避免把真实缺陷当成可跳过。
- **零宽控制**：按 Unicode 类别判定——`General_Category=Cf` 且不参与双向重排的字符一律零宽处理并保留 ActualText，不再维护字符清单；双向控制（LRM／RLM／ALM／U+202A–202E／U+2066–2069）与未验证脚本走内容级跳过，而不是整任务失败。

### 4.7 L5 诊断格式

示例：

```
PDF 字体不支持 U+2219；阶段=参考答案；源字体=Times New Roman；来源=行内CSS；
已尝试=SourceHanSansCN(无字形)、Segoe UI Symbol(无字形)、Arial Unicode MS(文件缺失)
```

同时满足：不含题文／答案／资源地址；题号由外层补充；字体名带不可见字符时输出可辨识形式。

## 5. 不变量

- PUA 语义与严格校验：Symbol `F070→π`、`F0BB→≈`，Wingdings 局部截图、基线、ActualText。
- 双向文本、未验证格式控制、复杂连接语境改为内容级异常并只跳过该题；已验证的零宽控制（含 U+00AD、U+200B、U+2061–U+2064）继续保留 ActualText。
- Times 只支持四个系统字面，不做字体目录扫描。
- 排版结构、分页、书签、原子写入；失败保留旧导出文件。
- 不替换字符、不删字符、不静默把符号改成 `?`。

## 6. 测试计划

**单元**

1. `normalizeFamily`：U+200B／U+FEFF／词间 NBSP／首尾 NBSP／全角／引号包裹。
2. 归一化后 Times 拉丁文仍使用 Times（防止修完只过符号、正文却落回思源）。
3. Symbol + NBSP 的 `F070` 映射仍生效；同名 `@font-face` 仍拒绝。

**集成（新增 `FontFallbackPdfTest`）**

| 用例 | 断言 |
| --- | --- |
| Calibri + U+223C | 成功；提取含 U+223C |
| Times + U+223C | 成功；由回退字体提供 |
| Times + U+2219 | 成功；页面资源含 Times，不是回退字体 |
| Calibri + U+2219 | 成功 |
| U+2322、U+02D9 | 提取码点与输入一致，无 U+FFFD |
| 中文 + 回退字符 + Times 混排 | 顺序、可提取、基线不漂移 |
| 粗体／斜体／下划线／上下标 + 回退字符 | 样式保留，不破行 |
| 窄表格长回退串 / 跨页表格 | 换行与分页正常 |
| 标题、参考答案行、页脚含回退字符 | 三个出口都成功 |
| 未知字体 + `F070` | 仍失败，码点与阶段正确，不泄漏题文 |
| 三题中间一题失败 | 其余两题导出；`skippedIds` 含失败题；日志有原因；无孤立书签 |
| 截图失败（模拟 renderComplex 抛错） | 仍快速失败，未被当成可跳过 |

**回归**：Selectable／FillBlank／SkippedSubject／SourceFont／Direction／AssetGeometry／Layout／InlineFormula 全量。

**真实验收**：由用户对 `56556180`、`43443919` 重新导出；检查页面显示、文本提取、书签与相邻题目排版。本轮不登录、不代触发。

## 7. 实施阶段

- **P0**：L1 归一化 + `PdfFontResolver`（Times／思源／Segoe／Arial Unicode）+ 正文路由 + 异常类型 + 两例回归。
- **P1**：L3 全出口 + L4 隔离（已确认采用）+ ToUnicode 校正 + 全量回归。
- **后续（本轮不做）**：策略 B 的随包 Noto 字体、覆盖矩阵、安装包与许可验证。

P0／P1 各自独立提交，便于单独回滚；不改数据库、不做数据迁移。

## 8. 风险与回滚

- 回退字体依赖 Windows 自带字体，跨机器版本可能有细微字形差异；Segoe UI Symbol 自 Windows 7 起随系统，Arial Unicode MS 缺失时自动跳过。
- 多字体混排可能改变行高；沿用 `setMultipliedLeading(1.35f)`，用基线／分页用例验证。
- 零宽禁断行（U+2060／U+FEFF）跨字体仍按现有规则明确拒绝；回退只会让原本缺字的字符先报字形错误，不新增静默行为。
- `glyph.setChars` 会修改字形对象；字体按文档加载并 `cached=false`，影响范围限于当前 PDF，需用提取测试确认。
- L4 已确认采用，会改变“失败即中止”的既有语义；缓解措施是只捕获 `PdfQuestionSkippedException`（字形与内容两类），截图、几何、结构异常仍快速失败，跳过题在日志中列出 ID 与原因。
- 回滚：按阶段 revert 对应提交即可；`main` 未受影响。

## 9. 决策记录

1. **字体策略：A**。只使用系统字体（Segoe UI Symbol → Arial Unicode MS），本轮不随包字体。B 作为后续增强保留，触发条件见 4.2。
2. **L4：接受**。单题字形失败与内容级不支持（格式控制、双向文本）改为跳过并记录，不再中止整个导出任务；其它异常仍快速失败。

## 10. 与两份原方案的取舍

| 议题 | codex 方案 | 本地方案 | 合并结论 |
| --- | --- | --- | --- |
| U+223C | 缺字，需回退 | 同 | 采纳 |
| U+2219 | 判为“现有字体均无法提供字形” | 字体名未归一化，Times 被跳过 | **采纳本地判断**，有复现证据 |
| 字体身份 | 未涉及 | L1 归一化 | 采纳，作为前置 |
| 回退字体 | 随包 Noto Sans Math | 系统 Segoe／Arial Unicode | **采用 A（系统字体）**；B 留作后续，触发条件见 4.2 |
| 文本出口 | 未涉及 | 覆盖标题／答案行／页脚 | 采纳本地 |
| 失败处理 | 报错并保留旧导出（任务仍失败） | 逐题跳过 + 报告 | **采用 L4**，保留可见性 |
| 诊断 | 题号／阶段／码点 | 加实际码点与尝试记录 | 合并 |
| ToUnicode | 只在测试中检查 | 明确校正机制 | 采纳本地 |
| 视觉回归 | 基线／换行／分页 | 偏重文本 | **采纳 codex 的检查项** |
| 随包字体许可 | 明确要求先验证 | 未展开 | 采纳 codex 的严谨性 |
