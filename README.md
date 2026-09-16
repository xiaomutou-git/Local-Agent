# 本机助手 · JVM 版（Java + Swing + C/JNI）

本地 Ollama 模型驱动的桌面助手，Swing 原生界面，
核心业务全部在内存安全的 Java 中；C 仅保留一个极小的 JNI DLL 承载 Java 无法完成的 Win32 能力。

## 技术栈与零网络构建

| 组件 | 选型 | 来源 |
| --- | --- | --- |
| JDK | JBR 21（Android Studio 自带，无需安装） | `D:\Android Studio\jbr` |
| UI | Swing/AWT（JDK 内置，零下载） | JDK |
| HTTP/Ollama | java.net.http.HttpClient + SSE（JDK 内置） | JDK |
| SQLite | sqlite-jdbc 3.43 | 从本机 Android Studio 复制到 `lib/` |
| JSON | Jackson 2.13 | 从本机 JMeter 复制到 `lib/` |
| Office | 手写 OOXML（java.util.zip + XML，无 POI） | JDK |
| 截屏 | AWT Robot | JDK |
| 原生 DLL | C + MSVC（VS2022，可选） | `native-c/` |
| exe 启动器/卸载器 | C#（系统自带 .NET Framework 4 的 csc 编译，零下载） | `native-cs/` |

**构建全程不需要网络/Maven**（当前环境 Maven 源不可达）。构建脚本直接调用本机 JBR javac。

## 目录结构

```
time-jvm/
├── .project .classpath .settings/  # Eclipse/JDT 工程文件（Trae 可直接打开，JDK 21 + UTF-8）
├── lib/                       # 本地依赖 jar（sqlite-jdbc/jackson/slf4j）
├── src/main/java/com/localagent/
│   ├── Main.java              # 入口：装配全部组件 + 启动 Swing + 历史配置一次性升级
│   ├── config/Config.java     # 配置（白名单 key、keepAlive 保活等）
│   ├── db/                    # SQLite + 审计日志（90 天保留/4000 字截断/旧库自动迁移）
│   ├── safety/
│   │   ├── Safety.java        # 命令白名单+二级解析/路径保护/敏感读取
│   │   ├── CheckResult.java
│   │   └── DocSafety.java     # 文档危险内容检测
│   ├── ollama/                # OllamaClient（回环/流式）+ OllamaEnv/OllamaSetup（首次引导）
│   │                          #   OllamaEnv：安装/服务/模型检测、按内存选档、签名校验、pull 进度
│   ├── agent/                 # Agent 循环、审批（5min 超时）、会话持久化、空闲记忆调度
│   ├── tools/Tools.java       # 36 个内置工具（文件/命令/进程/截图/Office/记忆/提醒…）
│   ├── mcp/                   # MCP 外部工具（仅本地 stdio）：客户端/管理器/工具目录/环境探测与模板
│   ├── memory/ knowledge/ scheduler/ tts/ office/
│   ├── nativelib/NativeBridge.java  # JNI 桥（无 DLL 时优雅降级）
│   ├── util/Json.java         # Jackson 封装（JSON 容错读写）
│   └── ui/                    # 主窗、设置、审计对话框、托盘提醒、OllamaSetupDialog（首次引导）
├── native-c/localagent_native.c # Win32：前台窗口标题、清空回收站（约 60 行，可选）
├── native-cs/                 # C# 源（系统 csc 编译，零依赖）：Launcher.cs 启动器、Uninstaller.cs 卸载器
├── src/test/java/             # 回归 12 件套 + UiVerify/UiShot（手工 UI 截图校验，不入回归）
├── build.ps1                  # 一键构建+回归+自带运行时+编译 exe 启动器/卸载器+打包（零网络）
├── build-native.ps1/.cmd      # 可选：MSVC 编译 JNI DLL（需 Windows SDK）
└── dist/本机助手/              # 构建产物（内置 runtime；双击 本机助手.exe 启动，卸载.exe 卸载）
```

## 构建与运行

```powershell
# 一键构建（编译 + 259 项回归 + 内置运行时 + 编译 exe 启动器/卸载器 + 组装 dist）
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 仅编译打包（跳过回归）
powershell -ExecutionPolicy Bypass -File .\build.ps1 -SkipTests

# 运行：双击 dist\本机助手\本机助手.exe（无黑窗；.bat 为带控制台输出的备用启动器）
# 首次启动若未装 Ollama 会自动引导安装与下载模型，无需手工准备（见下节）
```

