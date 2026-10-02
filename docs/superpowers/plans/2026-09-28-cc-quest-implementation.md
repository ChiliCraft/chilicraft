# cc-quest 任务与章节系统实施计划

- 对应规格：`docs/superpowers/specs/2026-09-28-cc-quest-design.md`
- 目标环境：Paper 1.21.1、Java 21、Gradle Kotlin DSL
- 实施边界：只实现首版任务/章节闭环；不实现完整六章剧情、NPC 对话树、分支并行、回滚、跨服同步或外部依赖。
- 本计划基于已核对的真实源码：`settings.gradle.kts`、`cc-core` 公开 API/数据库实现/GUI 实现、`cc-survival` 模块模板，以及 `README.md`、`docs/architecture.md`、`docs/events-protocol.md`、`docs/module-dev-guide.md`。
- 本轮只写计划，不创建 `cc-quest`，不改代码，不运行构建，不提交 Git。

## 1. 已核对的真实约束与需先处理的差异

### 1.1 工程与依赖

当前 `settings.gradle.kts` 已 include `cc-core`、`cc-survival`、`cc-demon`、`cc-soul`、`cc-martial`、`cc-adventure`、`cc-season`，没有 `cc-quest`。根构建脚本统一 Java 21、UTF-8、Paper API 和版本号注入；附属模块使用 `java-library`，只用 `compileOnly(project(":cc-core"))`、Paper API 和已有 slf4j API，不打包运行时依赖。

### 1.2 ChiliCraftAPI 与线程契约

真实公开入口是 `cc-core/src/main/java/com/chilicraft/api/ChiliCraftAPI.java`：

- `getProfile(UUID)`、`getSoul(UUID)`、`addSoul(UUID, int, String)` 等档案/灵魂方法；除读取外，API 实现对写入方法要求主线程。
- `subscribe(String, EventHandler)`、`unsubscribe(String, EventHandler)`、`publish(String, EventData)`；事件总线同步在主线程分发。
- `registerModuleConfig(String, ModuleConfig)` 与 `rowStore()`。
- `registerMenuEntry(ModuleMenuEntry)` / `unregisterMenuEntry(String)`。
- `registerCommandRoute(ModuleCommandRoute)` / `unregisterCommandRoute(String)`。

`ChiliApiImpl` 将菜单和命令注册转交给 `ModuleRegistry`；`ModuleRegistry` 的注册、注销、查询都要求主线程，注册重复 ID/别名会抛异常。因此 `cc-quest` 的注册和 GUI 回调只能走主线程，异步数据库完成回调必须 `Bukkit.getScheduler().runTask(plugin, ...)` 切回主线程后才能碰玩家、Inventory 或服务状态。

### 1.3 RowStore 的真实调用方式

`cc-core/src/main/java/com/chilicraft/api/RowStore.java` 的实际签名为：

```java
CompletableFuture<Integer> upsert(String table, Map<String, Object> row, String... keyColumns);
CompletableFuture<List<Map<String, Object>>> select(String table, String where, Object... args);
CompletableFuture<Integer> delete(String table, String where, Object... args);
```

`cc-core/src/main/java/com/chilicraft/core/database/RowStoreImpl.java` 已将 `cc_quest_progress` 放进表白名单，UUID 会自动序列化为字符串，where 值必须使用 `?` 参数绑定。SQL 在核心 DB 单线程队列执行，future 回调在 DB 线程；不能在回调中直接调用 Bukkit。

计划中的真实调用形态：

```java
Map<String, Object> row = new LinkedHashMap<>();
row.put("player_id", playerId);
row.put("chapter_id", chapterId);
row.put("quest_id", questId);
row.put("state", state.name());
row.put("progress", progress);
row.put("completed_at", completedAtOrNull);
api.rowStore().upsert("cc_quest_progress", row,
        "player_id", "chapter_id", "quest_id")
    .whenComplete((count, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
        // 只在这里记录错误、标记加载/保存结果或刷新在线玩家 GUI
    }));
```

