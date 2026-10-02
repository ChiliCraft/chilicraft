# ChiliCraft 统一指令与分类导航菜单实施计划

- 对应设计：`docs/superpowers/specs/2026-09-28-unified-command-menu-design.md`
- 目标环境：Paper 1.21.1、Java 21、Gradle Kotlin DSL
- 实施边界：只实现统一入口、分类菜单、注册 API、权限兼容和六个现有附属接入；不实现五个规划模块业务，不新增跨模块事件，不启动 Minecraft 服务端验收。
- 总体验收：根目录执行 `./gradlew.bat build`；仅在 Paper 仓库网络失败时改用 `./gradlew.bat build --offline`。不引入测试框架或新依赖。

## 1. 实施原则与关键决策

1. 先扩展 `com.chilicraft.api`，再实现核心注册表和菜单/命令消费端，最后按设计指定顺序迁移六个附属。附属始终只编译依赖 `com.chilicraft.api` 与公开的 `GuiHolder`，不得引用 `com.chilicraft.core.*`。
2. 菜单入口与命令路由由同一个核心注册表按规范模块 ID 管理；注册表只保存不可变描述、权限字符串和回调，不保存 `Player`。所有注册、注销、查询和回调执行都要求主线程。
3. 旧顶层命令保留。每个现有命令类抽出统一的 `execute(CommandSender, String[])` 与 `complete(CommandSender, String[])` 分发入口；Bukkit 的 `onCommand`/`onTabComplete` 和 `/cc <module> ...` 都调用它，禁止复制业务逻辑。
4. 核心路由只做模块存在性、基础使用权限、规划模块和异常兜底；管理子命令仍在模块处理器内按“规范管理权限或旧管理权限”二次校验，不能把某模块的基础使用权限当成管理权限。
5. `/cc soul` 保持核心余额查询；仅 `/cc soul menu` 及 `/cc soul <模块子命令>` 路由到 `cc-soul`。新增 `/cc balance` 也查询余额。
6. `core.reload` 只刷新配置、文案和内容缓存。菜单入口及命令路由仅在 `onEnable` 注册，在 `onDisable` 注销，重载处理器内不得重复注册。
7. 当前 `DemonGui` 含生成、清除和重载等管理动作，不能直接作为普通玩家模块页。普通 `/cc demon` 使用新只读状态页；拥有管理权限的管理中心才进入现有 `DemonGui#openMain`。`cc-survival` 和 `cc-season` 同样提供轻量只读/安全操作页面，不增加玩法或高风险管理能力。

## 2. 目标公开 API 形态

### 2.1 新增公开类型

在 `cc-core/src/main/java/com/chilicraft/api/` 新增下列类型，保持 Java 21、不可变值对象和中文 javadoc：

- `MenuCategory.java`：枚举 `SURVIVAL_ADVENTURE`、`PROGRESSION`、`WORLD_ECOLOGY`、`SOCIAL_LIFE`、`ECONOMY_SERVICES`、`TOUR_STREET`。
- `ModuleMenuEntry.java`：不可变 `record`，至少包含 `moduleId`、`displayName`、`category`、`Material icon`、`int sortOrder`、基础使用权限集合、可选管理权限集合、`description`、`BooleanSupplier available`、`Consumer<Player> openAction`、`Consumer<Player> helpAction`。构造时复制权限集合并校验 ID/回调非空；`available` 只表示已注册模块当前是否可进入。
- `ModuleCommandExecutor.java`：函数式接口 `boolean execute(CommandSender sender, String[] args)`。
- `ModuleTabCompleter.java`：函数式接口 `List<String> complete(CommandSender sender, String[] args)`。
- `ModuleCommandRoute.java`：不可变 `record`，包含规范 `moduleId`、别名集合、基础使用权限集合、管理权限集合、执行回调和补全回调；构造时复制集合、规范化 ID/别名并拒绝空值。

权限集合用于兼容迁移：`hasAnyPermission` 语义为集合中任一节点满足即可。例如灵魂基础权限同时登记 `chilicraft.soul.use` 与 `chilicraft.soul.user`，管理权限登记 `chilicraft.soul.admin`。

### 2.2 扩展 `ChiliCraftAPI`

修改 `cc-core/src/main/java/com/chilicraft/api/ChiliCraftAPI.java`，增加：

```java
void registerMenuEntry(ModuleMenuEntry entry);
void unregisterMenuEntry(String moduleId);
List<ModuleMenuEntry> menuEntries();

void registerCommandRoute(ModuleCommandRoute route);
void unregisterCommandRoute(String moduleId);
ModuleCommandRoute getCommandRoute(String moduleIdOrAlias);
List<ModuleCommandRoute> commandRoutes();
```

契约明确：

