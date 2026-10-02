# AGENTS.md — AI Agent 操作指引

本文件供 AI agent（Claude Code、Codex 等）在本仓库工作前快速建立上下文。人类开发者同样适用。

## 项目是什么

ChiliCraft：ChiliChill 2026 巡演「混入人类计划 II：方街」主题的 Paper 1.21.1 插件组。核心 `cc-core` 提供档案 / 全局模式 / 参数 / 事件总线 / 灵魂货币，玩法全部是附属模块（cc-survival / cc-demon / cc-soul / cc-martial；cc-street 规划中）。模块间**零编译期依赖**，只靠字符串事件名通信。

## 必读顺序

1. [README.md](README.md) —— 项目与模块总览、巡演联动点
2. [docs/architecture.md](docs/architecture.md) —— 分层与装配模式
3. [docs/events-protocol.md](docs/events-protocol.md) —— 事件契约与全量清单
4. [docs/performance.md](docs/performance.md) —— 性能红线（验收口径）
5. 改附属模块前：对照 [docs/module-dev-guide.md](docs/module-dev-guide.md) 的范式与自检清单

## 构建与验收

```powershell
# 常规构建（需可访问 repo.papermc.io；国内网络需代理）
.\gradlew.bat build

# 依赖已缓存时的兜底（SNAPSHOT 元数据刷新失败 / TLS 握手失败时用这个）
.\gradlew.bat build --offline
```

- 环境：Windows + PowerShell；JDK 21 工具链（缺失自动下载），编译目标 Java 21 字节码
- **验收标准：`gradlew build`（或 --offline）BUILD SUCCESSFUL**。不要尝试启动 Minecraft 服务端验证
- 构建失败先分清两类：网络类（papermc 元数据 / TLS handshake → 换 `--offline`）与代码类（正常修）

## 环境坑（不要踩）

- **GBK**：中文 Windows 默认 GBK。JDK 17 编译 worker 会按平台编码读 daemon 写出的 argfile，GRADLE_USER_HOME 路径含中文即崩——这是项目用 JDK 21 工具链的原因，见根 [build.gradle.kts](build.gradle.kts) 注释，**不要改回 17**
- 源码、配置、文档一律 UTF-8
- 不要在 PowerShell 用 `&&`（不支持），用 `;` 或分开执行

## 改动边界

1. **cc-core 默认零改动**。新玩法 = 新附属模块；跨模块协作 = 事件总线
2. **新事件先登记后编码**：先在 [docs/events-protocol.md](docs/events-protocol.md) 加行，再写代码。订阅尚未实现的事件是合法且常态
3. 改现有模块时保持既有范式（见下），不做顺手重构
4. 巡演主题联动改动优先复用「配置名单 + 事件订阅」模式，模板缺失时静默跳过

## 代码范式（对照现有模块抄）

- 附属 onEnable：ServicesManager 取 API（取不到自禁用）→ `registerModuleConfig` → `settings.refresh()` → 启动周期任务 → `api.subscribe("core.reload", ...)` 热载
- onDisable：任务 `cancel` → 每个 `subscribe` 对应 `unsubscribe` → 状态置 null
- Settings：字段包内可见 + `refresh()` 整体重建 + 非法值回退默认告警
- 周期任务：单句柄、逐玩家 try-catch、1–2 秒周期、禁止 PlayerMoveEvent 等高频事件
- 消息：MiniMessage 模板存 config `messages` 段，缺失空串静默，解析失败回退纯文本
- 注释与配置全中文；javadoc 说明「为什么」

## 常见任务路径

| 任务 | 路径 |
|---|---|
| 新增附属模块 | module-dev-guide.md Step 0–5 + 自检清单 |
| 新增联动点 | events-protocol.md 登记事件 → 订阅方实现 → config.yml 加名单/模板 |
| 调数值 / 文案 | 各模块 `src/main/resources/config.yml`，`/cc reload` 热载 |
| 查事件语义 | events-protocol.md 全量清单（含字段约定与订阅方行为） |
| 改核心 API | 谨慎：改 `com.chilicraft.api` 契约须同步核对全部附属用法 + 本文档范式 |

## 文档同步义务（硬性规矩）

**新建子插件或改动插件，必须同步更新相关资料以供查阅；文档未同步 = 任务未完成，不得收尾汇报。** 按改动类型对照下表执行：

| 改动类型 | 必须同步的资料 |
|---|---|
| 新建子插件 | settings.gradle.kts include、[README.md](README.md) 模块总览表、[architecture.md](docs/architecture.md) 工程结构、[events-protocol.md](docs/events-protocol.md)（有新事件先登记）、沉淀出新范式时更新 [module-dev-guide.md](docs/module-dev-guide.md) |
| 新增 / 变更事件 | [events-protocol.md](docs/events-protocol.md) 清单（先登记后编码，含字段约定与订阅方行为） |
| 改动巡演联动点 / 名单机制 | [README.md](README.md) 巡演联动一览、模块 config.yml 注释与默认值 |
| 改动周期任务 / 性能相关行为 | [performance.md](docs/performance.md) 周期表与红线口径 |
| 调数值 / 文案 | 模块 config.yml 内注释保持与行为一致（无结构变化不必动 docs） |
| 改动装配范式 / API 契约 | [architecture.md](docs/architecture.md)、[module-dev-guide.md](docs/module-dev-guide.md) 对应范式段落 |

更新原则：只改受影响的小节，不做顺手重写；文档描述必须与代码实际行为一致，宁可写「规划中 / 未实现」也不留过期描述。

## 提交纪律

- 提交前必过构建；改动多个模块时逐模块说明
- **提交前按「文档同步义务」核对资料已更新**，README / docs / AGENTS 与代码不一致不得提交
- 不引入新依赖；附属依赖一律 `compileOnly`
- 不提交 `build/`、`.gradle/` 产物