回归基线：**安全 POC 39/39、Office 往返 30/30、数据层 7/7、MCP 工具合并 35/35、
MCP stdio 49/49、本地环境探测 25/25、进程管道 6/6、时间工具 10/10、
会话导出 8/8、知识库索引 9/9、单实例锁 3/3、Ollama 引导 38/38（共 259 项）**；
构建产物 `dist\本机助手\runtime` 内置完整运行时（JDK 带 jmods 时优先 jlink
裁剪，否则复制 JBR），目标机器无需安装 JDK；`本机助手.exe`/`卸载.exe` 由系统自带
.NET Framework 4 的 csc 编译，Windows 10/11 出厂自带该运行库、无需另装；
生产代码另以 `-Xlint:all` 编译保持 **0 error / 0 warning**。

## 目标机器要求（零环境兼容性）

已按"全新 Windows、未安装任何开发环境"场景做过依赖闭环验证（PE 导入表实证）：

| 外部依赖 | 是否需要用户预装 | 说明 |
| --- | --- | --- |
| JDK / JRE | **不需要** | 分发目录内置完整 JBR 21（`runtime/`），目标机无 Java 也能运行 |
| VC++ 运行库 | **不需要** | JBR 已在 `runtime/bin` 自带 vcruntime140.dll、vcruntime140_1.dll、msvcp140.dll、ucrtbase.dll |
| .NET 运行时 | **不需要** | exe 启动器/卸载器基于 .NET Framework 4（Windows 10/11 出厂自带 4.6/4.8）；非 .NET Core |
| SQLite 驱动 | **不需要** | sqlite-jdbc 已内嵌 Windows x86_64 native，仅依赖系统 msvcrt.dll（Windows 自带） |
| PowerShell | 系统自带 | Win10/11 内置 5.1（签名校验、卸载自清理使用，已带 `-ExecutionPolicy Bypass`） |
| 解压工具 | 系统自带 | 标准 zip，资源管理器右键"全部解压"即可 |
| Ollama | **首次启动自动安装** | 见下节；需联网下载安装器（约 800MB）与模型（0.4~9GB） |

**系统版本**：Windows 10/11 64 位开箱即用；Windows 11 ARM64 可经 x64 模拟运行；
CPU 推理无需显卡/N 卡驱动。无需管理员权限（Ollama 采用用户级安装到 `%LOCALAPPDATA%`）。

两个已知提示（非故障）：
- **SmartScreen 首次拦截**：exe 未购买商业代码签名证书，首次双击可能出现
  "Windows 已保护你的电脑"，点「更多信息」→「仍要运行」即可；
- **网络要求**：安装器从 ollama.com 下载（会重定向到官方 CDN/GitHub Release），
  模型从 Ollama 官方仓库拉取；网络受限时可点引导窗口的「手动下载/说明」自行安装后重启。

## 首次启动引导（自动安装 Ollama 与适配模型）

首次双击 `本机助手.exe` 时，主界面前先做三态检测（仅首次出现，跳过后不再打扰）：

1. **未安装 Ollama**：一键从固定官方地址 `https://ollama.com/download/OllamaSetup.exe`
   下载安装器（带进度），先做 Windows **Authenticode 数字签名校验**（必须 Valid 且证书
   主体含 Ollama，防止下载链路被劫持运行伪造文件），通过后才启动官方安装器并等待服务就绪；
2. **已安装但服务未运行**：自动拉起 `ollama app.exe` 并轮询 `/api/version` 确认就绪；
3. **服务正常但无模型**：按本机物理内存预选合适档位，确认后 `ollama pull` 拉取（实时进度、可取消）。

模型按内存五档推荐（qwen2.5 中文模型，体积取自 Ollama 官方库标注），用户可改选：

| 内存 | 推荐模型 | 下载量 |
| --- | --- | --- |
| ≥32GB | qwen2.5:14b | 约 9.0GB |
| ≥16GB | qwen2.5:7b | 约 4.7GB |
| ≥8GB | qwen2.5:3b（可选视觉版 qwen2.5vl:3b 分析图片） | 约 1.9GB |
| ≥4GB | qwen2.5:1.5b | 约 986MB |
| 更低 | qwen2.5:0.5b | 约 398MB |

