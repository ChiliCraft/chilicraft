# cc-quest 核心闭环设计规格

- 日期：2026-09-28
- 状态：已获用户确认，待实施计划
- 目标：实现 ChiliCraft 任务与章节系统的首个可运行闭环
- 环境：Paper 1.21.1、Java 21、Gradle Kotlin DSL

## 1. 范围

首版实现：

- 章节与任务配置加载；
- 任务和章节显式状态机；
- 事件驱动任务进度；
- `cc_quest_progress` 异步持久化；
- 旅途手册 GUI；
- `/cc quest`、`/cc quest guide`、`/cc quest status`、`/cc quest claim <任务ID>`；
- `/cc quest admin reload` 与 `/cc quest admin reset <玩家>`；
- 统一菜单中的任务入口；
- 任务完成、章节完成和奖励领取反馈；
- 订阅现有模块事件并推进任务。

明确不实现：完整六章剧情文本、复杂 NPC 对话树、任务分支并行、任务回滚、跨服务器同步、新外部依赖。

## 2. 模块结构

新建 `cc-quest` Gradle 子项目，仅 `compileOnly(project(":cc-core"))` 依赖核心。

建议组件：

- `ChiliQuestPlugin`：装配、生命周期、注册和注销；
- `QuestSettings`：不可变配置快照；
- `QuestService`：主线程状态机、事件匹配和奖励流程；
- `QuestRepository`：通过 `RowStore` 异步读写；
- `QuestListener`：监听 Bukkit 事件及核心事件总线入口；
- `QuestCommand`：统一命令执行和 Tab 补全；
- `QuestGui`：旅途手册和任务详情页面；
- `QuestDefinition`、`ChapterDefinition`：不可变内容模型。

附属模块不得引用 `com.chilicraft.core.*`，不得直连 SQLite，不得编译期依赖其他附属模块。

## 3. 状态模型

任务状态：

```text
LOCKED -> AVAILABLE -> ACTIVE -> COMPLETED -> CLAIMED
```

章节状态：

```text
LOCKED -> ACTIVE -> COMPLETED
```

章节激活后，其首个可用任务进入 `AVAILABLE`；玩家首次查看或收到对应目标事件时可进入 `ACTIVE`。任务目标满足后进入 `COMPLETED`，奖励成功发放后进入 `CLAIMED`。已领取状态是奖励幂等依据。

同一玩家的进度长期以 UUID 为键，不使用 `Player` 作为长期 Map 键。任务状态转换在主线程完成，并在状态转换后异步持久化。

## 4. 配置模型

内容放在 `cc-quest/src/main/resources/chapters.yml`，运行配置放在 `config.yml`。内容快照整体替换，不原地修改。

示例：

```yaml
chapters:
  chapter-1:
    title: 初入方街
    order: 1
    tasks:
      - id: first-demon
        title: 第一次面对饿魔
        description: 击败一只饿魔
        target:
          event: demon.mob_killed
          amount: 1
        reward:
          souls: 10
```

任务目标统一包含：`event`、`amount`，可选 `target` 和 `value`。配置中任务 ID 在章节内唯一，章节 ID 全局唯一；重复 ID、缺失事件名、非正目标数量和无效奖励配置回退为上一份有效快照并记录告警，不以半配置状态运行。

## 5. 事件驱动进度

首版订阅以下已登记事件：

- `demon.mob_killed`
- `soul.relic_gained`
- `martial.arena_win`
- `martial.realm_up`
- `adventure.boss_killed`
- `adventure.dungeon_clear`
- `adventure.expedition_result`
- `season.special_day`

启动时按事件名建立目标索引：

```text
事件名 -> 任务目标列表
```

收到事件后的流程：

1. 检查 `playerId` 是否存在；
2. 按事件名查询目标索引；
3. 检查玩家对应章节和任务状态；
4. 校验可选 `target` / `value`；
5. 在主线程推进内存状态；
6. 异步写入 `cc_quest_progress`；
7. 主线程发送反馈或刷新当前 GUI。

事件回调只进行轻量索引和状态判断，不执行阻塞数据库操作。由于事件总线是同步即时分发，不补发模块禁用期间发生的历史事件。

## 6. 持久化与幂等

通过现有 `RowStore` 访问 `cc_quest_progress`，不新增数据库直连。

至少保存：

- `player_id`；
- `chapter_id`；
- `quest_id`；
- `state`；
- `progress`；
- `updated_at`。

