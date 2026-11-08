# 登录验证码接入记录

当前正式发行与安装包记录见 [1.1.3 Windows 发行记录](../releases/1.1.3.md)。下文保留各阶段检查点；早期“未提交”描述为当时状态，最终修复纳入本次 1.1.3 本地提交。

## 目标与接口证据

在原有学校代号、用户名、密码登录界面加入图片验证码，支持刷新、加载失败重试与登录失败后换图。沿用 Kotlin / Compose Desktop / Ktor，不接入识别服务。

2026-11-08 检查融智云考学生端公开脚本：

- 页面：<https://www.cctrcloud.net/exam/>
- 登录实现：<https://www.cctrcloud.net/exam/js/modules/login_common.js>
- 图片请求：`GET https://api.cctrcloud.net/mobile/index.php?act=seccode&op=makecode&t=<时间戳>`。
- 登录请求：原 `act=login` 表单新增 `captcha` 字段。
- 官网登录使用 `xhrFields: {withCredentials: true}`；图片请求与提交必须保留同一个 Cookie 会话。
- 匿名图片请求实测：HTTP 200、`image/png`、1188 字节，响应设置 `PHPSESSID` 和 `4A17_seccode` Cookie；没有提交真实登录凭据。

不把验证码存入配置文件或任务数据库。原程序在选择课程后重新登录，且后台重复登录两次；验证码接入必须同步让导出任务复用已认证的客户端，避免消耗或丢失验证码。

## 实施与验证计划

1. 离线协议测试：本地 HTTP 服务检验图片 Cookie、刷新后的 Cookie、验证码字段、空值阻断及错误后重试。
2. API：增加独立登录会话，图片和登录共用客户端；失败保留会话以便刷新。
3. 界面：密码下新增验证码图片、输入框和刷新按钮；加载中与提交中防重入。
4. 导出：传递并复用已登录客户端；进程重启后没有会话时提示重新登录。
5. 编译、离线测试、独立代码审查，并在本文件留存实际结果与限制。

## 工作区与执行边界

- 改动前本地基线提交：`cd4a5b3`，保留原有 Edge 驱动和打包改动；未推送。
- 用户允许读取本机 Java / Gradle / 依赖缓存及相关技能；所有新增构建缓存和产物写入当前项目 `.gradle/`、`build/` 或模块 `build/`。
- 本次不修改远程服务、不部署、不读取真实账号配置与密码文件。

## 验证结果

- 全项目 `compileKotlin` 通过，覆盖主桌面模块以及 cctr-api、util、web-driver-api 模块。
- 四个离线协议测试真实执行并全部通过（4/4，进程退出码 0），包括 Cookie 绑定与验证码提交、刷新替换 Cookie、空值阻断、服务端错误后重新刷新并登录。
- 本地 Gradle 测试 Worker 曾因 JDK 11 在中文项目路径下的参数文件编码出现 `GradleWorkerMain` 加载失败；先通过直接运行已编译测试方法确认 4/4，通过验证专用 JVM 参数 `-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK` 后，正式 Gradle 测试也通过。没有修改项目原有 UTF-8 构建配置。
- 临时测试运行器：`build/validation-tools/RunCaptchaTests.java`（反射发现 `@Test`，逐个执行，任何断言异常退出码 1）；用项目内 Gradle 生成的 test runtime classpath，通过本机 JDK 11 的 `java -cp <classpath> build/validation-tools/RunCaptchaTests.java` 运行，避开 Worker 参数文件。`user.home`、`java.io.tmpdir` 指向项目 `build/validation-home`、`build/validation-tmp`。
- 正式命令：`gradle --no-daemon -Porg.gradle.java.installations.auto-download=false -Pkotlin.compiler.execution.strategy=in-process "-Dorg.gradle.jvmargs=-Xmx2048m -Dfile.encoding=GBK" :modules:cctr-api:test --tests top.kagg886.cctr.api.CaptchaLoginTest compileKotlin`。使用本机 Gradle 8.5 / JDK 11，工具链路径显式指定，Gradle 用户缓存为项目 `.gradle/agent-home`，本机依赖缓存仅只读共享。
- 正式报告：`modules/cctr-api/build/test-results/test/TEST-top.kagg886.cctr.api.CaptchaLoginTest.xml`，`tests=4, skipped=0, failures=0, errors=0`；命令退出码 0，`BUILD SUCCESSFUL`。
- 独立代码审查完成：表单预填、输入保留、刷新/提交防重入、会话关闭、重试配置恢复均已复核，无新增阻断问题。
- 隔离登录预览通过：直接渲染真实 `AddPage`，空账号情况下实际加载并显示验证码图片、刷新按钮和验证码输入框。预览源码仅位于 `build/validation-tools/captcha-preview`，未打入分发应用；未输入验证码、未提交真实账号。
- 新应用 exe 启动检查：进程可启动并响应，配置与日志指向项目内测试目录；测试目录使用占位 Edge 配置，完整主入口的 Edge 初始化未作为真实环境验收。登录界面图片显示由上述隔离预览验证。本次验证进程已关闭。
- 最终 `git diff --check` 通过。验证码改动未提交、未推送；基线 `cd4a5b3` 保持不变。

