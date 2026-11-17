# PDF 局部资源负原点捕获修复

按 [真实题目诊断](../design/2026-11-17-pdf-negative-capture-origin.md) 修复局部图片位于负视口坐标时被直接拒绝的问题。在 `codex/pdf-export-layout` 延续原有未提交的 PDF 改动，版本保持1.1.5，没有提交、合并或覆盖旧便携包／用户发布说明。

## 行为与边界

捕获范围同时保留原0原点、根盒、可见内容 union及四边保护边，再将完整范围统一平移到可截图的正坐标。没有删除资源的 absolute、负margin或自身transform，也没有把负坐标简单clamp为0。真实同形样例的28×44图片恢复为36×76 CSS捕获，图片实际rect为 `(4,27.796875,28,44)`，正top偏移及原锚点保留。

平移／预适配会使原根建立新的包含块，因此变换后逐一重测已有可见资源／文字Range的矩形，与原矩阵预期比较，允许0.25 CSS px数值误差。数量或位置／尺寸不一致时，提供 `layout-context-changed` 及纯几何元数据，仍按原失败语义保留旧归档。百分比或right等依赖原视口／外部父容器的布局没有被宣称普遍支持，也不会错位后静默成功。

扩展后的完整宽高先经过原单边／面积及物理像素预算；DPR仍独立归一化到每 CSS px两像素。CSS总高度、内容高度、baseline与底边留白均按同一坐标原点／倍率转换。捕获结果追加带默认值的 `sourceCssScale`，显式CSS vertical-align和上下标位移也与浏览器预适配同比，防止宽资源图像缩小而位移未缩小；原构造调用保持兼容。

此轮只改变 PDF局部浏览器捕获及其基线换算。原生可复制题文、Times四字面／中文回退、零宽控制、选项／填空答案、坏题干限定跳过、页码／书签及IMG管线保持。

## 验证

- 红阶段：旧正式包真实 Edge同形样例得到与用户一致的 `CSS=31.40625x75.796875；DPR=1.0；negative-origin`，证据 `build/negative-origin-pdf-review/old-product.log`。
- 正式 Gradle：`& build/negative-origin-pdf-build/gradle-local.ps1 -PdfTests`，`gradle-first.log` **38项、0失败**。新增完整bounds／原锚点／预算回归及预适配后的CSS位移倍率回归，原36项相关套件仍通过。
- 包内 Java／应用：`& build/negative-origin-pdf-build/verify-final.ps1`，`portable-final-smoke.log` **32项通过**。
- 真 Edge 157.0.4341.60、DPR1与1.25：同形负左、负top、双负、原transform、多absolute、宽预适配全部通过，四边颜色标记完整。percent／right依赖反例准确拒绝，分别报告371／742 CSS px的矩阵误差。首轮日志 `build/negative-origin-pdf-review/first-product.log`。
- 实际PDF（DPR1.25）：短图实际基线偏移−6.900pt、理论−6.902344pt；宽图sourceScale约0.428690，实际−2.440pt、理论−2.443723pt。周围中文、答文、两书签均可提取，图像完整。记录 `build/negative-origin-pdf-review/first-pdf-product.log`。
- 最终便携包内真实 Edge定点复验同样通过，身份与下述主JAR哈希一致，误差小于0.004pt；记录 `build/negative-origin-pdf-review/final-pdf-product.log`。可交付样例 `build/negative-origin-pdf-review/final-pdf-product/negative-formula-baselines.pdf`，同目录 `capture-1.png`／`capture-2.png` 为局部捕获证据。

## 交付

Windows x64便携版：`build/negative-origin-pdf-build/portable-final/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`。保留同目录 `app` 与 `runtime`；完整目录228,533,529字节，108个JAR。主JAR SHA256 `019F7E6F877A9A1D0997A2C9B60C27D82C4791986E932D042598228273FCDF9F`，版本1.1.5，收据 `build/negative-origin-pdf-build/build-receipt.json`。同版本旧包应以目录／收据区分。

没有替用户登录或重导真实题76141255，已知实证形态使用脱敏本地样例验收。已有诊断窗口均保留。本轮不保证任意外部定位依赖均可自动还原；无法验证时明确失败，不截图残缺内容或扩大跳题范围。
