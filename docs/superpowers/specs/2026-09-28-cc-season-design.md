# cc-season 移植与 1.21.1 升级设计文档

- 日期：2026-09-28
- 状态：已获用户确认，待实施计划
- 范围：阶段一 Paper 1.20.4 → 1.21.1 整体升级；阶段二 SeasonCore 移植为新附属模块 cc-season

## 1. 背景与结论

SeasonCore（https://github.com/KinkinxD12/SeasonCore）是 Paper 1.21.1 的季节插件（日历 / 天气 / HUD / 农耕生态 / 世界视觉）。评估结论：

- **不能直接共存**：`api-version: '1.21'` + compileOnly paper-api 1.21.1，在 1.20.4 服务端拒绝加载（Unsupported API version）
- **不能直接搬运代码**：其世界视觉是真实改写 biome + 回滚（BiomeBackupStore），与 ChiliCraft 的竞技场隔离原则与 performance.md 红线冲突
- 项目年轻（2026-01 创建、单人作者），代码西英混杂

**定稿决策**：

1. ChiliCraft 整体升级到 Paper 1.21.1（独立验收）
2. 以 SeasonCore 源码为**行为蓝本**、按 cc 五件套范式重写为 `cc-season` 附属模块（非代码搬运）
3. 四季视觉不照搬，改用**发包 biome 重映射**（ProtocolLib 软依赖）
4. 实施顺序：先升级后移植，各自独立 `gradlew build` 验收

## 2. 阶段一：Paper 1.20.4 → 1.21.1 升级

### 2.1 改动清单

| 位置 | 改动 |
|---|---|
| 根 `build.gradle.kts` | `options.release` 17 → 21（Paper 1.20.5+ API 为 Java 21 字节码）；JDK 21 工具链**不动**；更新 GBK 坑注释中「字节码兼容由 options.release = 17 保证」的口径 |
| 六个模块 `build.gradle.kts` | `io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT` → `1.21.1-R0.1-SNAPSHOT`（cc-core / cc-survival / cc-demon / cc-soul / cc-martial / cc-adventure） |
| 六个模块 `plugin.yml` | `api-version: "1.20"` → `"1.21"` |

### 2.2 API 适配审计面

重灾区：**Attribute / PotionEffectType 枚举 → 接口 + 注册表化**。1.21.1 中 `GENERIC_*` 等常量名仍有效（改名集中在 1.21.3），因此简单常量引用可照常编译，**排查重点是枚举特有用法**：

- `switch` 语句 / `values()` / `valueOf()` / `name()` / `ordinal()` / `EnumMap` / `EnumSet`
- 静态获取方式（如 `PotionEffectType.getByName`）→ 注册表方式

涉及文件（扫描结果）：

- Attribute / AttributeInstance：cc-soul（SoulListener、RelicService）、cc-adventure（ExpeditionService、BossService）、cc-martial（7 个类）、cc-demon（DemonManager）
- PotionEffectType：cc-survival（PressureTask）、cc-soul、cc-adventure、cc-martial

低风险面：Material（遍布各模块）、Sound（仅 cc-martial）常量引用不受影响。全项目无 Registry / 附魔 / Biome API 使用。`-Xlint:deprecation` 已开启，编译输出作为补漏依据。

### 2.3 构建与验收

- **首轮必须联网构建**：本地 Gradle 缓存无 1.21.1 API，`--offline` 必然失败；首轮成功后 `--offline` 恢复可用
- 验收：`gradlew build` BUILD SUCCESSFUL，不启动服务端
- cc-core 说明：本阶段改 cc-core 的构建依赖与 plugin.yml 属「跟随升级」，不触碰 `com.chilicraft.api` 契约与行为，零改动原则仍然成立

### 2.4 文档同步

- AGENTS.md：「编译目标 Java 17 字节码」→ 21；项目描述中的 1.20.4 口径
- architecture.md：`options.release = 17` 相关口径
- README.md：Paper 版本口径（如有）

### 2.5 明确不含

- test-server 运行时升级（Paper 服务端 jar、第三方插件版本更换属运维事项，另算）

## 3. 阶段二：cc-season 模块

### 3.1 骨架与坐标

- 模块：`cc-season`，包 `com.chilicraft.season`，主类 `ChiliSeasonPlugin`
- settings.gradle.kts 追加 `include("cc-season")`；构建脚本对照 cc-demon（`compileOnly(project(":cc-core"))` + paper-api + slf4j + processResources 注入 version）
- 五件套：主类 / Settings（字段包内可见 + `refresh()` 整体重建 + 非法值回退默认告警）/ Service / Listener / Task
- onEnable 标准流程：ServicesManager 取 API（取不到自禁用）→ `registerModuleConfig` → `settings.refresh()` → 周期任务 → `subscribe("core.reload", ...)` 热载
- onDisable：任务 cancel → 每个 subscribe 对应 unsubscribe → 状态置 null