## 可运行产物

- 入口：`build/verification-captcha/app/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`。
- 请保留整个 `CCTRCloudExportDesktop` 目录，包含 `app` 与 `runtime`，不能只复制 exe。
- 首轮由本机 JDK 17 `jlink` / `jpackage --type app-image` 生成，均退出码 0，应用目录自带运行环境。108 个应用 jar/依赖已打包；首轮主 jar SHA-256 为 `C65A5B360602A43C708DB5DD76185A2DEBA2C93105F3ED865809D2BFC07605EC`。后续修复的当前哈希与增量更新方式见下文。
- 原有 `build/compose/binaries/main/exe` 安装包未更新、未覆盖。Compose 的 WiX 自动下载被 GitHub 超时阻断，故交付独立应用目录，没有生成新安装器。

## 会话与重试行为

- 课程选择及导出提交携带同一个已验证客户端；任务执行不再重复登录，结束后关闭客户端。
- 每次点击旧任务“重试”进入验证码登录，预填原任务账号；认证后恢复原课程配置与导出方式。
- 验证码与 Cookie 只保留在内存，不写入任务数据库。软件重启后原 WAITING 任务缺少会话时会失败并提示点击重试、重新输入验证码。
- 登录密码现以遮罩显示；登录请求正文、Cookie、验证码不再通过原有完整 HTTP 日志输出。

## 验收限制

首轮未读取真实账号配置或密码文件，未执行真实账号登录和题库完整导出。后续在用户授权的独立调试窗口中，由用户手动输入并亲测登录成功，见末节。完整题库导出仍未验收；原有需要密码文件或浏览器驱动的广泛集成测试不在本次执行范围。

## 后续登录失败修复（2026-11-08）

用户反馈软件仅显示“登录失败”，官网同账号可以登录。重新核对公开登录协议，发现并修复两项明确的不兼容：