读取使用：

```java
api.rowStore().select("cc_quest_progress",
        "player_id = ?", playerId)
    .whenComplete((rows, error) -> Bukkit.getScheduler().runTask(plugin, () -> {
        // 主线程校验 UUID、合并到内存状态、必要时刷新 GUI
    }));
```

不得拼接玩家 UUID、任务 ID 或 target 到 where；不得使用 `insert` 代替复合主键 `upsert`；不得使用 `join`、`get` 或同步等待阻塞主线程。

### 1.4 数据表字段差异

设计规格第 6 节列出 `updated_at`，但真实 `SchemaMigrations` 的 V1 表 `cc_quest_progress` 只有：`player_id`、`chapter_id`、`quest_id`、`state`、`progress`、`completed_at`，没有 `updated_at`。目前没有可供 cc-quest 直接调用的迁移 API，且附属不得直连数据库。

实施前必须作出并记录以下唯一选择：

1. **严格按规格**：修改 `cc-core/src/main/java/com/chilicraft/core/database/SchemaMigrations.java`，追加 V3 迁移 `ALTER TABLE cc_quest_progress ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0`，并在 upsert 行中写入它；同步更新 `docs/architecture.md` 的迁移说明。该选择突破“cc-core 默认零改动”，但满足规格字段要求。
2. **保持现有核心零改动**：将 `completed_at` 作为现有唯一时间列，计划和实现文档明确这是与规格的已知偏差。

推荐选择 1，因为 `completed_at` 的语义不能可靠替代每次状态变更时间；不得在计划或代码中伪造 `updated_at` 已存在。若选择 1，V3 必须先于 cc-quest 功能代码实施并确保旧数据库可升级；V1/V2 DDL 不得改写。

### 1.5 GUI 与命令真实 API

GUI 只能复用公开可用的 `cc-core/src/main/java/com/chilicraft/core/gui/GuiHolder.java` 和全局已注册的 `GuiListener`：

- `new GuiHolder(int size, Component title)`，尺寸自动规整到 9 的倍数、1–6 行。
- `holder.set(int slot, ItemStack item)` 或 `holder.set(int slot, ItemStack item, GuiHolder.ClickAction)`。
- `holder.open(Player)`。
- `GuiListener` 通过 `InventoryHolder instanceof GuiHolder` 识别，统一取消菜单点击和拖拽，包括 Shift 点击、数字键、双击和玩家背包区点击；动作收到 `(Player, ClickType)`。

不要按标题字符串注册监听器，不要创建重复的全局 GUI 监听器，不要把 `GuiHolder` 或 `Player` 放进长期缓存。每次打开新建页面；点击时按任务 ID 从当前 `QuestService` 状态重新查找，不使用页面构建时过期的定义对象。

命令真实签名来自：

- `ModuleCommandExecutor#execute(CommandSender sender, String[] args)`，返回 `boolean`。
- `ModuleTabCompleter#complete(CommandSender sender, String[] args)`，返回 `List<String>`。
- `new ModuleCommandRoute(moduleId, aliases, usePermissions, adminPermissions, executor, completer)`。

菜单真实签名来自：

- `new ModuleMenuEntry(moduleId, displayName, MenuCategory, Material, sortOrder, usePermissions, adminPermissions, description, BooleanSupplier available, Consumer<Player> openAction, Consumer<Player> helpAction)`。

统一入口模块 ID 应为 `quest`，配置注册 ID 为 `cc-quest`，权限为 `chilicraft.quest.use` 与 `chilicraft.quest.admin`；没有必要声明旧顶层 `/quest`，统一入口由核心 `/cc` 路由提供。

## 2. 目标工程骨架与资源

### 2.1 工程接入

**修改：** `settings.gradle.kts`

- 在现有附属 include 后追加 `include("cc-quest")`。
- 不改根项目名、Foojay 插件或已有模块顺序之外的内容。

