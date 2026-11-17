# PDF 方向标记、原字体文本和局部资源几何

本轮在 `codex/pdf-export-layout`（基于 `851f208`）延续未提交的源字体修复，版本保持 1.1.5。未提交、合并或覆盖旧测试版；既有 `docs/releases/1.1.3.md` 保持原字节。批准方案与问题证据见 [统一评估](../design/2026-11-16-pdf-direction-marks-and-source-fonts.md)。

## 实际行为

U+200E 在字形判断之前按方向格式处理。当前仅支持从左到右的段落，LRM 不生成可见字形或截图；中文、英文、数字括号和希腊符号仍是可复制文字。真实 RTL 字符、RTL 方向样式和未验证的其它格式控制提供具体错误，不删除全部 Cf。样式表出现无法支持的方向规则时保守拒绝，避免把 class 的 RTL 当成默认 LTR。

显式 Times New Roman 连续文本按普通、粗体、斜体、粗斜四个真实字面嵌入；只读取已知 `times*.ttf` 文件，核对 family 和 OS/2 嵌入权限，字体缓存限定每个 PDF。中文等该字体未覆盖的字符回退到已有思源字体。粗斜支持 HTML 标签及当前支持的行内样式。字体身份仍复用已有限定 CSS／`font face` 继承规则，不增加完整 CSS 引擎；字体简写、复杂覆盖和同名自定义字体不被猜成系统字体。此轮没有增加无限字体目录搜索或其它字体的原生输出。Symbol 的已核实来源映射及 Symbol／Wingdings 的受验证局部字形继续保留，未知 PUA 不强行替换。

局部捕获等待图片 decode、字体 ready，并最多四次测量稳定几何。测量同时考虑可见文本范围和图片／SVG／MathML 后代，首层盒为零但后代有内容不会直接被当空。正的亚像素尺寸可捕获；空／隐藏内容、非有限数值、未稳定、负坐标、单边超限及像素预算超限分别有纯元数据原因。错误包含题 ID（外层）、来源阶段、类型、CSS 总尺寸、内容尺寸和 DPR，不记录题文、答案或资源地址。

正尺寸资源先按页面／单元格可用宽度整体等比适配，再在同一视觉坐标系捕获；保护留白、基线和内容高度同步换算。捕获仍为每 CSS px 两个输出像素，并在分配像素前限制单边和 800 万 CSS 像素面积（约 3200 万输出像素）。未扩大上限，也不以缩 clip 裁内容。正文／表格已有结构继续原生分页；**没有实现任意复杂公式的自动切片**。无法在当前比例内安全处理的超高资源仍明确失败并保留旧文件，不静默跳题。

## 验证证据

- 红阶段：`build/direction-font-pdf-build/gradle-red.log`，新增三项按预期失败，复现 LRM 缺字、Times 无原生字体和方向分类缺失；旧成品真实 Edge 捕获对照见 `build/pdf-text-layout-review/geometry-old-product.log`。
- 正式 Gradle：`& build/direction-font-pdf-build/gradle-local.ps1 -PdfTests`，`gradle-final.log` 为 **34 项、0 失败**，包含原先填空、损坏题干跳过、书签／编号、公式基线和分页套件。
- 便携包：`& build/direction-font-pdf-build/verify-final.ps1`，包内 Java／应用 JAR **28 项通过**；主 JAR SHA256 `9339F9CA3663AF1390A26E4EBAEF5BF923021801BCD45A8E8A54AEDE98B4F55F`，完整收据 `build/direction-font-pdf-build/build-receipt.json`。
- 独立原字体验收：Times 四字面均有 FontFile2／ToUnicode，中文使用思源，无文本截图，LRM 无可见字形；准确提取正文／答案。日志 `build/pdf-text-layout-review/native-times-final.log`。
- 真实 Edge 157.0.4341.60 的局部几何：tiny 正常；zero／hidden 明确 `zero-content`；零首盒可见后代正常；4100px 宽资源四边标记完整并等比适配；3500×3000 资源适配为有界捕获；11000px 高资源明确 `dimension-limit`。日志 `build/pdf-text-layout-review/geometry-final-product.log`。这些均为本地合成样例，无账号登录或真实题库重触发。
- 两份一页 PDF 已用拒绝外部字体回退的 PDFBox 渲染并逐页查看，Times 普通／粗／斜／粗斜、中文和希腊符号均清楚，未见 LRM 方框或异常间距。PDF／PNG 为 `build/direction-font-pdf-validation/{times-faces.pdf,times-page-1.png,ltr.pdf,ltr-page-1.png}`，渲染记录 `build/direction-font-pdf-build/render.log`。

## 交付与边界

新 Windows x64 便携测试版：`build/direction-font-pdf-build/portable-final/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`。完整目录 228,515,047 字节，包含 108 个运行 JAR；应保留同目录 `app` 和 `runtime`。未启动用户界面，未操作既有诊断实例。

用户报告的源字体私用字符、LRM 和尺寸错误没有用真实账号重新导出验收。题 `42732302` 的原始几何仍未知，本轮修复常见小／空尺寸分类及合理大尺度适配，并提供再次失败所需的最小诊断值，不宣称已证明该题具体原因或所有大型资源均可导出。原字体原生输出当前限定 Times New Roman 四字面；缺失／不可嵌入／身份不明的字体和未验证方向仍会明确失败。

后续用户在隔离日志实例复现了参考答案 U+200C，被上述通用格式控制守卫拒绝。补齐的语义分类、ActualText、独立 DOM 片段及禁断行范围见 [零宽控制修复](2026-11-17-pdf-zero-width-controls.md)，该记录取代本记录中“其它格式控制均拒绝”的初始边界。