- 注册与注销只允许主线程；重复模块 ID、重复别名或别名撞规范 ID抛 `IllegalStateException`；参数非法抛 `IllegalArgumentException`。
- 注销不存在的 ID 静默处理，并同步移除该路由拥有的别名。
- 查询返回排序稳定的不可变快照，不暴露内部 `Map`。
- 回调异常由核心调用方捕获；API/注册表本身不吞异常。

## 3. 按依赖顺序执行的任务

### 任务 1：建立公开注册契约和核心注册表

**修改文件**

- `cc-core/src/main/java/com/chilicraft/api/ChiliCraftAPI.java`
- `cc-core/src/main/java/com/chilicraft/core/api/ChiliApiImpl.java`

**新增文件**

- `cc-core/src/main/java/com/chilicraft/api/MenuCategory.java`
- `cc-core/src/main/java/com/chilicraft/api/ModuleMenuEntry.java`
- `cc-core/src/main/java/com/chilicraft/api/ModuleCommandExecutor.java`
- `cc-core/src/main/java/com/chilicraft/api/ModuleTabCompleter.java`
- `cc-core/src/main/java/com/chilicraft/api/ModuleCommandRoute.java`
- `cc-core/src/main/java/com/chilicraft/core/module/ModuleRegistry.java`

**实施步骤**

1. 按第 2 节形态新增 API 类型；只使用 Paper API/JDK 现有类型，不增加依赖。
2. `ModuleRegistry` 使用主线程内普通 `LinkedHashMap` 分别维护菜单项、规范路由和 alias→moduleId；所有写方法先调用与 `ChiliApiImpl#requirePrimaryThread` 同口径的主线程断言。
3. 菜单查询按 `category`、`sortOrder`、`moduleId` 排序；路由查询按 `moduleId` 排序。别名解析使用 `Locale.ROOT` 小写，玩家名和后续参数不在这里改写。
4. 注册路由前一次性校验规范 ID及全部别名冲突，校验通过后再写入所有 Map，避免半注册状态。
5. `ChiliApiImpl` 持有 `ModuleRegistry` 并新增 API 委托方法；由核心装配阶段注入注册表。保留当前 `moduleConfigs` 行为，不顺带改动持久化或事件 API。
6. 提供核心内部 `ModuleRegistry#clear()`，供核心禁用时释放附属回调强引用。

**阶段验证**

```powershell
./gradlew.bat :cc-core:compileJava
```

并检查公开 API 中没有出现 `com.chilicraft.core.*` 类型，注册表字段中没有 `Player`。

### 任务 2：装配注册表并完成核心生命周期清理

**修改文件**

- `cc-core/src/main/java/com/chilicraft/core/ChiliCorePlugin.java`

**实施步骤**

1. 在 `onEnable` 核心服务装配阶段创建唯一 `ModuleRegistry`，注入 `ChiliApiImpl`、统一菜单对象和 `CcCommand`。
2. 保持现有顺序：配置/数据库/档案/服务 → API 注册 → GUI 监听 → 命令；附属只会在核心 API 注册后加载。
3. `onDisable` 在 Bukkit `ServicesManager#unregisterAll(this)` 前执行 `registry.clear()`，确保菜单和命令回调不再强引用附属实例；对半途启用失败保持 null 保护。
4. `reloadAll()` 不调用任何注册方法，只重读核心配置、重启自动保存、重扫集成并发布 `core.reload`。

**阶段验证**

```powershell
./gradlew.bat :cc-core:compileJava
```

静态核对 `register*` 不出现在 `reloadAll()`。

### 任务 3：把核心主菜单改为 27 格分类导航

**修改文件**

- `cc-core/src/main/java/com/chilicraft/core/gui/MenuGui.java`
- `cc-core/src/main/resources/config.yml`

**新增文件**

- `cc-core/src/main/java/com/chilicraft/core/gui/CategoryGui.java`
- `cc-core/src/main/java/com/chilicraft/core/gui/HelpGui.java`
- `cc-core/src/main/java/com/chilicraft/core/gui/AdminMenuGui.java`
- `cc-core/src/main/java/com/chilicraft/core/gui/GuiItems.java`

**实施步骤**

