# 本机助手 · JVM 版（Java + Swing + C/JNI）

Electron/Node 版（`../time`）的 Java 重写版。本地 Ollama 模型驱动的桌面助手，Swing 原生界面，
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
│   │   ├── Safety.java        # 命令白名单+二级解析/路径保护/敏感读取（完整对齐 JS 版）
│   │   ├── CheckResult.java
│   │   └── DocSafety.java     # 文档危险内容检测
│   ├── ollama/OllamaClient.java # 回环校验/禁重定向/unsafe 端口/SSE 流式/异步 generate
│   ├── agent/                 # Agent 循环、审批（5min 超时）、会话持久化、空闲记忆调度
│   ├── tools/Tools.java       # 30+ 工具（文件/命令/进程/截图/Office/记忆/提醒…）
│   ├── memory/ knowledge/ scheduler/ tts/ office/
│   ├── nativelib/NativeBridge.java  # JNI 桥（无 DLL 时优雅降级）
│   ├── util/Json.java         # Jackson 封装（JSON 容错读写）
│   └── ui/                    # Swing 主窗、设置、审计对话框、系统托盘提醒
├── native-c/localagent_native.c # Win32：前台窗口标题、清空回收站（约 60 行）
├── src/test/java/             # 回归三件套 + UiVerify/UiShot（手工 UI 截图校验，不入回归）
├── build.ps1                  # 一键构建+回归+打包（零网络）
├── build-native.ps1/.cmd      # 可选：MSVC 编译 JNI DLL（需 Windows SDK）
└── dist/本机助手/              # 构建产物（双击 本机助手.bat 启动）
```

## 构建与运行

```powershell
# 一键构建（编译 + 38 项回归 + 组装 dist）
powershell -ExecutionPolicy Bypass -File .\build.ps1

# 仅编译打包（跳过回归）
powershell -ExecutionPolicy Bypass -File .\build.ps1 -SkipTests

# 运行：双击 dist\本机助手\本机助手.bat（先启动本机 Ollama）
```

回归基线：**安全 POC 24/24、Office 往返 7/7、数据层 7/7（共 38 项）**；
生产代码另以 `-Xlint:all` 编译保持 **0 error / 0 warning**。

## 安全对齐（与 Electron 版一致）

- 命令执行：`shell:false` 参数数组 + 黑名单（certutil/bitsadmin/mshta/wsl…）+ cmd/powershell 二级解析
  + 完整命令行审批展示 + 5 分钟审批超时
- 路径：HARD_DENY/PROTECTED 目录 + junction realpath 识别；敏感目录/凭据文件读取升级确认
- 网络：Ollama 仅允许字面回环、拒绝 URL 凭据、unsafe 端口黑名单、HttpClient Redirect.NEVER
- 配置：白名单 key，拒绝 mass-assignment
- 依赖：全部为 JDK 内置或从本机复制的固定版本 jar

## 与 Electron 版的差异（当前版本）

1. **原生 DLL 未编译**：本机 VS2022 未安装 Windows SDK（缺 UCRT 头文件/库）。
   影响仅两个功能：`get_foreground_window`（前台窗口标题）返回空、`empty_recycle_bin` 不可用；
   其余功能（含截屏）均为纯 Java，不受影响。安装 VS 的「Windows 10/11 SDK」组件后运行
   `build-native.ps1` 即可补构建（脚本会自动校验 SDK 是否存在）。
2. Office PDF 读取未实现（docx/xlsx/pptx 生成与读取均已实现并通过往返验证）。
3. UI 为纯 JDK Swing 自绘的现代浅色界面（无第三方 UI 库）：
   品牌侧栏、自绘会话列表、消息气泡、工具状态条、扁平圆角按钮、圆角输入卡片、
   空状态欢迎页、细滚动条；消息以纯文本气泡展示（无 Markdown 富文本）。
   设计规范集中在 `ui/UiTheme.java`（色板/字体/间距/圆角），可在此统一换肤。

## 数据位置与旧库迁移

数据库与配置：`%USERPROFILE%\AppData\Roaming\本机助手\data\agent.db`（与 Electron 版独立，互不影响）。

首次启动自动识别 Electron 旧版库结构：`conversations` 缺 `data` 列时以 `ALTER TABLE` 平滑补列，
再把旧 `messages` 表中的历史消息无损回填进会话 JSON，旧会话打开即可见，无需手工处理。

## 近期更新

- **旧版数据库自动迁移**：旧表自动补列并回填历史消息，覆盖安装不丢会话；发送/流式异常改为红色上屏，
  不再静默无响应。
- **记忆提取不再卡住界面**：回合结束不立即调用模型，改为空闲 90 秒后异步提取（新消息会重置计时）；
  提取请求走 `/api/chat` 端点分离 r1 思考与正文，"停止"按钮答完即时恢复（收尾毫秒级）。
- **响应速度优化**：旧配置 `keepAlive=0` 一次性升级为 30 分钟模型保活（仅升级一次，尊重后续手动修改，
  设置面板可调）；流式输出按 40ms 节流合并刷新；修复消息气泡被撑出大块空白的布局问题。
- **工程化**：补齐 `.project/.classpath/.settings`（JavaSE-21、UTF-8），Trae/Eclipse 可直接导入；
  全量源码通过 `-Xlint:all`，0 error / 0 warning（序列化版本号、transient、this-escape 等均已根因修复）。