**新增：** `cc-quest/build.gradle.kts`

- 复制 `cc-survival/build.gradle.kts` 的真实结构。
- 使用 `plugins { id("java-library") }`。
- 仅添加 `compileOnly(project(":cc-core"))`、`compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")`、已有 `compileOnly("org.slf4j:slf4j-api:2.0.13")`。
- `tasks.processResources` 用 `version` 展开 `plugin.yml` 的 `${version}`。
- 不使用 shadow、不加 YAML/数据库/命令框架依赖；Bukkit/Paper 自带 `YamlConfiguration` 足够加载 `chapters.yml`。

### 2.2 资源文件

**新增：** `cc-quest/src/main/resources/plugin.yml`

至少包含：

- `name: cc-quest`、`version: "${version}"`、`main: com.chilicraft.quest.ChiliQuestPlugin`、`api-version: "1.21"`。
- `depend: [cc-core]`、`compatible-core: ">=1.0"`、`softdepend: []`。
- `chilicraft.quest.use`，默认 `true`；`chilicraft.quest.admin`，默认 `op`，children 包含 use。
- 不声明对其他附属的依赖，不伪造 `/quest` 顶层命令。

**新增：** `cc-quest/src/main/resources/config.yml`

- 模块运行配置与 `messages` 段分离。
- 包含 GUI 标题、按钮、状态、权限/错误、奖励、重置/重载反馈等 MiniMessage 模板。
- 缺失消息返回空串或代码默认值；解析失败回退纯文本，不阻断模块。
- 所有注释使用中文，数值默认值与 `QuestSettings.refresh()` 的兜底一致。

**新增：** `cc-quest/src/main/resources/chapters.yml`

- 按规格使用 `chapters.<chapter-id>.title/order/tasks`。
- 每个任务至少有 `id/title/description/target.event/target.amount/reward.souls`；可选 `target.target`、`target.value`。
- 放置一份可运行的最小首章示例，不能声称实现完整六章剧情。
- 文件缺失时通过 `saveResource("chapters.yml", false)` 释放默认文件；运行时由独立内容加载器读取，不把章节内容混入 `config.yml`。

### 2.3 Java 文件布局

**新增目录：** `cc-quest/src/main/java/com/chilicraft/quest/`

建议文件与职责：

- `ChiliQuestPlugin.java`：服务获取、配置注册、服务装配、事件/路由/菜单注册、禁用清理。
- `QuestSettings.java`：`config.yml` 运行配置与消息快照，`refresh()` 整体重建。
- `LiveModuleConfig.java`：仿照 `cc-survival/.../LiveModuleConfig.java`，每次 getter 穿透 `plugin.getConfig()`，`moduleId()` 返回 `cc-quest`。
- `QuestContentLoader.java`：从 `YamlConfiguration` 读取 `chapters.yml`，校验后构建不可变章节/任务快照和事件索引。
- `ChapterDefinition.java`、`QuestDefinition.java`、`QuestTarget.java`、`QuestReward.java`：不可变内容模型；不要引用 Bukkit 玩家对象。
- `QuestState.java`、`ChapterState.java`、`PlayerQuestProgress.java`：状态枚举和 UUID 归属的内存状态；明确不以 `Player` 为长期 Map 键。
- `QuestRepository.java`：唯一的 RowStore 访问层，负责行映射、异步读写、错误传播，不执行 Bukkit 操作。
- `QuestService.java`：主线程状态机、玩家状态缓存、事件匹配、章节解锁、奖励幂等和事件发布。
- `QuestListener.java`：低频 Bukkit 事件（至少玩家首次进入/打开页面所需的生命周期入口）；不得监听 PlayerMoveEvent 或其他高频事件。
- `QuestCommand.java`：实现共享 `execute/complete`，供 `ModuleCommandRoute` 使用。
- `QuestGui.java`：旅途手册和任务详情页，每次打开快照式构建。

## 3. 模型与状态机实施细节

### 3.1 内容模型