1. 保留 `GuiHolder`/`GuiListener` 的 holder 判定和全点击/拖拽取消逻辑，不新增按标题识别的监听器。`MenuGui#open(Player)` 继续每次新建快照。
2. 将主菜单固定为 27 格：4 档案，10 生存冒险，11 成长体系，12 世界生态，13 模式切换，14 社交生活，15 经济服务，16 巡演方街，21 家园，22 帮助中心，23 灵魂余额，26 关闭；管理中心优先 18，若后续布局冲突才使用设计允许的 25。
3. `GuiItems` 仅收敛本次新增菜单共用的背景、按钮、lore 构建；图标配置或入口图标为 null/非法时回退 `Material.PAPER`，文案通过 `Messages#get(key, default)` 兜底。
4. 主菜单空槽填统一背景。档案沿用现有 `infoHead` 数据；模式入口打开核心模式子页或在同页提供 Adventure/Cozy 两按钮，实际切换仍调用现有 `api.setMode` 和冷却逻辑；家园入口调用现有家园信息/传送路径，余额入口显示 `api.getSoul`。
5. `CategoryGui#open(Player, MenuCategory)` 创建 54 格页面：顶部分类说明，中部按注册表快照的排序值放置入口，45 返回主菜单、49 帮助、53 关闭。每次构建时按基础权限过滤已实现模块。
6. 点击模块槽位时不要直接调用构建快照中捕获的回调：先用 `registry`/`api.getCommandRoute` 或按 moduleId 重查当前 `ModuleMenuEntry`，再检查权限和 `available`，最后在 try/catch 中调用；异常日志包含 moduleId，玩家收到统一失败消息。
7. 左键调用 `openAction`，右键调用 `helpAction`；其他点击类型不执行。已注册但 `available=false` 显示“暂不可用”且不调用回调。
8. 在核心静态表中加入规划入口：任务 `quest`、休闲 `cozy`、世界活动 `events`、经济 `economy`、方街 `street`。它们不注册虚假路由，显示灰色图标，点击只发送“开发中”。
9. `HelpGui` 展示全局命令与当前可见模块说明；`AdminMenuGui` 只在玩家拥有 `chilicraft.admin` 或任一已注册路由的管理权限时显示/打开，仅列只读状态、帮助、核心现有安全重载和模块明确提供的管理入口，不提供插件启停、卸载、JAR 热替换、世界重建或数据清空。
10. 将所有新增标题、分类名、说明、背景、返回、关闭、帮助、开发中、不可用、回调失败、权限不足等玩家文本加入核心 `config.yml` 的 `messages` 段；保留旧键作为现有逻辑兜底，配置无效时使用代码中文默认值。

**阶段验证**

```powershell
./gradlew.bat :cc-core:compileJava :cc-core:processResources
```

静态核对：主菜单和分类页槽位均在边界内；点击前按 moduleId 重查；`GuiListener` 仍覆盖点击、Shift 点击、数字键、双击与拖拽的统一取消路径。

### 任务 4：实现统一 `/cc` 路由、`/menu` 和补全

**修改文件**

- `cc-core/src/main/java/com/chilicraft/core/command/CcCommand.java`
- `cc-core/src/main/java/com/chilicraft/core/ChiliCorePlugin.java`
- `cc-core/src/main/resources/plugin.yml`
- `cc-core/src/main/resources/config.yml`

**新增文件**

- `cc-core/src/main/java/com/chilicraft/core/command/MenuCommand.java`

**实施步骤**

1. `plugin.yml` 为 `cc` 保留 `chilicraft` alias，新增独立 `menu` 命令；新增 `chilicraft.menu`（default true），保留 `chilicraft.use`、`mode`、`home`、`admin`。`MenuCommand` 只校验玩家和 `chilicraft.menu`/兼容 `chilicraft.use` 后调用同一个 `MenuGui#open`。
2. `CcCommand` 固定核心子命令优先级：无参和 `menu` 打开统一菜单；`help [module]`、`info`、`home`、`sethome`、`balance`、`mode`、`reload` 进入核心逻辑；单独 `soul` 继续查询余额。
3. 若首参数是 `soul` 且还有参数，则尝试路由 `soul`；其他非核心首参数直接查询 `ModuleCommandRoute`。找到路由后只复制剩余数组，不改变后续参数大小写，执行基础权限任一匹配检查后调用模块执行器。
4. 对 `quest/cozy/events/economy/street` 的缺失路由返回“开发中”；其他未知模块返回用法和当前有权限候选。
5. 模块执行回调统一 try/catch，日志包含 moduleId、sender 名称和异常，发送 `messages.module-command-failed`；不把堆栈或内部异常文本发给玩家。
6. `/cc help [module]` 无模块时展示全局命令和有权限模块；指定已注册模块时调用其帮助回调；指定规划模块显示开发中；无效值返回候选。
7. Tab 补全：第一参数合并固定核心子命令、已注册且可用且有基础权限的模块 ID/别名、规划 ID；`reload` 仅向核心管理员显示。`mode` 第二参数补全枚举。模块后续参数原样切片交给模块 completer，返回值再按当前输入前缀过滤；模块 completer 负责隐藏其管理子命令。
8. 在主类中校验 `getCommand("cc")` 和 `getCommand("menu")` 均非 null，分别绑定执行器/补全器；缺声明仍视为致命装配错误。
9. 更新核心帮助和 usage 文案，推荐 `/cc balance`，明确 `/cc soul` 兼容查询、`/cc soul menu` 打开灵魂模块。

