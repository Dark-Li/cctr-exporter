# 题目 54058673 导出 PDF 时读取题干失败

## 复现与结论

2026-11-10，使用公式修复版的原始 Windows 应用与独立工作区配置启动诊断。用户手动登录和触发导出。诊断任务 #1 查询到 223 题，随后在 `54058673` 同样失败。日志仅保留以下必要结论，原始响应和凭据不进入文档。

最深异常为 `java.io.EOFException`，调用链为 `GZIPInputStream.readUByte → readUShort → readHeader → SecurityKt.decompress(security.kt:37) → Question.getSubjectHtml(question-list.kt:67–68) → SelectableQuestionLayout.questionBlock(SelectableQuestionPdf.kt:94)`。`Base64.getDecoder().decode(...)` 在创建 `GZIPInputStream` 前已返回，构造器读取 gzip 头时提前遇到输入结束。错误发生在题干的 `subjecthtml_svg` 字段，尚未解析题干 HTML、调用 Edge 或排版该题。

成品 Java 栈的 gzip 帧没有源码行号。同款 Corretto 17 的 `readHeader` 在格式标识、可选扩展长度和头校验处都会调用 `readUShort`，单凭这份栈不能判断字段为空、只含一个字节，或截在可选头字段，也不能确定平台生成、存储或传递过程中何处异常。仓库现有 DTO、示例及测试只证明存在 `subjecthtml_svg`，暂无证据支持普通 `subjecthtml` 作为备用。接口的 `id` 在当前代码中用于分片分页，暂无按题 ID 请求的依据。

## 建议修复

1. 在题干解码处明确区分缺省、Base64 无效、gzip 头或内容不完整。保留原异常因果链，给用户显示题号、字段与可理解的原因。诊断只记录错误类型和必要长度，避免保留题文、答文、登录凭据及请求参数。
2. 首选成功解码的 `subjecthtml_svg`。仅在脱敏元数据验证平台确实提供同题的有效原文备用字段后，才把它加到 DTO 并在主字段失败时使用。对于可能短暂的坏载荷，可以限次重取已知的同一分片，或沿原题库选择重新查询，按同一 ID 核对后再尝试一次。连续得到同样坏数据时停止重试。
3. 若依然无可用题干，明确报告受影响的题 ID，沿用 PDF/ZIP 的原子失败行为，避免把空题干或少题的文件标成完整导出。若用户希望保留其余题目，可另行提供明确标记的部分导出，列出失败题 ID 和原因。

## 验收

覆盖有效 gzip、空值、短 gzip 头（含可选头）、损坏的正文、无效 Base64。若加入备用字段或重取，验证同题优先级、限次和无有效备用的错误。复查正常选择题、填空、公式和书签，模拟一组中单题损坏，确认失败不会覆盖已有 PDF/ZIP。最终由用户用同一题库重导。此次仅复现与诊断，产品源码未改。