`QuestDefinition` 至少保存：章节 ID、任务 ID、标题、描述、`QuestTarget(event, amount, target?, value?)`、`QuestReward(souls)`。ID 在解析阶段规范化并检查：章节 ID 全局唯一、任务 ID 在章节内唯一、事件名非空、amount 为正、灵魂奖励为非负整数。重复/非法内容不得生成半份索引；加载失败保留上一份有效快照并记录警告。

`ChapterDefinition` 保存章节标题、order 和不可变任务列表。按 `order` 决定章节顺序，不依赖 YAML map 的偶然顺序。

### 3.2 玩家状态

长期缓存使用 `Map<UUID, PlayerQuestProgress>`；进度对象内部以章节 ID/任务 ID 查找。每条状态至少保存 `QuestState` 和进度数值，加载完成标志与脏状态应独立于业务状态，避免异步加载尚未完成时误覆盖在线变化。

任务状态严格实现：

```text
LOCKED -> AVAILABLE -> ACTIVE -> COMPLETED -> CLAIMED
```

章节状态严格实现：

```text
LOCKED -> ACTIVE -> COMPLETED
```

章节激活时只将首个符合条件的任务置为 `AVAILABLE`。首次打开详情或首次收到对应目标事件时，`AVAILABLE -> ACTIVE`。达到 amount 后 `ACTIVE -> COMPLETED`。章节内全部任务为 `CLAIMED` 后章节 `COMPLETED`，并发布 `quest.chapter_cleared`。

状态机方法必须主线程执行并只接受合法转换；非法重复事件只保持原状态。事件带来的进度不能突破目标 amount，不能推进 `CLAIMED` 任务，不能重复发奖。

### 3.3 事件匹配字段

订阅以下真实已登记事件，不新增事件定义：

| 事件 | `playerId()` | `target()` | `amount()` | 扩展字段/匹配说明 |
|---|---|---|---:|---|
| `demon.mob_killed` | 击杀者 | 实体类型 ID | 1 | 可用任务 `target.target` 匹配实体类型 |
| `soul.relic_gained` | 玩家 | 遗物 ID | 1 | 任务 target 可限制遗物 ID |
| `martial.arena_win` | 冠军 | `arena` | 1 | 通常按事件名和正 amount 匹配 |
| `martial.realm_up` | 玩家 | 境界 ID | 新境界序号 | `target.value` 可匹配境界序号/target 可匹配境界 ID |
| `adventure.boss_killed` | 击杀者 | Boss ID | 1 | `target.target` 匹配 Boss ID；`firstKill` 如需限制，读取 `data.get("firstKill")` |
| `adventure.dungeon_clear` | 队员 | 地城 ID | 灵魂奖励 | `target.target` 匹配地城 ID；amount 是奖励，不应盲目当作次数，可在目标语义中规定按事件计数 1 |
| `adventure.expedition_result` | 队员 | `expedition` | 到达层数 | 可读取 `layer/result/soul`；任务 target/value 规定层数或结果匹配 |
| `season.special_day` | null | 配置联动标识 | 年份 | 这是世界级事件，首版只有在规格允许的全服/名单语义下处理；若任务要求玩家进度必须跳过并记录原因，不伪造 playerId |

事件回调只做：检查 `data`/`playerId`、事件名索引、target/value 匹配、在主线程状态机中更新内存、提交异步保存、给在线玩家反馈。不得在回调中 select 数据库、读取文件或等待 future。事件总线同步分发，不补发插件禁用期间历史事件。

> `events-protocol.md` 中 `quest.quest_completed` 与 `quest.chapter_cleared` 已属于规划事件。实现发布时先把实际发布语义和字段补写到 `docs/events-protocol.md`，再编码；不能先写代码再登记。

## 4. RowStore 持久化与奖励幂等

### 4.1 Repository 读写

