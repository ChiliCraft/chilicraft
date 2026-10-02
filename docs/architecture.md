# 架构总览

本文面向开发者和 AI agent，说明 ChiliCraft 的分层结构、装配模式与数据流。实现规格全文见根目录《ChiliCraft·插件版技术文档（实现规格 v1.0） (1).md》（v1.1）。

## 工程结构

Gradle 多工程（[settings.gradle.kts](settings.gradle.kts)），根 [build.gradle.kts](build.gradle.kts) 统一约定：

- Java 21 工具链编译，`options.release = 21`（目标字节码 21，目标服务端为 Paper 1.21.1）
- 全部源码 `UTF-8`；`-Xlint:deprecation` 暴露过时 API 用法
- 仓库：mavenCentral + papermc（国内网络需代理，见 README「构建」）
- 版本统一 `com.chilicraft:1.0.0`

```
chilicraft/
├── cc-core/        # 核心（契约 + 实现 + DB + 命令 + GUI）
├── cc-survival/    # 附属：生存压力
├── cc-demon/       # 附属：饿魔（含 /demon 箱子 GUI）
├── cc-soul/        # 附属：灵魂与遗物（含 /soul 箱子 GUI）
├── cc-martial/     # 附属：武学竞技场（含 /martial 箱子 GUI）
├── cc-adventure   # 附属：地城、远征与世界 Boss（含 /adventure 箱子 GUI）
├── cc-season      # 附属：季节日历、天气与生态
├── cc-quest       # 附属：任务章节、事件推进与旅途手册
└── cc-street      # 规划中：方街（见规格 v1.1）
```

## 三层结构

### 1. 契约层 `com.chilicraft.api`（cc-core 内）

附属**只**允许依赖这一层的接口，禁止触碰 `com.chilicraft.core.*` 内部实现：

| 类型 | 职责 |
|---|---|
| `ChiliCraftAPI` | 核心服务总入口：档案、模式、灵魂、任务奖励事务、事件总线、参数、ModuleConfig、RowStore，以及菜单/命令注册 |
| `ModuleMenuEntry` / `MenuCategory` | 分类菜单入口描述与六个菜单分类 |
| `ModuleCommandRoute` / `ModuleCommandExecutor` / `ModuleTabCompleter` | `/cc <module>` 路由、执行和 Tab 补全契约 |
| `EventData` / `EventHandler` | 事件载体与回调契约 |
| `ParamKey` | 跨模块参数键（HUNGER_DECAY、WEIGHT_LIMIT 等 5 键） |
| `GameMode` / `PlayerProfile` | 全局模式枚举与档案只读视图 |
| `ModuleConfig` / `HomeZone` / `RowStore` | 附属配置、家园区、受限行级存储契约 |

### 2. 实现层 `com.chilicraft.core.*`（cc-core）

- `ChiliCorePlugin`：装配与生命周期
- `api/ChiliApiImpl`：契约实现，onEnable 时注册进 Bukkit `ServicesManager`
- `event/EventBusImpl`：字符串事件名 → 订阅者列表，**主线程同步分发**
- `economy/SoulService`：灵魂台账（LedgerEntry，全部变动留痕）
- `param/ParamService`：`params[mode]` 基准值 + 家园区加成的合并计算
- `profile/*`：档案仓库与登录预载
- `database/*`：HikariCP + SQLite（WAL），**单线程写 executor**，`SchemaMigrations` 管表结构
- `module/ModuleRegistry`：按模块 ID 管理菜单入口与命令路由，拒绝重复/别名冲突，查询返回排序快照；不保存 `Player`
- `command/CcCommand`：统一 `/cc` 核心子命令与模块路由；`gui/MenuGui` 为 27 格主菜单，`gui/CategoryGui` 为 54 格分类页，`gui/HelpGui`/`gui/AdminMenuGui` 提供帮助和低风险管理入口
- `gui/*`、`text/Messages`、`config/*`、`integration/IntegrationManager`（PlaceholderAPI/Vault 等软依赖）

### 3. 附属层 `com.chilicraft.<module>`

每模块固定五件套：

| 角色 | 约定 |
|---|---|
| 主类 `Chili<X>Plugin` | 取 API → 注册 ModuleConfig → 装配服务 → 启动周期任务 → 订阅事件 |
| `<X>Settings` | 数值缓存：`refresh()` 从 config 重建全部字段，字段包内可见 |
| `<X>Service` | 纯业务逻辑，无静态状态 |
| `<X>Listener` | Bukkit 事件（仅低频事件） |
| `<X>Task` | 周期任务，单任务单句柄 |

## 装配模式（附属 onEnable 标准流程）

```java
// 1. 硬依赖核心：ServicesManager 取 API，取不到则自禁用
RegisteredServiceProvider<ChiliCraftAPI> reg =
        getServer().getServicesManager().getRegistration(ChiliCraftAPI.class);
if (reg == null) { /* log.error + disablePlugin + return */ }
api = reg.getProvider();

// 2. 注册模块配置（核心据此管理其默认值与数据文件）
api.registerModuleConfig("cc-xxx", new LiveModuleConfig(this));

// 3. 重建数值缓存 → 装配服务 → 启动周期任务
settings.refresh();

// 4. 统一入口在业务对象就绪后注册一次
api.registerMenuEntry(menuEntry);
api.registerCommandRoute(commandRoute);

// 5. 订阅：core.reload 热载 + 其他模块的业务事件
reloadHandler = (eventName, data) -> { reloadConfig(); settings.refresh(); };
api.subscribe("core.reload", reloadHandler);
```

