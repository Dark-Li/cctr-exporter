# 公式 PDF 局部渲染修复

## 修复

Edge 局部截图去除页面 CSS `zoom`，待资源解码、页面字体就绪和两帧绘制后，以公式容器的 CSS 尺寸截图。截图和测量使用同一坐标系，按设备像素比换算成 2 倍像素，四周留透明边。PDF 用原逻辑尺寸等比缩放到题干、选项或表格可用宽度。尺寸不符会触发带题目 ID 的原有导出错误。

iText 仅直接处理可识别的基础图形 SVG。含文字或字体、CSS 尺寸或其他未知语义的 SVG 与 MathML 交给 Edge 局部渲染。周围正文、选项与答案说明仍使用可提取的 PDF 文字。图片导出继续使用原路径。问题证据见 [公式渲染诊断](../design/2026-11-09-pdf-formula-rendering-diagnosis.md)。

## 验证

- 在离线 Edge 157.0.4341.42 上，旧元素截图把 CSS 视觉范围约 352×157 的公式截成 176×79，底边分母和右侧标记消失。同一旧页面通过 CDP 捕获视觉矩形可保留四边，确认缺失产生在元素截图。去除 `zoom` 后，带留边的 629×109 超视口样例在 DPR 1、1.25、2 均生成 1258×218 位图，四边及分母完整。
- 新增的端到端回归在旧实现失败，旧版只把 MathML 交给 Edge。修复后，含 `ℝ`、`𝑥` 的 SVG 与 `em` 尺寸 SVG 也按局部资源交给 Edge，周围原生文字仍可提取。正式 Gradle 测试 `:test --tests SelectableQuestionPdfTest --tests FillBlankQuestionPdfTest --tests PdfExportLayoutTest` 共 16 项通过，便携包 Java 与 JAR 上 10 项测试通过。
- 用最终便携包的 Java 和应用 JAR 调用真实 Edge，DPR 1、1.25、2 各导出一页。每份题干、选项与答案的三个公式均有完整四边标记，宽式比例正确，三对周围中文仍能提取。另用 PDFBox 渲染并目视确认了 DPR 1.25 的整页。
- 便携包在真实 Edge 中把 `4em`×`2em` SVG 渲染为 64×32 CSS 像素，留边后 PNG 为 144×80。目视确认 `ℝ`、`𝑥` 字形完整。另查 MathML 的分子、分母、上标和下标均完整，记录在 `product/glyph-packaged.log`。
- 浏览器 A/B 在独立工作区配置上运行。现有 EdgeDriver 156 与本机 Edge 157 的版本检查仅在离线测试中显式关闭，产品的驱动设置照常。真实账号与 `task_68` 重导需要用户验证。

## 工作区产物

- 启动器 `build/formula-pdf-build/portable-final/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`。保留同目录 `app`、`runtime`。完整目录 228,442,585 字节。
- 离线公式示例 `build/formula-pdf-edge-review/product/product-dpr1.25.pdf`，整页预览同目录的 `product-dpr1.25-page-1.png`，包内实测记录为 `verify-packaged.log`。
- 构建日志和校验收据在 `build/formula-pdf-build/`。浏览器 A/B 位图和记录在 `build/formula-pdf-edge-review/`，其中 `narrow-old-element.png` 和 `narrow-new-cdp.png` 对照了分数下缘与右侧的缺失及恢复。