1. `Login.kt` 实际通过 `RSA.encrypt(password)` 加密密码，但 `security.kt` 仍使用旧公钥。官网 [config.js](https://www.cctrcloud.net/exam/lib/config.js?20260922) 的全局字段 `exam_public_key` 已变化，[common.js](https://www.cctrcloud.net/exam/js/modules/common.js?1) 的 `encrypt` 使用该公钥。2026-11-08 重新获取并独立复核，去除 PEM 包装后 216 个 Base64 字符与更新后的本地公钥逐字符一致。
2. 官网 [login_common.js](https://www.cctrcloud.net/exam/js/modules/login_common.js) 兼容 `datas.error` 为字符串或对象 `{code,msg}`，且 `code=200` 携带错误时仍拒绝成功。原程序只尝试字符串解析，会丢失服务端具体错误。现已兼容两种格式并持续显示可复制的错误文字。

实际加密所用 DER 公钥的 SHA-256：

- 当前官网及本地：`dc47a15d3a0a2ec7af0f0259e4711d7953285eb187fbb5df4da4e11a3be1ee62`。
- 原基线公钥：`9507126a04342727f1336eea34bfb3e93c652a54f81c75ea2a6f00d91f56efd2`。

沿用官网静态配置中的公钥，没有增加启动时拉取密钥的网络依赖。后续官网更换密钥时仍需核对并更新。

匿名空登录表单仅探测校验结构，返回 HTTP 200、`text/html; charset=UTF-8`、`code=400`、字符串错误“验证码输入错误，请重新输入”。验证码 Cookie 为 `4A17_seccode`（域 `api.cctrcloud.net`）及 `PHPSESSID`（域 `cctrcloud.net`），路径均为 `/`；匿名空学校查询后两者仍保留。未提交真实账号或尝试解答验证码。

本轮验证：

- 两项新增错误结构测试先实际失败（6 项中 2 项失败），修复后通过。
- 新增公钥指纹回归测试，检查 `RSA` 初始化 Cipher 实际使用的 `publicKey`，避免再次漏测旧公钥问题；首轮测试仅检查密码不是明文，未覆盖该契约。
- 正式 Gradle 命令选择 `CaptchaLoginTest` 与 `PasswordEncryptionTest`，同时执行 `compileKotlin`、`copyCaptchaRuntime`，`BUILD SUCCESSFUL`。XML 为 6+1 项、失败 0、错误 0、跳过 0。
- 独立审查重新读取官网并核对公钥和错误语义，无新增阻断问题；没有新增密码、Cookie、验证码日志。

同路径应用已可逆增量更新，当前入口仍为 `build/verification-captcha/app/CCTRCloudExportDesktop/CCTRCloudExportDesktop.exe`。新增两个 jar 文件，原子更新 `app/CCTRCloudExportDesktop.cfg` 的 classpath；没有覆盖用户进程打开的旧 jar，也没有结束用户进程。

- 当前 UI jar：`app/CCTRCloudExportDesktop-1.1-SNAPSHOT-loginfix-20261108.jar`，SHA-256 `2A7E5A25C741A9F31F352C5E1B1190E8F0F86D56AB41DDD7267AEB0857F4EA29`。
- 当前 API jar：`app/cctr-api-1.0-SNAPSHOT-loginfix-20261108-r2.jar`，SHA-256 `9B25F5CF9E10BFE283BCC10E250655624263FACCD69C5C4E7EBBD93F957DF225`。第一轮修复 jar（不含诊断开关）也保留，哈希为 `3B5868E29354CDA45B2F39E1B847028527F10544CD0F6EFB944AF9D3C56FFC8B`。
- 两个分发 jar 与本次 Gradle 编译、复制产物的哈希分别一致；cfg 已核实指向新文件。
- 回滚：关闭软件后，用同目录 `CCTRCloudExportDesktop.pre-loginfix-20261108.cfg` 恢复为 `CCTRCloudExportDesktop.cfg`，旧 jar 均保留。

用户需关闭旧版本窗口，再重新打开同路径正常 exe，才会载入本轮修复；独立调试窗口已经载入最新代码。

## 用户授权调试与亲测结果

2026-11-08，用户明确要求打开调试窗口，手动输入账号密码并由代理捕捉异常。已用真实 `AddPage`、`AddViewModel`、API 代码打开 `Captcha Login Validation` 窗口，配置和临时目录均隔离在项目 `build/login-debug-home`、`build/login-debug-tmp`。代理没有读取输入控件、密码文件或用户输入期间的截图。

诊断仅在 JVM 属性 `cctr.login.debug=true` 时开启；只记录阶段、HTTP 状态、服务代码、脱敏错误和不含异常消息的堆栈帧。日志位于 `build/login-debug/logs/stdout.log`、`stderr.log`。登录路径不再使用可能记录原始响应的通用 DTO 失败日志。新增脱敏回归测试后，正式测试共 **8 项通过、0 失败、0 错误**，全项目编译通过。

用户反馈“好使了”。仅读取脱敏阶段日志，确认同一次尝试：

- 17:18:12：验证码图片 HTTP 200。
- 17:18:33：学校查询 HTTP 200；登录 HTTP 200；服务代码 200；`login.success`；视图模型报告登录成功。

这次已由用户真实账号亲测登录成功；没有自动创建导出任务，完整题库导出仍未验证。调试窗口和用户原有窗口均按要求保留，没有终止用户交互进程。

正常可运行入口保持原路径，cfg 已原子更新至最新 UI 与 r2 API jar，并逐项核对与最新构建产物哈希一致；**正常入口没有启用调试属性**。第二次 cfg 备份为同目录 `CCTRCloudExportDesktop.pre-debug-20261108.cfg`；原始回滚备份继续保留。最终源码检查 `git diff --check` 通过，未提交或推送验证码改动。
