# 局部 PDF 捕获负坐标诊断与方案

## 实证

用户在最新隔离日志窗口自行复现，题 `76141255`（材料工程基础／第二章／论述题）的参考答案报错：`局部内容几何失败；原因=negative-origin；类型=img；CSS=31.40625x75.796875；DPR=1.0；阶段=参考答案`。最深栈是 `DriverUtil.kt:159`，经 `PdfHtmlContent.capture:463` 包装。没有记录题文、答文或凭据。

会话为 `build/diagnostic-next-error/session-20261117-125026/`，证据在 `stderr.log`。实际运行是 `build/zero-width-pdf-build/portable-final` 的1.1.5成品，主 JAR SHA256 `E9FF937CC6C151E876EE6A72BFEEC00FEB2472E9D169A839850A91A9757784A9`。检查时 PID32920仍存活且窗口响应；本轮没有重复启动、关闭实例、操作账号或重触发任务。

`DriverUtil.kt:115–134` 收集可见图片／SVG／MathML及文字 Range 的 `getBoundingClientRect()`，取最小 `left/top` 和最大 `right/bottom`。这些是**视口 CSS 坐标**，没有先减去根容器原点。根容器矩形仅将宽高用于尺寸计算，其 `left/top`未返回。`:159` 要求内容最小 left、top 均不小于0，失败发生在 CDP 截图之前。

尺寸、稳定状态和像素预算规划已通过，随后被该 guard主动拒绝，因而本次不是宽高／面积过大、U+200C、字体缺字或 PNG 解码失败。初次日志没有具体 left/top、根原点或完整 union，当时不能确定负边界和具体样式原因；后续纯元数据复现如下。

## 后续纯元数据复现（已确认）

用户再次在独立诊断窗口自行登录、执行指定导出，已取得同题同异常。会话为 `build/diagnostic-negative-origin-runtime/session-20261117-171756/`，`stderr.log` 中 `PDF_NEGATIVE_ORIGIN_DIAGNOSTIC` 保留两轮稳定快照和触发 guard的原始 measurement，二者一致。PID3788保持运行，旧实例未动。

| 对象／字段 | 实测 CSS 数值或状态 |
| --- | --- |
| 根容器 border box | `(0,0)–(8,8)`，内容宽高0，四边 padding各4，position static |
| 唯一有效候选 | `source=element`、tag img；没有 Range 候选触发负边界 |
| 图片边界 | left `-0.59375`、top `27.796875`、right `27.40625`、bottom `71.796875`，28×44 |
| 图片 computed样式 | position absolute；left 4px、top 4px；margin-left −4.6px、margin-top 23.8px；transform none；vertical-align middle |
| 图片加载 | complete true，naturalWidth 28、naturalHeight 44 |
| 祖先／视口 | 根、body、html均static且无transform；视口750×438；scrollX/Y均0；DPR1 |
| 原捕获计划 | raw `31.40625×75.796875`，scale1，取整32×76；bottomInset4；无文字基线marker |

图片脱离普通流，因此根只剩8×8保护边。当前祖先没有建立定位包含块，图片的绝对定位按初始视口解析；computed right `722.594px`／bottom `366.203px`也分别与 `750−27.40625`／`438−71.796875`吻合。`4−4.6≈−0.6` 与实际左边 `−0.59375`一致，`4+23.8≈27.8`与实际top一致，差异为布局子像素和样式字符串舍入。**实际是图片负左边距导致左边轻微越过0，不是顶部越界。**

`PdfHtmlContent`将资源的 `outerHtml`单独交给浏览器，仍保留其定位／margin；临时根不具备原平台的父布局上下文。此例已经确认该组合触发当前0原点guard，但没有读取原始题文／HTML或平台父容器，不能断言原网页本身布局错误。也不能将computed used值等同于源HTML中明确声明的left/top。本次没有证据支持字体、方向标记、MathML或损坏自然尺寸导致异常。

诊断只在 `build/diagnostic-negative-origin-build/source/` 的隔离driver副本增加白名单快照与guard命中日志，原measure返回值、稳定key、guard条件、transform及capture保持。主应用仍为上述E9FF…84A9；诊断driver SHA256 `300E3E0EAF195979622DAB13F7FF51BB47D4479A01C3657DB93A1711DCDDBB91`。正式Driver源码 SHA256仍为 `D923013D24995B7F42E61CF84D9068244174289690A24DA0E527973B542C71F1`，未修改正式产品源码。

## 最小修复方案（尚未实施）

1. 保留非有限、空内容、加载稳定及内存预算校验。将“可绘制内容出现在负坐标”与“几何不可用”分开：负原点本身不应直接判内容损坏，也不能删除 guard后继续从 `(0,0)` 截图。
2. 在同一视口 CSS 坐标系建立完整有效 union `U=[L,T,R,B]`，包含原逻辑锚点区域、可见内容左／上负扩展及四边保护留白，尊重源内容本来就有的可见／隐藏／裁剪语义。本例不能仅裁成图片28×44并顺便移除27.8px的正top偏移，否则可能改变基线语义。不能只按 `right/bottom` 算尺寸，也不能用 `abs`、clamp到0或缩小clip丢掉左／上半边。
3. 确定等比适配比例 `s` 后，用受控外层变换将整个 union平移到正原点，例如 `matrix(s,0,0,s,-L*s,-T*s)`；不覆盖资源自己的变换、不直接删除其absolute或margin。外层变换可能建立新的包含块，实施时必须重新验证实际几何，保持资源内部相对定位。输出 CSS宽高为 `ceil((R-L)*s)`、`ceil((B-T)*s)`。最终完整范围重新经过单边与物理像素预算检查，继续使用独立 DPR换算，不能放宽上限绕过问题。不能仅给CDP负clip坐标，因为视口外内容仍可能被浏览器裁掉，应先实际完成统一平移。
4. 所有基线和防裁留白跟随同一变换。若原基线为 `baselineY`，归一化后为 `s*(baselineY-T)`；底边留白为 `outputHeight-s*(contentBottom-T)`。捕获返回的 CSS宽高、内容高度、baseline和inset必须一致，再由 PDF最终等比缩放处理。不能只移图像像素而保留旧的基线值。

尚无源几何时，最小诊断补充应为：题 ID／阶段／类型、根矩形 `[x,y,w,h]`、有效 union `[L,T,R,B]`、scroll尺寸／页面滚动偏移、DPR、适配比例、最终clip范围及基线／底边留白（若存在）。这些足以定位坐标关系，不必记录原 HTML、题文、答案或资源地址。必要时再加受限的 computed display／position／margin／transform／vertical-align数值，不输出任意style文本。

## 验收范围

合成负x、负y、双负偏移及正常原点，覆盖相对位置、transform、可见后代超出零首盒的情况。以四边标记和像素边界验证完整内容、比例及保护留白；检查纯公式、图文同行、上下标、表格缩窄、多公式、换页和实际最终基线。零／隐藏及非有限几何仍分类失败；把负扩展纳入后的超预算仍明确失败，旧PDF／ZIP保留。保持原生正文可复制，不将整题改为截图。

首轮仅只读日志／源码归档。后续按用户授权构建并启动上述纯日志诊断副本，未修改正式产品、测试或依赖，未实施行为修复、升版或提交。未读取外部题库、数据库或凭据，未抓网络payload；用户在程序内自行登录触发。以上坐标归一化仍是待实施方案。

后续用户批准后已实施，实际行为、验证证据及支持边界见 [负原点捕获修复记录](../changes/2026-11-17-pdf-negative-capture-origin.md)。本诊断保留当时的实证与方案阶段，不将原网页未知父上下文改写为已证事实。