**阶段验证**

```powershell
./gradlew.bat :cc-core:compileJava :cc-core:processResources
```

通过代码路径表逐项核对：`/menu`、`/cc`、`/cc menu` 指向同一 `MenuGui`；`/chilicraft` 仍为 alias；`/cc soul` 不被模块路由截获。

### 任务 5：接入 `cc-survival`（第一个迁移样板）

**修改文件**

- `cc-survival/src/main/java/com/chilicraft/survival/ChiliSurvivalPlugin.java`
- `cc-survival/src/main/java/com/chilicraft/survival/SurvivalSettings.java`
- `cc-survival/src/main/resources/config.yml`
- `cc-survival/src/main/resources/plugin.yml`

**新增文件**

- `cc-survival/src/main/java/com/chilicraft/survival/SurvivalCommand.java`
- `cc-survival/src/main/java/com/chilicraft/survival/SurvivalGui.java`

**实施步骤**

1. 新增 `chilicraft.survival.use`（default true）与 `chilicraft.survival.admin`（default op）；本轮无管理命令，但先声明规范节点供管理中心判定，不增加管理行为。无需新增顶层 `/survival` 命令。
2. `SurvivalGui#open(Player)` 使用 `GuiHolder` 创建只读状态页，展示当前饥饿、温度、负重、耐久开关及是否处于配置的方街减压世界；附属不能依赖核心 `MenuGui`，因此模块页只提供关闭，主分类页返回由核心负责。不要增加状态修改按钮。
3. `SurvivalCommand` 实现公开模块执行/补全接口：无参、`menu`、`status` 打开/输出只读状态；无效参数返回 `/cc survival [menu|status]` 和候选；控制台执行 `status` 可输出文本，菜单请求明确提示玩家专属。
4. 主类在业务对象和任务装配完成后，订阅事件前注册：菜单 ID `survival`、分类 `SURVIVAL_ADVENTURE`、实际权限仅 `chilicraft.survival.use`、排序靠前；命令路由 ID `survival`、无别名。注册任一失败时回滚已成功项并禁用附属。
5. `onDisable` 在停止任务和退订事件后调用 `unregisterCommandRoute("survival")`、`unregisterMenuEntry("survival")`，再清空字段。`core.reload` 只调用现有 `reloadConfig/settings.refresh`。
6. 把状态页和帮助文案加入 `config.yml` 并由 `SurvivalSettings#refresh()` 缓存。

**阶段验证**

```powershell
./gradlew.bat :cc-survival:compileJava :cc-survival:processResources
```

确认模块仍无新的周期任务、无其他附属编译依赖。

### 任务 6：接入 `cc-demon`，拆开普通状态与管理动作

**修改文件**

- `cc-demon/src/main/java/com/chilicraft/demon/ChiliDemonPlugin.java`
- `cc-demon/src/main/java/com/chilicraft/demon/DemonCommand.java`
- `cc-demon/src/main/java/com/chilicraft/demon/DemonSettings.java`
- `cc-demon/src/main/resources/config.yml`
- `cc-demon/src/main/resources/plugin.yml`

**新增文件**

- `cc-demon/src/main/java/com/chilicraft/demon/DemonStatusGui.java`

**实施步骤**

1. 将 `DemonCommand` 改为可供路由回调使用的处理器：`onCommand` 委托 `execute`，`onTabComplete` 委托 `complete`。无参/`menu` 对普通用户打开 `DemonStatusGui`；只有管理权限才可从显式 `admin`/管理入口进入现有 `DemonGui#openMain`。
2. `status` 可由基础用户使用；`clear`、`spawn`、`reload` 逐项检查 `chilicraft.demon.admin`，不得只依赖 plugin.yml。未知参数返回用法和候选，不再静默打开管理 GUI。补全向普通玩家只暴露 `menu/status/help`，管理员额外看到 `admin/clear/spawn/reload` 及类型。
3. `DemonStatusGui` 复用 `DemonManager#total/count` 与设置缓存，只读展示总量、类型数量、刷怪开关和昼夜，不绑定生成/清空动作。
4. `plugin.yml` 新增 `chilicraft.demon.use`（default true），保留 `chilicraft.demon.admin`；将旧 `/demon` 入口改为基础使用权限，并依靠代码级管理检查保护危险子命令。管理员节点配置 `children` 包含 use，兼容只授予旧 admin 的非 OP 用户。
5. 主类构造一个 `DemonCommand`，同时绑定旧 `/demon` 和注册 `ModuleCommandRoute("demon", ...)`，避免两个实例状态漂移。菜单普通回调打开状态页；管理回调/帮助说明现有管理能力。
6. 注册发生在 `settings/manager/statusGui/command` 就绪后；失败回滚并禁用。`onDisable` 在退订后注销路由和菜单，随后执行现有 `manager.clearAll()`。
7. 配置补充普通状态页、统一帮助、无效参数和权限提示文案；现有管理 GUI 文案不重写。