`QuestRepository#load(UUID)` 调用 `select("cc_quest_progress", "player_id = ?", playerId)`，在 DB 线程只解析 JDBC 原生值：`String`、`Integer/Long`、`null`。解析后回主线程合并状态。玩家首次登录或首次打开任务页面触发一次加载，使用 `loading` 集合去重；加载失败不清掉现有内存状态，并给在线玩家配置化错误提示。

`save(PlayerQuestProgress)` 使用 `upsert`，主键列精确为 `player_id/chapter_id/quest_id`。提交前复制不可变行数据，避免异步任务读取会继续改变的可变对象。future 失败只记录错误、保留内存状态并将对象标记为待重试；下一次合法状态变化再次 upsert。不得使用 `completed_at` 存储非完成状态的更新时间，除非最终明确选择“核心零改动”的差异方案。

若采用推荐的 V3 迁移，行包含 `updated_at = System.currentTimeMillis()`；任务进入 COMPLETED 时另写 `completed_at`，CLAIMED 保留完成时间。读取时缺少旧数据库字段应依靠迁移完成，不在附属层猜测。

### 4.2 奖励事务边界

奖励发放必须在主线程：

1. 重新从当前内存状态按 UUID/章节 ID/任务 ID 查找任务；
2. 只有 `COMPLETED` 才进入领取；`AVAILABLE/ACTIVE/CLAIMED` 均拒绝；
3. 先确认玩家在线、奖励配置为有效非负值；
4. 调用真实 API `api.addSoul(playerId, souls, "quest_reward_<quest-id>")`，reason 使用稳定蛇形且不得为空；
5. API 调用成功后立即将状态转为 `CLAIMED`、更新章节状态并异步 upsert；
6. 发送一次领取反馈并发布 `quest.quest_completed`，章节全部领取后再发布 `quest.chapter_cleared`。

`CLAIMED` 是重复点击/重复事件的幂等闸门。由于 `RowStore` 没有与灵魂 API 的跨表事务，不能宣称“数据库与灵魂奖励原子事务”；实现应以主线程状态闸门和 `CLAIMED` 持久化避免重复执行，并在保存失败时保留内存已领取状态、记录错误，不自动再次发奖。重启前若领取写入失败是首版不可避免的风险，需在计划验收中明确测试和日志口径，不通过重复发奖掩盖它。

## 5. 插件生命周期、重载与注册

### 5.1 `onEnable` 顺序

在 `cc-quest/src/main/java/com/chilicraft/quest/ChiliQuestPlugin.java` 中严格按以下顺序：

1. `saveDefaultConfig()` 和 `saveResource("chapters.yml", false)`；
2. 通过 `ServicesManager#getRegistration(ChiliCraftAPI.class)` 获取 API，取不到记录错误并 `disablePlugin(this)`；
3. 创建 `QuestSettings`，调用 `refresh()`；注册 `api.registerModuleConfig("cc-quest", new LiveModuleConfig(this))`，捕获重复注册异常；
4. 创建/校验 `QuestContentLoader`，先保证有有效章节快照；若默认/当前文件无效且无上一份有效快照，安全禁用而不是运行半配置；
5. 创建 `QuestRepository`、`QuestService`、`QuestGui`、`QuestCommand`；
6. 注册 `QuestListener`（只低频事件）；
7. 注册所有事件 handler 字段，事件名和退订一一对应；
8. 主线程一次性注册 `ModuleMenuEntry("quest", ...)` 与 `ModuleCommandRoute("quest", Set.of(), Set.of("chilicraft.quest.use"), Set.of("chilicraft.quest.admin"), command::execute, command::complete)`；任一注册失败立即回滚另一个并禁用；
9. 订阅 `core.reload` 最后完成热载闭环。

菜单入口建议使用 `MenuCategory.PROGRESSION`、`Material.WRITTEN_BOOK` 或其他 Paper 已存在的稳定图标、固定排序值；`openAction` 捕获 `QuestGui` 门面而不是玩家。帮助回调只发送配置化说明。

### 5.2 `onDisable` 顺序

