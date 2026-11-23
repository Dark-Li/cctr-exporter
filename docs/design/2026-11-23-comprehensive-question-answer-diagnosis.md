# 综合题答案缺失诊断与修复

2026-11-23。基于真实接口响应确认原因，修复已实施。

## 现象

导出「综合题」题库时，PDF 只有父题干，答案统一显示「平台未提供」，小题题干也不见了。任务日志没有 WARN/ERROR，没有「排版失败」或「已跳过题目 ID」。

## 已验证的原因

- 任务 102 导出 4 个章节、7 道综合题，4 份 PDF 全部只有父题干；题干停在「试问：」「回答：」「请回答以下问题。」，小题（1）（2）…缺失。
- 临时诊断（`-Dcctr.question.debug=true`）抓取 `getStudentPractiseQuestionList` 原始响应，共 4 道题、12 个小题。
- 父题 `questiontypename=综合题`，`answer=""`、`answerhtml_svg=""`，`options={"options":""}`（`options` 解析为空列表）。父级本来就没有答案。
- 父题 `subquestions` 是数组，元素与 `Question` 同构：`questionid`、`questiontypename=主观题`、`questiontypenumber`、`score`、`subjecthtml_svg`、`answerhtml_svg`、`answer`、`options=""`、`analysishtml_svg`。小题的 `answer` 与 `answerhtml_svg` 均有内容，HTML 可含 `<img>`。
- 小题题干 HTML 不自带序号；数组顺序即展示顺序，内部 `id` 反而是倒序，不能按 id 排序。
- 修复前 `Question` 未声明 `subquestions`，`Json { ignoreUnknownKeys = true }` 会在反序列化时静默丢弃该字段；`SelectableQuestionPdf.questionBlock` 只读父级答案，于是落到「平台未提供」。排版本身成功，所以日志没有异常。

## 修复

- `Question` 增加 `subquestions: List<Question> = emptyList()`，递归复用现有模型。
- `questionBlock` 在父题干之后优先处理小题：按数组顺序输出 `（1）` + 小题题干，再统一输出「参考答案」与逐小题编号答案。
- 小题答案优先级：有可见内容的 `answerhtml_svg` → `answer` → 选项真值 → 「平台未提供」。
- 父题与小题共用 `choicePresentation()` 计算选项前缀与正确答案，避免两套标号规则漂移。
- `answer` 的 `answer_origin!!` 改为 `orEmpty()`，避免两者皆空时旧 PNG 路径 NPE。

## 边界

- 小题不单独编号为「第 N 题」，不新增书签；书签与题号仍按父题。
- 小题数组没有嵌套 `subquestions`；当前不递归渲染更深层级。
- 小题若为选择题且答案字段为空，用选项真值输出字母；判断题沿用「正确/错误」等语义答案。
- 单个小题内容损坏时仍按现有逐题隔离策略处理，整题重排或跳过，不会留下半页内容。
