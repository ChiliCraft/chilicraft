# 模块开发指南

从零新增一个 ChiliCraft 附属模块的完整步骤。开始前先读 [architecture.md](architecture.md)（架构总览）与 [events-protocol.md](events-protocol.md)（事件与数据协议）。

设计原则：**核心 cc-core 零改动**——新玩法全部以附属形式挂接，跨模块协作只走事件总线。

## Step 0：规划

先定三样东西，写进模块主类 javadoc：

1. **模块 ID**：配置使用 `cc-<name>`，统一菜单/命令注册使用不带前缀的规范 ID `<name>`；工程名、plugin.yml `name`、`registerModuleConfig` 参数仍保持一致；
2. **统一入口**：确定 `MenuCategory`、显示名、Material、排序值、`chilicraft.<name>.use`/`admin` 权限，以及 `ModuleCommandRoute` 的别名和旧命令兼容关系；
3. **事件名清单**：遵循「模块名.事件名」（如 `soul.relic_gained`），见 events-protocol.md；
4. **配置键草案**：数值段 + `messages` 段，全部可热载。

## Step 1：挂入多工程构建

[settings.gradle.kts](settings.gradle.kts) 追加一行：

```kotlin
include("cc-street")
```

新建 `cc-<name>/build.gradle.kts`（以 [cc-demon/build.gradle.kts](cc-demon/build.gradle.kts) 为模板，全部依赖 `compileOnly`，普通 jar 即最终构件）：

```kotlin
// cc-<name>：<一句话职责>
plugins {
    id("java-library")
}

dependencies {
    // 核心 API：运行时由服务端上已安装的 cc-core 提供，不打进 jar
    compileOnly(project(":cc-core"))
    // 服务端 API：运行时由服务端提供，不打进 jar
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    // 日志门面：服务端自带，不打进 jar
    compileOnly("org.slf4j:slf4j-api:2.0.13")
}

// 把版本号注入 plugin.yml（plugin.yml 中用 ${version} 占位）
tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}
```

工具链、编码、release=21 由根构建脚本统一施加，附属不要重复声明。

## Step 2：plugin.yml

`cc-<name>/src/main/resources/plugin.yml`：

```yaml
# ChiliCraft <中文名>附属（cc-<name>）
name: cc-<name>
version: "${version}"
main: com.chilicraft.<name>.Chili<Name>Plugin
api-version: "1.21"
authors: [ChiliCraft]
description: <一句话中文描述>

# 硬依赖核心：核心未启用时本附属自动禁用
depend: [cc-core]
# 兼容基线：兼容的核心 API 版本（核心装载时校验，未达标给出可读告警）
compatible-core: ">=1.0"
softdepend: []

# 旧顶层命令仅在需要兼容时声明；统一入口由 cc-core 的 /cc 路由提供
commands:
  <name>:
    description: <中文名>模块命令
    usage: /<name> <sub...>
    permission: chilicraft.<name>.use

permissions:
  chilicraft.<name>.use:
    description: 允许使用 <name> 模块
    default: true
  chilicraft.<name>.admin:
    description: 允许使用 <name> 模块管理操作
    default: op
    children:
      chilicraft.<name>.use: true
  # 迁移中的旧 user 节点可保留，并与 use 任一满足；危险子命令仍须代码二次校验 admin。
```

不要声明对其他附属的 depend/softdepend——跨模块协作只走事件总线（字符串事件名，无编译期依赖，详见 events-protocol.md）。

## Step 3：五件套骨架

包名 `com.chilicraft.<name>`，固定五个角色（完整活例见 cc-demon / cc-survival）：

### 3.1 主类 `Chili<Name>Plugin`