**阶段验证**

```powershell
./gradlew.bat :cc-demon:compileJava :cc-demon:processResources
```

静态核对普通权限路径无法触达 `manager.spawn`、`manager.clearAll` 或模块 reload。

### 任务 7：接入 `cc-soul` 并保留余额兼容语义

**修改文件**

- `cc-soul/src/main/java/com/chilicraft/soul/ChiliSoulPlugin.java`
- `cc-soul/src/main/java/com/chilicraft/soul/SoulCommand.java`
- `cc-soul/src/main/java/com/chilicraft/soul/SoulSettings.java`
- `cc-soul/src/main/resources/config.yml`
- `cc-soul/src/main/resources/plugin.yml`

**实施步骤**

1. `SoulCommand` 增加共享 `execute/complete`；旧 `/soul` 无参和统一 `/cc soul menu` 都调用现有 `SoulGui#openMain`。核心保证 `/cc soul` 单参数仍走余额，不传给该处理器。
2. `relic give` 与 `reload` 都执行管理权限检查（规范 `chilicraft.soul.admin`；旧节点相同）；普通补全不得暴露 `relic give` 或 `reload`。未知参数返回模块用法，不再默认开菜单。
3. `plugin.yml` 新增 `chilicraft.soul.use`（default true），保留 `chilicraft.soul.user` 和 `chilicraft.soul.admin`；路由基础权限登记 use+user，保持既有 LuckPerms 配置有效。
4. 主类复用同一个 `SoulCommand` 绑定旧命令和路由；菜单入口分类 `PROGRESSION`，打开现有 `SoulGui#openMain`，右键帮助走命令处理器的帮助发送方法。
5. 注册放在 GUI/命令装配完成且异步载库已启动后、周期任务及订阅之前；失败时注销已注册项并禁用。禁用时先停任务/退订，再注销菜单和路由，再执行现有 flush/clear。
6. `core.reload` 不注册入口；新增帮助/无效参数文案进入 config 和 `SoulSettings` 现有消息缓存。

**阶段验证**

```powershell
./gradlew.bat :cc-soul:compileJava :cc-soul:processResources
```

额外静态核对核心 `CcCommand` 的 `soul` 分支顺序和模块切片：`["soul"]` 查询余额，`["soul","menu"]` 向模块传 `["menu"]`。

### 任务 8：接入 `cc-martial`

**修改文件**

- `cc-martial/src/main/java/com/chilicraft/martial/ChiliMartialPlugin.java`
- `cc-martial/src/main/java/com/chilicraft/martial/MartialCommand.java`
- `cc-martial/src/main/resources/config.yml`
- `cc-martial/src/main/resources/plugin.yml`

**实施步骤**

1. `MartialCommand` 增加共享 `execute/complete`，保留无参/`menu` 调用 `MartialGui#openMain`，保留 `school/skills/arena` 业务方法；未知参数改为发送用法和候选。
2. 管理 `admin` 和 `reload` 同时接受规范 `chilicraft.martial.admin`（当前旧节点同名），普通补全继续隐藏管理项；统一路由只做 use/user 基础校验。
3. `plugin.yml` 新增 `chilicraft.martial.use`（default true），保留 `chilicraft.martial.user/admin`；路由基础权限登记 use+user。
4. 主类复用同一个处理器绑定旧命令和路由；菜单入口分类 `PROGRESSION`，打开现有 `MartialGui#openMain`。
5. 改正当前 `registerModuleConfig` 静默忽略重复的处理：本次新增菜单/路由重复必须记录可读错误、回滚并禁用；不要把 `core.reload` 当成重复注册理由。原配置注册行为若保持兼容，可单独捕获，但菜单/路由不得吞异常。
6. 禁用顺序保持现有 PAPI 注销、事件退订和服务 shutdown/flush；在释放 GUI/服务字段前注销菜单和路由。重载仅刷新 settings、skills、affixes。
7. 增加统一帮助/usage 配置键，沿用该模块浅层 kebab-case 消息加载规则，不创建 `messages.gui.*` 深层结构。

**阶段验证**

```powershell
./gradlew.bat :cc-martial:compileJava :cc-martial:processResources
```

确认 `%chilimartial_*%` 安装/移除路径、竞技场任务与冒险事件订阅未改变。

### 任务 9：接入 `cc-adventure`

**修改文件**

