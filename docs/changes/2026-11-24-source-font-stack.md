# 源字体栈解析与私用区字形截图

## 处理规则

- `font-family` 解析为有序 family 列表；首字体语义保持不变，Times 识别和 Symbol 的 Unicode 映射仍只看首字体。
- 私用区字形按以下顺序处理：
  - 首字体未安装：明确失败。浏览器对缺失首字体的替换不可预测，继续保持原先的保守契约。
  - 首字体已安装但缺该字形：按声明顺序尝试后续 family，选第一个已安装且包含全部码点的字体，用该 family 截图。
  - 首字体已安装且含字形：直接截图。
- 已安装字体通过 `GraphicsEnvironment` 枚举，覆盖 TTF 与 TTC（SimSun/NSimSun），校验英文/本地化 family 名、`canDisplay` 和非 `.notdef` 字形。
- 截图仍带 `data-cctr-font-family`，Edge 侧用 `CSS.getPlatformFontsForNode` 校验实际 family 与 `glyphCount>0`；Java 侧校验失败或 Edge 实际字体不符都会明确报错。
- 错误信息新增完整「字体栈」和每个候选的失败原因，便于定位是未安装、缺字形还是通用字体族。

## 实测依据

- `WINGDNG3.TTF` 已安装，family 为 `Wingdings 3`，含 `U+F072`，字形是空心三角形。
- `simsun.ttc` 的 SimSun/NSimSun 两个字面都不含 `U+F0B4`；Edge 只声明 SimSun 时该码点渲染为空白，声明 `SimSun, Wingdings 3` 时回退为右向粗箭头。
- CP936 把 `U+F0B4` 映射到 GBK 用户区字节 `0x9E2B`，没有标准 Unicode 字符；不能按码点猜成某个符号。
- 因此 `SimSun` 单字体场景无法由导出器还原原意，只能改题；含 Wingdings 3 回退的字体栈可由本次改动保真截图。

## 缺字占位（2026-11-24 策略变更）

- 声明字体已安装、确实缺该字形、字体栈里没有其它可用字形时，输出可见占位符 `�`（U+FFFD），题目继续导出，不再整题跳过。
- 占位原因写入任务 WARN 日志：题 ID、阶段、码点、字体栈，例如「ID 44141582：字符 U+F0B4 无法还原；阶段=参考答案；字体栈=SimSun；已用 � 替代」。
- 首字体未安装、自定义 `@font-face` 未验证时仍然失败：这两种情况连源字体身份都无法确认，不能安全占位。
- 该策略替代 2026-11-16「未知私用区必须显式失败」的旧行为；现在优先保证整题不丢失，同时让缺口在 PDF 和日志里都可见。

## 验证

- `SourceFontQuestionPdfTest` 新增 4 项：Wingdings 3 成功截图、`SimSun, Wingdings 3` 回退、首字体缺失仍失败、SimSun 单字体输出占位。
- 占位用例同时断言 `�` 出现在 PDF 文本中、警告包含码点与字体栈、题目仍计为已导出。
- `TextDirectionPdfTest` 新增 1 项：占位不增加跳过计数，任务日志出现「处字符无法还原」与「已用 � 替代」。
- 全量无界面回归 60 项通过。
- 真实题库验证：任务 115（26-27 材料工程基础练习）中 `45977608` 已用 Wingdings 3 截出空心三角形（PDF 答案显示 `Q=hA △ t`），文字仍可复制。
- `44141582` 的真实字体栈为 `SimSun, sans-serif`（任务 115 日志），确认无可用回退；重导后应显示 `�` 并记录占位 WARN。