### 3.2 日历服务（SeasonService 为蓝本）

- 状态 `CalendarState(year, day, season)`；`daysPerSeason` 默认 28（下限 4）
- 三种推进模式（配置互斥）：
  - `real_time_minutes_per_day > 0`：真实时间推进（周期任务内累计检查）
  - `follow_overworld_time`：锚世界 `fullTime / 24000` 追踪（随主周期任务执行）；`maxCatchupDays` 默认 2，单日跳变超出则重置基线并告警（防跳变）
  - `advance_on_sleep`：睡觉推进
- `requirePlayersOnServer`：无人在线冻结（蓝本行为，配置开关）
- `nextDay()` 为翻日**单一出口**：`day++` → 翻季 / 翻年 → `persistNow()` 写 `data/calendar.yml` → 发事件（见 3.3 顺序）
- 砍掉蓝本的 Frost 世界（aeternum_frost）特判逻辑
- 配置读取沿用蓝本的双路径回退风格，但键名按 cc 全中文注释规范重写

### 3.3 事件契约（先登记 events-protocol.md，后编码）

三个事件均主线程 publish；订阅方约定：自行缓存季节状态，不得反向持有 cc-season 内部对象。

`nextDay()` 内发布顺序固定：

1. `season.changed`（仅翻季时）：playerId=null、target=`spring|summer|autumn|winter`、amount=年份、扩展键 `day`=季内天数、`trigger`=`natural|command`
2. `season.day_changed`（每日）：playerId=null、target=当天所属季节、amount=年份、扩展键 `day`=季内天数
3. `season.special_day`（命中特别日子名单时）：playerId=null、target=联动标识（名单值）、amount=年份、扩展键 `season`=季节、`day`=季内天数

catchup 跨多日时逐日循环 `nextDay()`，事件逐日发——特别日子不会因跳变被静默跳过。

**巡演联动点**：config.yml「特别日子」名单（`第X年第Y天: 联动标识`），名单留空则完全静默，对齐「配置名单 + 事件订阅」范式。

### 3.4 功能子集

| 功能 | 蓝本 | 要点 |
|---|---|---|
| 季节天气 | SeasonalWeatherService | 按季节权重选择晴雨/雷暴，配置周期与概率 |
| HUD | HudService | BossBar 显示年/季/日，按季配色；全局开关 + `/ccseason hud` 个人切换（内存态 Set\<UUID\>，v1 不持久化） |
| 季节作物 | SeasonalCropGrowthListener / CropLoreService / crops.yml | crops.yml 内容文件（对齐 dungeons.yml 先例）：作物 × 季节生长速率，禁长季速率 0；种子/作物 lore 显示适种季节（配置开关） |
| 温室 | GreenhouseService | 玻璃顶棚判定（上方 N 格内玻璃覆盖）→ 忽略季节限制，N 与判定规则入配置 |
| 动物迁徙 | AnimalMigrationService | 按季节对名单内动物执行迁移/替换；**性能敏感**：低频周期任务、单句柄、逐实体 try-catch、扫描范围与周期入配置并写入 performance.md 周期表 |
| 村民类型 | VillagerTypeOverrides | 按季节改写村民 biome 类型外观（事件驱动，监听生成） |
| 季节时钟 | SeasonClockService | 时钟物品显示季节日期（lore/名称随翻日更新） |
| 指南书 | SeasonGuide | `/ccseason guide` 领取成书，内容存 config 模板，缺失静默 |
| PAPI 占位符 | AeternumPlaceholders | `%ccseason_season%` 等；PAPI 缺席时适配器降级不注册 |

### 3.5 四季视觉：发包 biome 重映射（不照搬 SeasonCore）

- **原理**：客户端按区块数据包中的 biome 渲染草地/树叶颜色。用 ProtocolLib 拦截 chunk 数据包，把 biome palette 重映射到「季节代理 biome」（如冬季 → 积雪变种），**真实世界区块不变**，无需备份与回滚
- **依赖形态**：ProtocolLib 软依赖。`softdepend: [ProtocolLib]`；`compileOnly(files("../test-server/plugins/ProtocolLib.jar"))`（对齐 MythicMobs 本地 jar 范式）；适配器 `catch(Throwable)` 降级——无 ProtocolLib 时视觉关闭、其余功能正常
- **隔离性**：逐玩家 × 逐世界生效；`worlds.disabled_season_fx` 排除名单（竞技场世界入名单，多竞技场隔离不受影响）
- **映射表**：内置默认映射（每季一组 biome → 代理 biome），config 可覆盖；1.21.1 biome 注册表索引运行时解析（`Registry<Biome>`），索引按服务端会话有效、重启重建缓存
- **换季刷新**：`resend_on_season_change`（默认 true）对在线玩家重发视野内区块——每季一次脉冲（默认 28 天），可接受
- **性能口径**：映射表按 世界×季节 缓存，仅换季重建；单包处理 = palette 查表替换，不触发额外区块加载、不改写方块段。写入 performance.md
- **v1 范围**：变色 + 天气联动；**假雪层（multi-block-change）二期**
- **WorldGuard 不集成**：发包方案与区域保护无关（YAGNI）
- **被替代而砍掉的蓝本件**：BiomeSpoofAdapter（真实改写）、WinterWorldPainter、AutumnSoilPainter、CanopySnowPainter、FastLeafDecayService、Frost 世界