plugin.yml 一律声明 `depend: [cc-core]`；核心自身无依赖。附属间**零编译期依赖**，跨模块协作只走事件总线（如 cc-martial 订阅 `adventure.dungeon_clear`、各附属订阅 `street.world_enter`，发布方尚未实现也不影响编译与运行）。

## 数据流

### 参数流（强度 = 配置 × 模式参数 × 家园区）

```
cc-core config.yml params[adventure|cozy]      （模式基准值）
        └─ ParamService：叠加家园区加成（如自家园区 MOB_SPAWN_RATE × 0.1）
                └─ api.getParam(playerId, ParamKey.X)   （主线程调用）
                        └─ 附属按每次调用穿透读取，模式切换即刻生效
```

附属自身数值不缓存到玩家侧；模块配置数字改动经 `core.reload` 事件热载。菜单入口和命令路由不在 reload 中重新注册，仅刷新文案、配置和缓存。所有菜单/Inventory 与玩家操作均由主线程触发。注册描述和回调不得持有 `Player`；模块 onDisable 必须在清理自身状态前调用 `unregisterMenuEntry(moduleId)` 与 `unregisterCommandRoute(moduleId)`，核心禁用时由 `ModuleRegistry.clear()` 清空。

### 事件流

```
发布方：api.publish("模块.事件名", new EventData(playerId, target, amount).put(...))
        └─ 主线程同步分发 → 全部订阅者 handler(eventName, data)
```

事件总线持订阅者**强引用**：附属 onDisable 必须 `unsubscribe`，否则内存泄漏与幽灵回调。

### 持久化

- SQLite（WAL 模式）+ HikariCP；写操作经单线程 executor 串行（单写者模型），读可并发
- 档案登录预载、按 `auto-save.interval-seconds`（下限 5 秒）自动保存
- 附属持久化走 `api.rowStore()` 受限行级 CRUD（表名白名单），完成回调在 DB 线程，操作游戏状态须回主线程
- 表结构由 `SchemaMigrations` 版本化迁移管理（V1 首期表；V2 规格 v1.1 方街 4 表：`cc_street_memories`、`cc_mail_letters`、`cc_park_records`、`cc_souvenirs`；V3 为 `cc_quest_progress.updated_at`；V4 为 `cc_quest_rewards` 任务奖励幂等记录）
- `cc-quest` 通过 RowStore 异步保存 UUID 任务进度；领取奖励必须调用 `ChiliCraftAPI.claimQuestReward(...)`，由核心在单个 SQLite 事务中同时更新任务状态、`cc_players.soul`、`cc_soul_ledger` 和奖励幂等表，提交成功后才回主线程更新任务内存状态并发布完成事件。
- 任务奖励 API 只能从主线程发起，数据库事务在核心 DB writer 线程执行；`rewardId` 使用玩家 UUID、章节 ID 和任务 ID 组成，防止重复领取。
- 档案与灵魂**禁止**走 RowStore，使用 API 专有方法

## 命令与权限

核心入口：`/menu`、`/cc`、`/cc menu` 打开同一个 `MenuGui`；`/cc` 的别名为 `/chilicraft`。核心子命令包括 `help [模块]`、`info`、`home`、`sethome`、`balance`、`soul`、`mode adventure|cozy` 和 `reload`（管理员）。`/cc soul` 保持余额查询，`/cc soul menu` 才路由到灵魂模块。

模块统一入口为 `/cc <module> [子命令]`：当前 `survival`、`demon`、`soul`、`martial`、`adventure`、`season`；季节路由另登记别名 `ccseason`。旧顶层入口 `/demon`、`/soul`、`/martial`、`/adventure`、`/season`、`/ccseason` 保留，并与统一入口复用模块命令处理器。`quest`、`cozy`、`events`、`economy`、`street` 是规划入口，统一命令和菜单只提示开发中。

核心权限：`chilicraft.menu`（默认 true，兼容 `chilicraft.use`）、`chilicraft.mode`、`chilicraft.home`（默认 true）、`chilicraft.admin`（默认 op）。模块登记 `chilicraft.<module>.use` 与可选 `chilicraft.<module>.admin`；灵魂、武学、冒险同时兼容旧 `*.user`，季节管理同时兼容 `ccseason.admin`。路由重复执行权限检查，Tab 按前缀/可用性/权限过滤，管理候选不向普通玩家暴露。

## 巡演联动（v1.1）

模块间联动一律走事件总线与配置名单，核心零改动。已落地的六个联动点见 README「巡演联动一览」；cc-street 规格（街区世界、游乐园玩法、`street.*` 事件族）见规格文档 v1.1 第 cc-street 章。