玩家首次进入或首次打开任务页面时异步读取进度；数据库回调必须切回服务器主线程后更新 GUI 或玩家消息。写入失败记录错误并保留内存状态，下一次状态变更继续尝试保存。

奖励发放前后必须在主线程检查状态。只有 `COMPLETED` 能领取，成功领取后原子地转为 `CLAIMED`；重复点击或重复事件不能重复发放奖励。首版奖励仅支持配置中的灵魂货币，使用核心公开 API 增加灵魂。

## 7. 命令与权限

统一命令：

```text
/cc quest
/cc quest guide
/cc quest status
/cc quest claim <任务ID>
/cc quest admin reload
/cc quest admin reset <玩家>
```

权限：

```text
chilicraft.quest.use
chilicraft.quest.admin
```

`/cc quest` 默认打开旅途手册；`guide` 与默认入口行为一致；`status` 显示当前章节和任务摘要；`claim` 只允许当前玩家领取自己的任务；`admin` 子命令执行代码级管理员权限检查。GUI、状态、领取和玩家任务重置属于玩家操作，控制台执行时明确提示只能由玩家执行。Tab 补全按权限和前缀过滤，不向普通玩家暴露 `admin`。

## 8. GUI

复用核心现有 GUI 生命周期和点击监听机制，新增旅途手册及任务详情页面。

旅途手册显示：当前章节、章节进度、任务列表、任务进度、完成/已领取状态、返回主菜单和关闭按钮。左键打开任务详情，右键已完成任务触发领取；锁定任务显示解锁条件，已领取任务显示完成状态。

点击处理前重新按任务 ID读取当前服务状态，不持有过期配置对象或 `Player`。所有菜单物品禁止通过 Shift 点击、数字键交换、双击、拖拽等方式取出。模块禁用时清理本模块 GUI 会话。

## 9. 生命周期与重载

启用顺序：

1. 获取 `ChiliCraftAPI`，失败则记录错误并自禁用；
2. 注册模块配置；
3. 加载并校验 `config.yml` 与 `chapters.yml`；
4. 创建设置、仓库、服务；
5. 订阅核心任务事件；
6. 注册菜单入口和命令路由；
7. 订阅 `core.reload`。

禁用顺序：

1. 取消模块任务；
2. 退订每个核心事件；
3. 退订 `core.reload`；
4. 注销命令路由；
5. 注销菜单入口；
6. 清理 UUID 进度缓存和 GUI 会话。

重载只原子替换有效配置和事件索引，不重复注册命令或菜单，不重置玩家进度。重载配置无效时保留上一份有效快照。

## 10. 资源与工程同步

新增：

- `cc-quest/build.gradle.kts`；
- `cc-quest/src/main/resources/plugin.yml`；
- `cc-quest/src/main/resources/config.yml`；
- `cc-quest/src/main/resources/chapters.yml`；
- `cc-quest/src/main/java/...` 下的模块实现。

同步：

- `settings.gradle.kts`；
- `README.md`；
- `docs/architecture.md`；
- `docs/module-dev-guide.md`；
- `docs/development-plan.md`。

`events-protocol.md` 中所需任务事件已存在，本轮不新增事件定义；实现时只核对字段约定并严格使用已登记名称。

## 11. 验收

构建命令：

```powershell
.\gradlew.bat :cc-quest:build --offline
.\gradlew.bat build --offline
```

验收必须覆盖：

- 模块获取核心 API、配置错误安全降级；
- `cc_quest_progress` 读写不阻塞主线程；
- 事件可按索引推进正确任务；
- 重复事件和重复点击不重复奖励；
- `/cc quest` 能打开 GUI；
- 任务入口显示在成长体系分类；
- 普通玩家不能执行管理命令；
- `/cc reload` 不产生重复注册；
- 所有订阅、命令、菜单和任务在禁用时清理；
- 不使用 `Player` 作为长期 Map 键；
- 全量构建成功；
- Java diagnostics 无新增错误。

## 12. 风险边界

- 不在异步回调操作 Bukkit 玩家、Inventory、世界或实体；
- 不通过反射访问核心内部档案；
- 不将任务历史事件当作可重放队列；
- 不在首版加入跨模块编译依赖；
- 不把奖励领取实现为可重复调用；
- 不修改现有模块事件语义和数据库迁移结构。
