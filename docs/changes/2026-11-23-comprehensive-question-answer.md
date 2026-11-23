# 综合题 PDF 小题与答案导出

## 处理规则

「综合题」的父级只提供引导语，题干与答案都在 `subquestions`。PDF 在父题干之后按源数组顺序输出 `（1）（2）…` 小题题干，再输出一个「参考答案」区，逐小题给出编号答案。小题答案优先取有可见内容的格式化答文，其次取平台纯文本，再次取选项真值，三者都没有才提示「平台未提供」。若平台答文自身已以同一编号（如「（1）」）开头，则不再重复添加。小题题干与答案的公式、图片继续走现有的局部渲染流程。

父题与小题共用同一套选项标号规则；综合题不再因为父级答案为空的而整题显示「平台未提供」。题号、书签、续页标记仍按父题，小题不单独编号。

问题证据与字段结构见 [综合题答案缺失诊断](../design/2026-11-23-comprehensive-question-answer-diagnosis.md)。

## 离线验证

- 新增 `ComprehensiveQuestionPdfTest` 3 项：4 个小题的题干/答案顺序与缺省占位；小题为选择题时使用自身选项字母作答；答文自带编号时不重复添加。
- `QuestionTest` 新增 2 项：`subquestions` 反序列化、无小题时默认为空列表。
- `:test --tests ComprehensiveQuestionPdfTest --tests SelectableQuestionPdfTest --tests FillBlankQuestionPdfTest --tests PdfExportLayoutTest` 共 19 项通过；`:modules:cctr-api:test --tests *QuestionTest` 通过。
- 样例产物 `build/comprehensive-pdf-validation/sub-questions.pdf`、`choice-sub.pdf`、`self-numbered.pdf`，文本提取核对为「题干 →（1）…（4）→ 参考答案 →（1）…（4）」，缺答小题只出现一次「平台未提供」。

## 待办

- 用真实「综合题」题库重新导出，核对含图片/公式的小题答案和分页；本轮离线样例不含 Edge 渲染资源。