拉取成功后自动写入配置并进入主界面；也提供「手动下载/说明」与「跳过」出口，
任何步骤失败都可重试。跳过状态记录在配置项 `ollamaBootstrapDone`，日后可在设置中自行管理。

## 卸载

双击分发目录中的 `卸载.exe`：二次确认后自动终止运行中的进程、删除程序目录与开机自启
注册表项（`HKCU\...\Run\LocalAgent`）及 `%TEMP%\local-agent`；**个人数据
（`%APPDATA%\本机助手` 的聊天记录/配置/知识索引）默认保留，必须显式勾选才删除**；
截图目录与知识库原文目录永不自动删除。程序目录由退出后的随机名 PowerShell 脚本自清理。

## 安全设计

- 命令执行：参数数组直传（不经 shell 拼接）+ 黑名单（certutil/bitsadmin/mshta/wsl…）
  + cmd/powershell 二级解析
  + 完整命令行审批展示 + 5 分钟审批超时
- 路径：HARD_DENY/PROTECTED 目录 + junction realpath 识别；敏感目录/凭据文件读取升级确认
- 网络：Ollama 仅允许字面回环、拒绝 URL 凭据、unsafe 端口黑名单、HttpClient Redirect.NEVER
- 配置：白名单 key，拒绝 mass-assignment
- 依赖：全部为 JDK 内置或从本机复制的固定版本 jar
- 白盒渗透复测加固（8 项，均有回归覆盖）：
  - **Office zip 炸弹**：解压改流式有界读取，单条目 20MB / 单包 1 万条目 / 累计 100MB
    三重闸门在读流阶段即时生效，OOM 不再先于检查；createPpt 补齐危险内容检测
  - **命令参数借道**：白名单 `explorer`/`control` 新增参数审查，拦截 exe 借道执行、
    URL/UNC 外联、任意 CPL 加载（直接调用与 `cmd /c` 子段双重生效）
  - **cmd 解析绕过**：折叠 `^` 转义（`d^e^l`）、拒绝 `%VAR%`/`!VAR!` 展开、
    拦截引号外重定向符，封堵首词匹配绕过
  - 图片分析 20MB 体积预检（Base64 膨胀前拦截）；系统保护目录按 SystemDrive 动态解析
  - 工具参数预览/会话标题统一 HTML 转义，修复 JLabel 注入；TTS 临时脚本改
    SecureRandom 随机名并用后即删；引导安装器必须通过 Authenticode 签名校验

## 当前版本说明

1. **原生 DLL 未编译**：本机 VS2022 未安装 Windows SDK（缺 UCRT 头文件/库）。
   影响仅两个功能：`get_foreground_window`（前台窗口标题）返回空、`empty_recycle_bin` 不可用；
   其余功能（含截屏）均为纯 Java，不受影响。安装 VS 的「Windows 10/11 SDK」组件后运行
   `build-native.ps1` 即可补构建（脚本会自动校验 SDK 是否存在）。
2. Office PDF 读取未实现（docx/xlsx/pptx 生成与读取均已实现并通过往返验证）。
3. UI 为纯 JDK Swing 自绘的现代浅色界面（无第三方 UI 库）：
   品牌侧栏、自绘会话列表、消息气泡、工具状态条、扁平圆角按钮、圆角输入卡片、
   空状态欢迎页、细滚动条；消息以纯文本气泡展示（无 Markdown 富文本）。
   设计规范集中在 `ui/UiTheme.java`（色板/字体/间距/圆角），可在此统一换肤。

## MCP 外部工具（仅本地 stdio，离线红线）

- 设置面板可视化每个服务的连接状态、工具数与错误详情，可一键「测试连接」
  （用临时进程握手，不写配置、不影响正式连接）。
- 保存配置后后台热重连，新增/修改/停用服务均免重启生效。
- 自动探测本机 npx/uvx/python 运行环境，并一键插入 filesystem/memory/time 三个纯本地服务模板；
  Windows 下 npx 自动包装 `cmd.exe /c`，服务名冲突自动加后缀。
- 配置与运行时均拒绝 http/url 等网络型远程服务，MCP 子进程统一注入死代理环境。

## 数据位置与旧库迁移

数据库与配置：`%USERPROFILE%\AppData\Roaming\本机助手\data\agent.db`。