- `cc-adventure/src/main/java/com/chilicraft/adventure/ChiliAdventurePlugin.java`
- `cc-adventure/src/main/java/com/chilicraft/adventure/AdventureCommand.java`
- `cc-adventure/src/main/java/com/chilicraft/adventure/AdventureSettings.java`
- `cc-adventure/src/main/resources/config.yml`
- `cc-adventure/src/main/resources/plugin.yml`

**实施步骤**

1. `AdventureCommand` 增加共享 `execute/complete`；无参和显式 `menu` 打开现有 `AdventureGui#openMain`，其余 `party/dungeon/expedition/boss/status` 继续调用原服务。无效参数显示用法和候选。
2. 权限检查兼容 `chilicraft.adventure.use` 或旧 `chilicraft.adventure.user`；补全先检查基础权限。当前无管理子命令，不虚构 admin 操作，但声明规范 `chilicraft.adventure.admin`（default op）供后续和管理中心识别。
3. `plugin.yml` 增加 use/admin，保留 user；旧 `/adventure` 的命令级权限保持 user，代码层 OR 检查用于统一入口。若要让仅获新 use 的用户也使用旧入口，则将命令 permission 改 use，并以 admin children/use 与默认值保证兼容，二选一后以“新旧任一满足”验收为准。
4. 主类复用一个处理器绑定旧命令和路由；菜单分类 `SURVIVAL_ADVENTURE`，打开 `AdventureGui#openMain`。注册在 parties/dungeons/expeditions/bosses/gui 完成后。
5. 重复注册必须记录并回滚后禁用，不再空 catch。禁用时先停 `AdventureTask`、退订事件，再注销路由/菜单，再执行 `bosses.shutdown()`。
6. 新增消息通过 `AdventureSettings` 已有深键加载规则放在 `messages.command.*`/`messages.menu.*`，不要改成浅键读取；现有硬编码命令反馈可只迁移本次新增帮助、权限、用法文本，避免范围外文案重构。

**阶段验证**

```powershell
./gradlew.bat :cc-adventure:compileJava :cc-adventure:processResources
```

确认 `dungeons.yml`、`bosses.yml` 加载和 MythicMobs 降级路径未改。

### 任务 10：接入 `cc-season`，新增安全季节页面

**修改文件**

- `cc-season/src/main/java/com/chilicraft/season/ChiliSeasonPlugin.java`
- `cc-season/src/main/java/com/chilicraft/season/SeasonCommand.java`
- `cc-season/src/main/java/com/chilicraft/season/SeasonSettings.java`
- `cc-season/src/main/resources/config.yml`
- `cc-season/src/main/resources/plugin.yml`

**新增文件**

- `cc-season/src/main/java/com/chilicraft/season/SeasonGui.java`

**实施步骤**

1. `SeasonGui` 使用 `GuiHolder` 展示 `SeasonService#state()` 的年份/季节/日期，并提供 HUD、指南、时钟三个安全动作；普通页面不放置 `set season/day`。
2. `SeasonCommand` 增加共享 `execute/complete`：统一入口无参/`menu` 打开 `SeasonGui`；保留 `info/hud/guide/clock/set`。控制台可执行 `info`，其他玩家操作明确反馈。
3. `set` 权限改为 `chilicraft.season.admin` 或旧 `ccseason.admin` 任一满足；补全仅向满足管理权限者显示 `set`、`season/day` 和枚举值，并按输入前缀过滤（修复当前无前缀过滤但不改业务）。
4. `plugin.yml` 保留 `ccseason` 与 alias `season`，新增 `chilicraft.season.use`（default true）、`chilicraft.season.admin`（default op），保留 `ccseason.admin`。路由 ID `season`，别名可登记 `ccseason` 供 `/cc ccseason` 兼容提示/分发，但文档只推荐 `/cc season`。
5. 主类复用一个处理器绑定旧命令和路由；菜单分类 `WORLD_ECOLOGY`，打开 `SeasonGui`。注册在 service/guide/clock/gui 可用后、任务和订阅前；失败回滚并禁用。
6. 禁用时先取消 `mainTask/migrationTask`、退订 `core.reload`，再注销菜单/路由，再关闭 ProtocolLib/PAPI 适配器。`reloadHandler` 继续只刷新 settings、迁徙任务和视觉适配器，不重复注册。
7. 新增状态页、帮助和错误文本进入 config，由 `SeasonSettings` 缓存；不改变 crops、迁徙、BossBar 和 ProtocolLib 视觉逻辑。

**阶段验证**

```powershell
./gradlew.bat :cc-season:compileJava :cc-season:processResources
```

确认普通菜单/补全没有 `set`，且 ProtocolLib/PAPI close 顺序仍完整。

### 任务 11：统一权限、注册失败和生命周期检查

**修改文件**