1. 取消本模块创建的所有 Bukkit task（如确实需要延迟重试，使用单句柄）；
2. 对所有任务事件执行对应 `api.unsubscribe`，再退订 `core.reload`；
3. `api.unregisterCommandRoute("quest")` 和 `api.unregisterMenuEntry("quest")`；
4. 清理 GUI 会话/UUID 状态/加载集合，关闭中的 GUI 不再刷新；
5. 将字段置 null，避免注册表或 handler 继续持有模块对象。

### 5.3 `core.reload`

reload handler 只做：`reloadConfig()`、`settings.refresh()`、重新读取并校验 `chapters.yml`、成功后原子替换内容快照/事件索引。配置或内容无效时保留上一份有效快照并记录告警；不得清空玩家进度，不重复调用菜单/命令注册 API，不在异步回调中直接刷新 GUI。在线玩家下次打开页面使用新快照；当前打开页面可由成功的主线程 reload 关闭或在下一次交互时按 ID 重新构建，不能引用旧定义。

## 6. 命令与 GUI 交互计划

### 6.1 命令矩阵

`QuestCommand#execute(CommandSender, String[])` 实现：

- 无参数、`guide`：仅玩家可用，打开旅途手册；控制台提示玩家专属。
- `status`：玩家显示当前章节/任务摘要；控制台提示玩家专属。
- `claim <任务ID>`：仅玩家领取当前任务；按当前章节查找任务，不允许领取其他玩家或锁定任务。
- `admin reload`：要求 `chilicraft.quest.admin`，调用与 core.reload 相同的配置/内容刷新方法，但只由核心事件/主线程路径执行。
- `admin reset <玩家>`：要求 admin；解析 Bukkit 在线玩家或 UUID 的安全目标，删除该玩家的 `cc_quest_progress` 行并在主线程重置缓存。删除使用 `delete("cc_quest_progress", "player_id = ?", targetUuid)`，不允许空 where；如果目标在线，删除完成后再主线程重新初始化首章状态。

`complete` 只返回有权限且匹配前缀的候选；普通玩家不看到 `admin`、`reset` 或其目标候选。模块命令 executor 本身仍二次校验 admin，不能只依赖 `ModuleCommandRoute` 的权限集合。

### 6.2 旅途手册与详情页

`QuestGui` 使用两个快照式页面：

- 旅途手册：章节标题/状态、任务列表、每项进度、完成/已领取状态、返回主菜单、关闭；左键任务详情，右键对 COMPLETED 任务进入领取路径。
- 任务详情：标题、描述、目标、当前进度、奖励、状态；返回手册、关闭；完成状态提供领取按钮。

每个页面新建 `GuiHolder(27/54, Component)` 并用 `set(slot, item, clickAction)` 绑定动作。点击回调先根据稳定的章节 ID/任务 ID重新向 `QuestService` 查询，再按当前状态处理；不从闭包中使用过期的 `QuestDefinition` 或长期保存 `Player`。所有菜单物品由 `GuiListener` 统一取消交互，代码不另行处理 shift/drag 分支。

异步首次加载完成后只在 `runTask` 中确认玩家在线且仍打开本模块页面，再调用 `QuestGui.openGuide(player)` 重建页面；玩家已关闭/模块已禁用则静默放弃。

## 7. 事件登记与文档/资源同步

由于首版会实际发布 `quest.quest_completed` 和 `quest.chapter_cleared`，实施顺序必须是：

1. 修改 `docs/events-protocol.md`，登记事件名、`playerId`、`target`、`amount` 和无扩展字段约定，并说明章节全部任务领取完成才发布；
2. 再实现 `QuestService` 的 publish；
3. 若规格最终决定不发布其中任一事件，则删除对应代码计划和资源文案，不留下未登记事件。

按 AGENTS.md 的新附属模块同步义务，代码完成后还必须更新：