```java
public final class ChiliStreetPlugin extends JavaPlugin {

    private ChiliCraftAPI api;
    private StreetSettings settings;
    private BukkitTask task;
    private EventHandler reloadHandler;

    @Override
    public void onEnable() {
        Logger log = getSLF4JLogger();
        saveDefaultConfig();                       // 缺失时 getConfig 返回空配置

        // 1. ServicesManager 取核心 API，取不到则自禁用
        RegisteredServiceProvider<ChiliCraftAPI> reg =
                getServer().getServicesManager().getRegistration(ChiliCraftAPI.class);
        if (reg == null) {
            log.error("未找到 cc-core 提供的 ChiliCraftAPI 服务，cc-street 已自行禁用");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        api = reg.getProvider();

        // 2. 注册模块配置（包装活配置，防 reloadConfig 换实例后读到旧引用）
        settings = new StreetSettings(this);
        settings.refresh();
        try {
            api.registerModuleConfig("cc-street", new LiveModuleConfig(this));
        } catch (IllegalStateException e) {        // moduleId 重复注册
            log.error("模块配置注册失败：{}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. 装配服务 / 监听器，启动周期任务（延迟起步避开启动尖峰）
        // task = getServer().getScheduler().runTaskTimer(this, new StreetTask(...), 20L, 20L);

        // 4. 业务对象就绪后只注册一次统一菜单入口和命令路由
        api.registerMenuEntry(menuEntry);
        api.registerCommandRoute(commandRoute);

        // 5. 订阅：core.reload 热载 + 其他模块业务事件
        reloadHandler = (eventName, data) -> { reloadConfig(); settings.refresh(); };
        api.subscribe("core.reload", reloadHandler);
        // api.subscribe("street.world_enter", streetHandler);   // 跨模块订阅范式
    }

    @Override
    public void onDisable() {
        if (task != null) { task.cancel(); task = null; }
        // 事件总线持订阅者强引用，禁用必须退订
        if (api != null && reloadHandler != null) {
            api.unsubscribe("core.reload", reloadHandler);
        }
        if (api != null) {
            api.unregisterCommandRoute("street");
            api.unregisterMenuEntry("street");
        }
        api = null; settings = null; reloadHandler = null;
    }
}
```

`LiveModuleConfig` 是每模块自带的 30 行包装类（逐 getter 穿透 `plugin.getConfig()`），从任一附属复制后改 `moduleId()` 返回值即可——**不要**把 `getConfig()` 瞬时引用直接交给核心，`reloadConfig()` 会替换内部实例导致旧引用读到过期数据。

### 3.2 Settings 数值缓存

统一入口注册补充规范：附属通过公开 `ChiliCraftAPI` 注册 `ModuleMenuEntry` 与 `ModuleCommandRoute`，不要依赖 `com.chilicraft.core.*`。菜单入口的模块 ID、分类、排序、Material、`usePermissions`、可选 `adminPermissions`、可用性和打开/帮助回调必须与路由保持一致；命令执行和 Tab 补全分别使用 `ModuleCommandExecutor`、`ModuleTabCompleter`。回调只捕获服务或 GUI 门面，不捕获单个 `Player`；注册表不保存玩家对象。

旧顶层命令若需兼容，应让 Bukkit 的 `onCommand`/`onTabComplete` 与统一路由共同调用同一个 `execute`/`complete` 分发方法，不复制业务逻辑。核心 `/cc` 先检查模块基础使用权限，模块处理器仍须对管理子命令二次检查 `chilicraft.<name>.admin`（兼容旧管理节点），并在 `complete` 中隐藏无权限管理候选。规划模块不注册虚假路由，由核心菜单和 Tab 显示开发中。

注册失败必须回滚另一项注册并禁用附属。`core.reload` 处理器只能刷新配置、Settings、文案和内容缓存，不得再次调用注册 API；所有菜单/Inventory 与玩家操作只在主线程执行。

周期任务禁止每秒穿透配置树，全部数值进缓存类：

- 字段包内可见（`boolean` / `double` / `int` / `List` / `Map`）；
- `refresh()` 从 `plugin.getConfig()` 重建全部字段，onEnable 与 core.reload 时各调一次；
- 非法值回退默认并 `logger.warning`（复用 positive / nonNegative / clamp 辅助）；
- 消息模板整体读入 `Map<String, String>`，`message(key)` 缺失返回空串，展示层判空静默跳过。

### 3.3 Service / Listener / Task

- **Service**：纯业务逻辑，状态经构造注入，无静态可变字段；
- **Listener**：只挂低频 Bukkit 事件（交互、死亡、实体死亡）。移动、背包变化等高频事件一律禁止；
- **Task**：周期任务单任务单句柄（一个 `runTaskTimer` 覆盖全部循环业务），`run()` 内逐玩家 `try-catch`，一人异常不中断整轮。周期取值参考：cc-demon 2 秒（40L, 40L）、cc-survival 1 秒（20L, 20L）。

### 3.4 消息发送（MiniMessage）

- 模板存 config `messages` 段，玩家文本一律 MiniMessage；
- 有占位符用 `Placeholder.unparsed("key", value)` resolver；模板缺失返回空组件（静默跳过），解析异常回退纯文本（范式见 cc-survival `PressureTask#msg`）。

## Step 4：config.yml

`src/main/resources/config.yml`：头部注释块说明约定 → 数值段（每键中文注释）→ `messages` 段。参考任一现有模块。数值键缺失或非法必须回退内置默认值（代码层兜底），配置文件被管理员删键不应导致功能失效。