首次启动自动识别旧版库结构：`conversations` 缺 `data` 列时以 `ALTER TABLE` 平滑补列，
再把旧 `messages` 表中的历史消息无损回填进会话 JSON，旧会话打开即可见，无需手工处理。

## 近期更新

- **免安装交付（exe 启动器 + 卸载器）**：用系统自带 .NET Framework 4 的 csc 编译两个
  GUI 程序（零第三方依赖）——`本机助手.exe` 无黑窗启动（按内置 runtime/JAVA_HOME/PATH
  定位 javaw，启动失败有明确弹窗）、`卸载.exe` 交互式卸载（个人数据默认保留）；
  build.ps1 每次出包自动编译，缺 csc 时降级保留 .bat。
- **首次启动自动配置 Ollama**：三态检测（未安装/服务未运行/无模型）一键闭环；安装器
  下载后强制 Authenticode 签名校验；按物理内存五档推荐 qwen2.5（8GB+ 可选 qwen2.5vl
  视觉版），`ollama pull` 实时进度、可取消；置顶引导窗口避免被其他程序遮挡。
- **白盒渗透修复 8 项漏洞**：Office zip 炸弹流式三重闸门、explorer/control 参数借道、
  cmd `^`/`%VAR%`/重定向绕过、图片体积预检、保护目录动态系统盘、JLabel HTML 注入、
  TTS 可预测临时脚本、createPpt 危险内容检测；回归由 183 扩充至 259 项全绿。
- **MCP 本地工具链**：设置面板新增连接状态面板与「测试连接」（临时握手、不污染正式连接）；
  保存配置后后台热重连，免重启增删/启停服务；自动探测 npx/uvx/python 并一键插入
  filesystem/memory/time 本地服务模板；三环境并行探测约 8 秒封顶。全程仅允许本地 stdio，拒绝 http/url。
- **旧版数据库自动迁移**：旧表自动补列并回填历史消息，覆盖安装不丢会话；发送/流式异常改为红色上屏，
  不再静默无响应。
- **记忆提取不再卡住界面**：回合结束不立即调用模型，改为空闲 90 秒后异步提取（新消息会重置计时）；
  提取请求走 `/api/chat` 端点分离 r1 思考与正文，"停止"按钮答完即时恢复（收尾毫秒级）。
- **响应速度优化**：旧配置 `keepAlive=0` 一次性升级为 30 分钟模型保活（仅升级一次，尊重后续手动修改，
  设置面板可调）；流式输出按 40ms 节流合并刷新；修复消息气泡被撑出大块空白的布局问题。
- **工程化**：补齐 `.project/.classpath/.settings`（JavaSE-21、UTF-8），Trae/Eclipse 可直接导入；
  全量源码通过 `-Xlint:all`，0 error / 0 warning（序列化版本号、transient、this-escape 等均已根因修复）。

## 开源许可证（Apache-2.0 + Commons Clause v1.0）

本项目以 **Apache License 2.0** 为基础许可，并附加 **Commons Clause v1.0** 作为额外限制条件；
完整法律文本见仓库根目录 [LICENSE](LICENSE)。

**你可以：**

- 自由使用、复制、修改、合并、发表、分发本软件的源代码与构建产物（含你的修改版）；
- 个人使用、企业内部使用、自行部署均不受限制；
- 按 Apache-2.0 要求保留版权与许可声明，并注明你对文件做出的修改。

**你不可以：**

- **销售本软件**——Commons Clause v1.0 额外规定：不得将本软件、或价值实质上源自本软件功能的
  产品/服务提供给第三方以获取费用或其他对价，包括但不限于基于本软件的付费托管（SaaS）、
  付费咨询与技术支持。

**补充说明：**

- 除"禁止销售"这一项附加限制外，Apache License 2.0 的全部权利与义务（含专利授权、
  修改与分发自由）均完整有效；本许可不影响你对自己所写代码的著作权；
- 本软件按"现状"提供，无任何明示或默示担保，使用风险由使用者自行承担（见 Apache-2.0 第 7、8 条）；
- `lib/` 中捆绑的第三方组件（Jackson、sqlite-jdbc、slf4j 等）仍遵循各自原始许可证
  （Apache-2.0 / MIT），Commons Clause 仅约束本项目自身代码，不改变第三方依赖的授权；
- 协议标识：`Apache-2.0 WITH Commons-Clause-1.0`；著作权人（Licensor）：小木头。