- `README.md`：模块总览新增 `cc-quest`，说明统一入口 `/cc quest`、旅途手册、首版范围和不包含完整六章剧情；巡演联动一览只在确有新联动时补充。
- `docs/architecture.md`：工程结构新增 `cc-quest`，附属组件表补充内容加载/异步 RowStore/任务状态机的实际范式；持久化段注明任务表字段和迁移版本。
- `docs/module-dev-guide.md`：只在本模块沉淀出通用范式时补充“内容 YAML 快照 + RowStore 异步回主线程 + GUI 会话清理”，不顺手重写既有章节。
- `docs/performance.md`：若没有周期任务，不新增周期表；如果为失败重试确实新增定时任务，登记单句柄、周期和逐玩家 try-catch 口径。
- `settings.gradle.kts`、`cc-quest/build.gradle.kts`、`plugin.yml`、`config.yml`、`chapters.yml` 与 Java 文件保持互相一致。

## 8. 按依赖顺序的实施步骤与每步验证

### 步骤 0：确认字段方案和事件登记

**文件：** `docs/events-protocol.md`、（推荐方案）`cc-core/src/main/java/com/chilicraft/core/database/SchemaMigrations.java`、`docs/architecture.md`。

先解决 `updated_at` 与真实表不一致；若采用 V3，追加不修改 V1/V2 的升级迁移并登记。随后登记 cc-quest 发布的两个事件。此步不添加业务代码。

### 步骤 1：建立工程和资源骨架

**文件：** `settings.gradle.kts`、`cc-quest/build.gradle.kts`、`cc-quest/src/main/resources/plugin.yml`、`config.yml`、`chapters.yml`。

只建立 Gradle、插件描述、权限和可加载的最小资源，确认无其他附属依赖、无新库、版本占位符一致。

### 步骤 2：实现配置与内容快照

**文件：** `cc-quest/src/main/java/com/chilicraft/quest/QuestSettings.java`、`LiveModuleConfig.java`、`QuestContentLoader.java`、`ChapterDefinition.java`、`QuestDefinition.java`、`QuestTarget.java`、`QuestReward.java`。

先实现完整校验、默认回退、快照整体替换和事件索引；不接玩家、不接数据库。验证非法章节不会替换有效快照，事件索引按事件名生成。

### 步骤 3：实现状态模型和 RowStore repository

**文件：** `QuestState.java`、`ChapterState.java`、`PlayerQuestProgress.java`、`QuestRepository.java`。

实现 UUID 状态缓存模型和行映射，严格使用 `api.rowStore()` 的真实 future API。验证 select/upsert/delete 的 where、复合主键、UUID 字符串化、DB 回调回主线程；不使用任何核心内部类或 SQLite 连接。

### 步骤 4：实现主线程服务与奖励流程

**文件：** `QuestService.java`、必要时 `QuestListener.java`。

完成状态转换、事件索引推进、章节激活、异步加载合并、保存失败重试标记、奖励 `api.addSoul`、`CLAIMED` 幂等和两个任务事件发布。所有 Bukkit/灵魂/GUI 操作都在主线程。验证重复事件、重复点击和重启加载的状态边界。

### 步骤 5：实现 GUI

**文件：** `QuestGui.java`。

只依赖 `GuiHolder` 的真实构造/set/open API；实现旅途手册和详情页、加载回主线程、任务 ID 二次查询、返回/关闭、完成领取和模块禁用会话清理。验证所有点击/拖拽由核心监听器取消，页面不保存 Player。

### 步骤 6：实现命令和插件装配

**文件：** `QuestCommand.java`、`ChiliQuestPlugin.java`、必要时 `QuestListener.java`。

按真实 `ModuleCommandRoute`/`ModuleMenuEntry` 构造签名注册一次；实现 `/cc quest`、`guide`、`status`、`claim`、`admin reload/reset`、Tab 权限过滤和控制台提示。装配和禁用顺序按第 5 节执行，注册失败回滚。

### 步骤 7：同步项目文档