## 进阶范式（按需采用，cc-adventure 活例）

以下范式由 cc-adventure 沉淀，非五件套必需，内容体量或外部集成达到相当规模时采用：

1. **内容文件与 config 分离**：条目型内容（地城 ×10、Boss ×5）拆为独立 YAML（`dungeons.yml` / `bosses.yml`），onEnable 用「缺失才释放资源」的方式落盘，由独立解析类（`DungeonContent` / `BossContent`）读成定义对象；config.yml 只留数值与 `messages` 段。内容文件同样经 `core.reload` 热载（重读文件 + 重建定义缓存）。
2. **消息键含点号的加载**：消息键形如 `expedition.start` 时，Bukkit 配置以 `.` 为路径分隔符，`getKeys(false)` + `getString(key)` 永远取不到值（静默全空）。范式：`getKeys(true)` 遍历深键、跳过中间 `ConfigurationSection`，YAML 按嵌套结构书写，还原出的深路径恰与代码键一致（见 [AdventureSettings.java](cc-adventure/src/main/java/com/chilicraft/adventure/AdventureSettings.java)）。键名无点号（如 cc-martial 的连字符键）不受此坑影响。
3. **可选第三方插件集成**：plugin.yml 声明 `softdepend`（如 MythicMobs），构建侧 `compileOnly(files("libs/<jar>"))`，运行侧独立适配器封装全部外部调用，每次调用 `catch (Throwable)` 降级到原版实现路径——外部插件缺失、版本不符、内部异常均不影响本模块核心玩法（见 [MythicAdapter.java](cc-adventure/src/main/java/com/chilicraft/adventure/MythicAdapter.java)）。
4. **附属箱子 GUI**：复用 cc-core 的 `GuiHolder`/`GuiListener`（cc-core 已全局注册，`instanceof GuiHolder` 识别并统一取消点击），附属直接 `new GuiHolder(...)`，**零监听器、零 plugin.yml 变更**。范式（见 cc-adventure `AdventureGui` 等 6 类、cc-martial `MartialGui` 等 5 类、cc-soul `SoulGui` 等 3 类、cc-demon `DemonGui` 等 3 类）：快照式构建——每次打开全新构建面板，点击动作成功后重建刷新；面板类与服务同包（服务多为包私有）；文案走 `messageOr(key, def)` 兜底——按钮名/lore 不可空白，config `messages` 的 gui 键段为权威文案源，代码 def 仅作保险丝且与 config 默认值保持一致；**gui 文案键命名跟随模块 Settings 的消息加载深度**——深键模块按嵌套小节书写（cc-adventure `messages.gui.*`），浅键加载（`getKeys(false)`）模块必须平铺 kebab-case（cc-martial `gui-title-main` 风格，嵌套小节浅加载读不到）；数据文件（dungeons.yml 等）串的 displayName 按纯文本展示（`Component.text`，与服务层广播 `Placeholder.unparsed` 同口径），模板占位符先 `Texts.parse` 再 `replaceText(matchLiteral)` 替换（占位符命名避开 MiniMessage 标准标签）；玩家头仅对在线玩家 `setOwningPlayer`（离线拉档案会卡主线程）。

## Step 5：验证

```powershell
.\gradlew.bat build --offline   # 网络受限时的兜底；常规用 .\gradlew.bat build
```

## 自检清单

- [ ] settings.gradle.kts 已 include；`gradlew build` 通过
- [ ] plugin.yml：`depend: [cc-core]`、`compatible-core: ">=1.0"`，无对其他附属的依赖声明
- [ ] onEnable：API 取不到时自禁用；`registerModuleConfig` 包 IllegalStateException
- [ ] 全部 subscribe 在 onDisable 有对应 unsubscribe；周期任务 cancel
- [ ] 菜单入口和命令路由在 onEnable 各注册一次，注册失败回滚；onDisable 成对调用 `unregisterMenuEntry` / `unregisterCommandRoute`
- [ ] `core.reload` 只刷新配置/缓存，不重复注册；注册回调不持有 `Player`
- [ ] 旧顶层命令与统一路由复用同一 `execute`/`complete`；Tab 按权限过滤管理候选
- [ ] 周期任务单句柄、逐玩家 try-catch；无 PlayerMoveEvent / 高频事件监听
- [ ] Settings 全字段经 refresh() 重建；非法值回退默认
- [ ] 消息模板缺失静默、解析失败回退纯文本
- [ ] 注释与配置全中文；源码 UTF-8
