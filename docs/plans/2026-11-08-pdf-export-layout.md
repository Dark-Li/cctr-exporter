# 题库 PDF 排版实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让现有截图题库 PDF 适合 A4 打印、连续阅读和按题跳转。

**Architecture:** 将 PDF 转换从通用文件目录工具中移至独立 `PdfExport.kt`，保留旧的扩展函数调用签名，添加可选分组信息。使用 iText 底层画布明确控制图片缩放和切页，题图在页间复用，不改变采集代码。

**Tech Stack:** Kotlin 1.9.22 / Java 11 字节码 / 现有 iText 8.0.4；工作区 Java 17 及离线 Kotlin 编译器用于验证；PDFBox 3.0.3 仅为验证工具。

**Spec:** [批准的设计](../design/2026-11-08-pdf-export-layout.md)。2026-11-08 用户已批准“按此方案实施”。

## Global Constraints

- 沿用现有截图，不改变登录、题库接口或答案内容。
- 所有生成物与缓存留在工作区，技能文件仅只读；不覆盖既有发行安装器。
- 保留 `docs/releases/1.1.3.md` 的既有用户修改，不提交或推送。

## Review Focus

- 超长题的源图片不能因分页遗漏或重复像素；无空白行时仍应连续分段。
- 空白透明区域不应导致额外空页；颜色较浅的题图不能被误当空白丢弃。
- 文件名自然排序应兼容长数字和同数字不同前导零。
- 超长课程名、章节名不能覆盖题图、页码或题号。
- 坏图或空输入应失败，且不能留下看似成功的目标 PDF。

## Task 1：离线回归与 PDF 布局

**Files:** 新建 `src/test/kotlin/PdfExportLayoutTest.kt`、`src/main/kotlin/top/kagg886/cctr/desktop/util/PdfExport.kt`；修改原 `file.kt` 移除旧转换实现。

**Interfaces:** `List<File>.convertToPDF(file: File, details: PdfExportDetails): Boolean`。保留原单参数扩展函数，由文件名提供默认标题。

- [x] 先在旧实现上执行 4 项回归，复现题号缺失、长题缩成一页、空输入被当作成功及坏图留下半成品。
- [x] 实现 A4 版面、中文分组信息、连续题号、页眉页脚、题目书签、短题保持完整、长题空白行优先分页。
- [x] 使用离线 Kotlin 编译器生成 Java 11 字节码，最终 6 项回归全部通过。

## Task 2：任务整合和视觉验证

**Files:** `TaskManager.kt`、临时目录工具、`docs/changes/2026-11-09-pdf-export-layout.md`。

**Interfaces:** 调用 Task 1 的 `convertToPDF`，传入课程/章节/题型；检查返回值，失败抛错以进入现有任务失败分支。

- [x] 将题库分组信息传入，过滤非题图文件，显式检查转换结果，并让零题 PDF 任务失败。
- [x] 编译实际改动的 `TaskManager.kt`、PDF 工具和临时目录工具，复跑回归。
- [x] 输出代表性 PDF，渲染 8 页为 PNG，逐页检查中文、分页、题图缩放、留白和页码。
- [x] 独立代码审查与 `git diff --check`，在变更文档记录命令、结果、产物及限制。

## 执行记录

- 基线：旧版对 5 个离线题图输出 `baseline.pdf`，出现 5 次 `Element does not fit current area` 警告；已渲染前两页。
- 执行方式：按用户要求，具体实施由子线程完成，主线程调度和反馈；另一个只读子线程独立审查。