**文件：** `README.md`、`docs/architecture.md`、必要时 `docs/module-dev-guide.md`、`docs/performance.md`、`docs/events-protocol.md`、`settings.gradle.kts`。

只描述已经实现的 cc-quest 行为，不能把规划中的完整剧情或不存在的核心 API 写成已完成。

### 步骤 8：构建与静态验收

按 AGENTS.md 的命令执行，先常规构建：

```powershell
.\gradlew.bat :cc-quest:build
```

若明确是 Paper 仓库元数据/TLS/网络失败，再执行：

```powershell
.\gradlew.bat :cc-quest:build --offline
.\gradlew.bat build --offline
```

最终全量验收命令仍是：

```powershell
.\gradlew.bat build
```

不启动 Minecraft 服务端。使用 Java diagnostics 检查本次新增/修改文件无新增错误；静态检查每个 subscribe 有 unsubscribe、每个注册有 unregister、reload 不重复注册、没有 `Player` 作为长期 Map 键、没有附属直连 SQLite、没有新增未登记事件。

## 9. 风险与回滚

1. **表字段风险：** 当前真实表没有 `updated_at`。优先通过核心 V3 迁移解决；若不能改核心，必须在实施前书面接受偏差，不能伪造字段。
2. **异步线程风险：** RowStore 回调在 DB 线程；任何 Bukkit、灵魂、GUI、状态机写入必须通过 `runTask` 回主线程。禁止 future `join/get`。
3. **奖励重复风险：** 灵魂 API 与 RowStore 没有跨表事务；`CLAIMED` 主线程闸门、单线程点击处理和失败留痕是首版边界。保存失败不能自动再次发放。
4. **旧内容/重载风险：** 内容解析失败保留上一份有效快照；重载不清玩家进度、不重新注册入口；章节 ID/任务 ID 变更会使旧进度失配，配置文档应要求稳定 ID。
5. **事件语义风险：** `adventure.dungeon_clear` 的 amount 是灵魂奖励，`season.special_day` 没有 playerId；任务目标必须显式定义计数/匹配规则，不能把所有 amount 都当任务次数。
6. **GUI 生命周期风险：** 核心 `GuiListener` 持有并分发 `GuiHolder` 动作；禁用时清理本模块会话并注销入口，异步回调检查插件和玩家状态后再刷新。
7. **注册冲突风险：** `ModuleRegistry` 主线程拒绝重复 ID/别名；注册菜单成功而命令失败时立即注销菜单并禁用本模块，反之亦然。
8. **依赖/网络风险：** 不引入新依赖；Paper SNAPSHOT 获取失败时只使用 `--offline`，不要改 Java 版本或构建脚本全局约定。
9. **文档漂移风险：** 若最终采用 V3、变更事件字段或增加重试任务，必须同步对应 docs；文档未同步不得收尾。

## 10. 完成定义

- `cc-quest` 已被 settings include，资源和 Java 骨架符合附属模板，只有 compileOnly 依赖。
- 配置和章节内容可校验、失败保留上一份有效快照，状态机按规格运行。
- `cc_quest_progress` 通过真实 RowStore future API 异步读写，所有游戏操作回主线程。
- 八个已登记事件按真实 `EventData` 字段匹配；新发布事件先登记后实现。
- `/cc quest` 及全部子命令、Tab、权限、控制台限制和统一菜单入口成立。
- GUI 使用真实 `GuiHolder`/`GuiListener` 生命周期，禁止所有取物路径，禁用清理会话。
- 奖励仅从 COMPLETED 领取，CLAIMED 阻止重复奖励；失败状态可诊断且不自动重复发放。
- reload 不重复注册、不重置进度；disable 成对取消订阅/注销路由/清理缓存。
- README、architecture、module-dev-guide（如适用）、events-protocol、performance（如适用）和迁移说明与实际行为一致。
- `:cc-quest:build` 和根 `build` 按 AGENTS.md 通过；不启动服务端、不提交构建产物。