- 任务 5–10 涉及的六个主类、六个 `plugin.yml` 和命令类（仅修正迁移检查发现的问题）

**实施步骤**

1. 为每个模块建立明确常量：`USE_PERMISSIONS`、`ADMIN_PERMISSIONS`；菜单可见性、核心路由、模块内部管理检查和补全使用同一组节点，避免字符串漂移。
2. 每个主类使用成对的私有方法 `registerNavigation()`/`unregisterNavigation()` 或等价直写流程。注册菜单成功但路由失败时立即注销菜单；随后记录 moduleId 和错误并 `disablePlugin(this)`。
3. 每个 `onDisable` 在依赖字段清空前注销；即使 onEnable 半途失败也可重复安全执行。禁止在 `core.reload` handler 中注册。
4. 核对六个入口的分类和排序：生存、恶魔、冒险；灵魂、武学；季节。排序值固定且同类唯一。
5. 核对管理中心判定只看 `chilicraft.admin` 或注册路由声明的任一管理权限；不因玩家能使用某模块就显示管理中心。
6. 核对回调不捕获单个 Player，只有服务/GUI门面；所有 Inventory/Bukkit 玩家操作都从主线程命令或 GUI 点击路径触发。

**阶段验证**

```powershell
./gradlew.bat :cc-core:compileJava :cc-survival:compileJava :cc-demon:compileJava :cc-soul:compileJava :cc-martial:compileJava :cc-adventure:compileJava :cc-season:compileJava
```

使用代码搜索核对每个 `registerMenuEntry/registerCommandRoute` 均有对应 `unregisterMenuEntry/unregisterCommandRoute`，且注册调用不在 reload handler 中。

### 任务 12：同步文档（无新事件）

**修改文件**

- `README.md`
- `docs/architecture.md`
- `docs/module-dev-guide.md`

**实施步骤**

1. README：更新模块表中的统一入口；增加 `/menu`、`/cc <module>`、旧命令保留和五个规划入口灰显说明。巡演联动机制本身未改，不重写该节。
2. architecture：在公开 API 表加入菜单入口/命令路由契约；更新核心实现组件为 `ModuleRegistry`、分类 GUI 和统一 `CcCommand`；命令章节列出 `/menu`、核心子命令、模块路由及权限兼容规则。
3. module-dev-guide：在 onEnable 标准流程中加入“业务装配后注册菜单/路由”，在 onDisable 加入成对注销；给出不持有 Player、重载不重复注册、旧命令复用同一处理器、管理补全过滤的范式和自检项。
4. 各模块 `plugin.yml` 已在迁移任务中同步命令、usage 与权限说明；各模块 `config.yml` 已同步新增玩家文本。
5. 明确不修改 `docs/events-protocol.md`：本设计没有新增或变更字符串事件。也不修改 `docs/performance.md`：没有新增周期任务，新增 GUI/命令均为玩家触发路径。

**阶段验证**

逐项比对 README/architecture/module-dev-guide 中的类名、命令和权限与实际实现，删除“`/cc menu` 尚未实现”等过期描述。

### 任务 13：全量构建和静态验收

**不新增测试文件，不启动服务端。**

1. 先运行常规构建：

```powershell
./gradlew.bat build
```

2. 仅当失败明确属于 `repo.papermc.io` 元数据、TLS 或网络问题时运行：

```powershell
./gradlew.bat build --offline
```

3. 获取 VS Code/Java diagnostics，要求本次涉及文件无新增错误；确认没有新增依赖，附属仍为 `compileOnly(project(":cc-core"))`。
4. 检查 `plugin.yml`：核心声明 `cc`/`menu`；六模块旧命令声明和 alias 未丢失；权限名、usage 与实现一致。
5. 以静态调用链核对命令矩阵：
   - `/menu`、`/cc`、`/cc menu` → 同一主菜单；`/chilicraft` 仍可用。
   - `/cc balance` 与 `/cc soul` → 核心余额；`/cc soul menu` → `SoulGui#openMain`。
   - `/cc survival|demon|martial|adventure|season` → 对应菜单/状态页。
   - `/demon`、`/soul`、`/martial`、`/adventure`、`/season`、`/ccseason` → 与统一入口共享处理器。
   - 规划模块 → 开发中；真正未知模块 → 用法和候选。
6. 以静态调用链核对权限/补全：无基础权限时模块隐藏且不能路由；普通玩家看不到管理补全；危险方法前有模块内部 admin 检查；管理中心判定符合设计。
7. 核对 GUI：固定槽位、分类页 54 格、左/右键分支、返回/帮助/关闭、灰显与不可用分支；点击模块前重新查询注册表；仍由 `GuiListener` 阻止点击和拖拽取物。
8. 核对生命周期：六模块各注册一次、注销一次；`core.reload` 不注册；核心 disable 清空注册表；没有回调持有 Player；没有新增任务或异步 Inventory 操作。
9. 核对回归边界：核心模式/家园/档案/reload 代码路径保留；事件订阅名和持久化未改；cc-season ProtocolLib/PAPI、cc-martial PAPI、cc-adventure MythicMobs 路径未改；附属间零编译期依赖。