### 3.6 命令

plugin.yml 声明 + 独立执行器与 Tab 补全，参数解析 / 权限检查 / 游戏逻辑三层分离，CommandSender → Player 先校验：

| 命令 | 权限 | 说明 |
|---|---|---|
| `/ccseason`（info） | 无（默认全员） | 查看当前年/季/日 |
| `/ccseason hud` | 无 | 切换个人 HUD 显示 |
| `/ccseason guide` | 无 | 领取指南书 |
| `/ccseason clock` | 无 | 领取季节时钟 |
| `/ccseason set <season\|day> <值>` | `ccseason.admin` | 管理调整；引发的换季 `trigger=command` |

配置热载走全局 `/cc reload`（core.reload），不另设 reload 子命令。

### 3.7 配置

- `config.yml` 全中文注释：日历段（daysPerSeason / 推进模式 / maxCatchupDays / requirePlayersOnServer）、天气段、HUD 段、视觉段（开关 / 映射表覆盖 / resend_on_season_change / worlds.disabled_season_fx）、特别日子名单、messages 段（MiniMessage，缺失空串静默、解析失败回退纯文本）
- `crops.yml` 内容文件：作物 × 季节速率表 + 温室参数
- 全部数值、概率、周期、文本走配置，非法值回退默认并告警

### 3.8 周期任务与线程

- 单权威周期任务句柄（1–2s）驱动：日历 tick（follow 模式做锚世界追踪；real_time 模式在同一任务内做累计时间检查；睡觉模式事件驱动无需 tick）/ HUD 刷新 / 天气判定；逐玩家 try-catch
- 动物迁徙独立低频任务（周期入配置，分钟级），主线程，逐实体 try-catch
- 发包拦截在 Netty 线程触发：**仅做 palette 查表替换（只读缓存映射），禁止触碰世界状态**；换季重建映射表在主线程完成后发布给适配器
- 全项目禁用 PlayerMoveEvent 等高频事件

### 3.9 物料与依赖前置

- **ProtocolLib jar 当前缺失**：test-server/plugins 无 ProtocolLib，实施前需放入（用户准备物料）；PAPI jar（PlaceholderAPI-2.12.3.jar）已有
- 不引入其他新依赖；第三方一律 compileOnly
- SeasonCore config.yml 完整内容实施期需补抓（前期调研只获取到尾部三节）

### 3.10 文档同步清单（阶段二收尾硬性要求）

- settings.gradle.kts include、README.md 模块总览表、architecture.md 工程结构
- events-protocol.md 登记 3 个事件（含字段约定与订阅方行为）
- performance.md：周期任务表（季节主任务、动物迁徙）+ 发包拦截性能口径
- module-dev-guide.md：发包视觉适配器若沉淀为可复用范式则补段

## 4. 验收标准

- 阶段一：联网 `gradlew build` SUCCESSFUL；`-Xlint:deprecation` 无新增阻断性错误
- 阶段二：`gradlew build`（或 `--offline`）SUCCESSFUL；plugin.yml 声明与实际注册一致；文档同步清单全项完成
- 不启动 Minecraft 服务端验证

## 5. 版本假设与依赖要求

- 目标服务端：Paper 1.21.1（Java 21 运行时）；编译：JDK 21 工具链、`options.release = 21`
- ProtocolLib 需支持 1.21.1 的版本（5.x）；PlaceholderAPI 2.12.3
- 附属模块对 cc-core 仅 `compileOnly(project(":cc-core"))`，跨模块零编译期依赖

## 6. 风险与开放问题

1. Attribute / PotionEffectType 接口化的实际适配点以编译错误与 deprecation 警告为准（审计面已圈定，见 2.2）
2. ProtocolLib 发包字段在 1.21.1 的具体结构（palette 位置）实施期验证；不适配时降级策略 = 视觉关闭
3. 动物迁徙的实体扫描成本需按 performance.md 红线定周期与范围，实施期给出默认值
4. SeasonCore 完整 config.yml 与未细读的类（AnimalMigrationService 等）在实施期按需补读，行为以本文档子集范围为准
5. 假雪层、HUD 个人偏好持久化为二期候选，不在本次范围