## 4. 迁移顺序与中间态控制

1. **API 与核心先落地**：任务 1–4 完成并保证 `:cc-core:compileJava` 通过后，才改附属。此时注册表为空也能显示核心固定入口和规划灰显入口。
2. **逐模块接入**：严格按 survival → demon → soul → martial → adventure → season。每完成一个模块就单独 compile/processResources，避免六模块错误叠加。
3. **旧入口最后才允许调整 plugin.yml 权限**：先让共享处理器和模块内部管理校验生效，再放宽/切换旧命令的命令级 use 权限，避免短暂暴露危险子命令。
4. **文档在代码稳定后同步**：只描述最终已实现行为；不把规划模块写成已实现。
5. **全量 build 收口**：仅构建和静态验收，不把启动 Paper 服务端作为完成条件。

## 5. 风险与回滚控制

### 5.1 公开 API 兼容风险

- 风险：附属针对扩展后的 `ChiliCraftAPI` 编译，旧核心运行时不具备新方法。
- 控制：核心和六模块作为同一发布批次构建、部署；不删除或改签任何已有 API 方法；发布说明中明确核心与附属必须使用同一批次版本。
- 回滚：按同一批次回退核心和六模块 jar，禁止只回退核心。

### 5.2 命令冲突与 `/cc soul` 歧义

- 风险：固定核心子命令与模块 ID 冲突，尤其 `soul`。
- 控制：核心固定子命令优先；单参数 soul 固定余额，两个及以上参数才路由；注册表拒绝模块 ID/alias 撞核心保留词（`menu/help/info/home/sethome/balance/mode/reload`，`soul` 作为受控特例）。
- 回滚：可先禁用对应模块的统一路由而保留旧命令；核心菜单仍可运行。

### 5.3 权限放宽导致管理动作暴露

- 风险：原 `/demon` 由 plugin.yml 整体 admin 保护，改为 use 后危险子命令可能外露。
- 控制：必须先给 clear/spawn/reload/admin 路径加代码级管理检查和补全过滤，再改 plugin.yml；静态逐个检查危险服务调用前的权限分支。
- 回滚：恢复旧命令的 admin permission 不影响 `/cc demon` 的只读状态入口。

### 5.4 回调悬挂与重复注册

- 风险：插件禁用后注册表仍持有 GUI/服务回调；reload 重复注册导致异常。
- 控制：每个注册都有 onDisable 注销；核心禁用清空；reload 不注册；点击时按 moduleId 重查而不执行旧快照回调。
- 回滚：单模块注册失败立即回滚其另一项注册并禁用该附属，核心和其他模块继续工作。

### 5.5 菜单配置与 Material 降级

- 风险：配置文案缺失/解析失败或图标无效导致页面打不开。
- 控制：文本沿用 `Messages`/各模块 Settings 默认值与解析降级；公开入口使用已解析 `Material`，null/异常回退 PAPER；单模块回调异常不关闭核心。
- 回滚：恢复默认 config 文案即可，无数据迁移。

### 5.6 构建环境风险

- 风险：Paper SNAPSHOT 元数据或 TLS 失败被误判为代码失败。
- 控制：先常规 build；只对明确网络错误使用 `--offline`；Java 保持 21，源码/资源 UTF-8，不更改工具链。

## 6. 完成定义

- [ ] 公开 API 和 `ModuleRegistry` 可注册、拒绝冲突、查询不可变快照、注销并清空。
- [ ] `/menu`、`/cc`、`/cc menu`、`/chilicraft` 行为符合设计。
- [ ] 27 格主菜单、54 格分类页、固定槽位、规划灰显、管理中心权限均落实。
- [ ] 六模块按指定顺序接入，旧入口和统一入口共享同一业务处理器。
- [ ] `/cc soul` 余额兼容与 `/cc soul menu` 模块入口同时成立。
- [ ] 新旧权限任一满足，管理动作仍二次校验，Tab 不泄露管理候选。
- [ ] reload 不重复注册，所有 onDisable 成对注销，核心禁用清空回调。
- [ ] 未新增事件、依赖、周期任务、数据表或规划模块业务。
- [ ] README、architecture、module-dev-guide、plugin.yml、相关 config 文案同步。
- [ ] `./gradlew.bat build`（或明确网络受限时 `--offline`）显示 `BUILD SUCCESSFUL`，Java diagnostics 无新增错误。
