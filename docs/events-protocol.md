# 事件与数据协议

ChiliCraft 跨模块协作的唯一通道：字符串事件名 + `EventData` 载体 + 主线程同步事件总线。核心零改动即可让任何附属订阅任何其他模块的事件（含尚未实现的规划事件）。

## 契约

### 命名规范

事件名 = `发布方模块名.事件名`（蛇形小写）：`demon.mob_killed`、`street.world_enter`。核心专属事件用 `core.` 前缀。

### EventData 载体（[EventData.java](<../cc-core/src/main/java/com/chilicraft/api/EventData.java>)）

| 字段 | 类型 | 约定 |
|---|---|---|
| `playerId()` | `UUID`，可空 | 事件关联玩家；`null` = 世界级事件 |
| `target()` | `String`，可空 | 目标标识（实体类型、地城 ID、章节 ID 等） |
| `amount()` | `int` | 数量值，默认 0，可为负 |
| `put(key, value)` / `get(key)` | 扩展键值 | 发布前填好；`put` 返回 this 可链式 |

发布方在构造后、publish 前填满全部字段；订阅方回调中只读。

### 线程契约

- `publish` **只允许主线程**调用（`EventBusImpl` 内有主线程断言，违反抛 `IllegalStateException`）；
- 分发同步进行：回调内可安全访问游戏状态，但回调耗时直接阻塞主线程——回调内只做轻量操作；
- 事件总线持订阅者**强引用**：`onDisable` 必须对每个 `subscribe` 调用对应的 `unsubscribe`。

### 发布 / 订阅范式

```java
// 发布（主线程）
api.publish("street.world_enter", new EventData(player.getUniqueId()));

// 订阅（onEnable；handler 存字段以便 onDisable 退订）
streetHandler = (eventName, data) -> {
    UUID pid = data.playerId();
    if (pid == null) { return; }               // 世界级事件按需跳过
    Player player = Bukkit.getPlayer(pid);
    if (player != null && player.isOnline()) { /* ... */ }
};
api.subscribe("street.world_enter", streetHandler);

// 退订（onDisable）
api.unsubscribe("street.world_enter", streetHandler);
```

同一 handler 可重复订阅不同事件名；`unsubscribe` 未订阅的组合静默忽略。

## 全量事件清单

### 现网已发布

| 事件 | 发布方 | playerId | target | amount | 触发时机 |
|---|---|---|---|---|---|
| `core.reload` | cc-core（`/cc reload`） | — | — | — | 配置重载完成；订阅方约定：`reloadConfig()` + `settings.refresh()` |
| `core.mode_switched` | cc-core | 玩家 | 新模式名（`adventure`/`cozy`） | 0 | 模式切换成功；extra `from` = 旧模式名 |
| `demon.hunger_triggered` | cc-demon | 玩家 | `hunger` | 当前饱食度 | 饿意 false→true 转换（入饿意名单瞬间） |
| `demon.mob_killed` | cc-demon | 击杀者 | 实体类型 ID | 1 | 玩家击杀饿魔（灵魂掉落记账源） |
| `soul.player_died` | cc-soul | 死者 | 死因名 | 灵魂损失量 | 玩家死亡（灵魂扣减与播报完成） |
| `soul.relic_gained` | cc-soul | 玩家 | 遗物 ID | 1 | 获得遗物 |
| `soul.relic_funeral` | cc-soul | 玩家 | 遗物 ID | 1 | 遗物葬礼完成（旧眼镜祭品可缩短仪式） |
| `martial.arena_win` | cc-martial | 冠军 | `arena` | 1 | 竞技场夺冠（含败者安慰礼与「演」演出之后） |
| `martial.realm_up` | cc-martial | 玩家 | 境界 ID | 新境界序号 | 武学境界晋升 |
| `adventure.boss_killed` | cc-adventure | 击杀者 | Boss ID | 1 | 每次世界 Boss 被玩家击杀后发布；扩展键 `firstKill` 表示是否为该玩家首杀 |
| `adventure.dungeon_clear` | cc-adventure | 队员（逐人发布） | 地城 ID | 灵魂奖励 | 地城通关结算时逐队员发布；cc-martial 订阅累计素青纱境界解锁进度 |
| `adventure.expedition_result` | cc-adventure | 队员（逐人发布） | `expedition` | 到达层数 | 远征结束（通关 / 主动退出 / 全员离线兜底）落库后逐队员发布；扩展键 `layer` = 到达层数、`result` = 结果（`clear`/`quit`）、`soul` = 灵魂结算量 |
| `season.changed` | cc-season | — | 新季节（`spring`/`summer`/`autumn`/`winter`） | 年份 | 仅翻季时发布；扩展键 `day` = 季内日、`trigger` = `natural`/`command` |
| `season.day_changed` | cc-season | — | 当天所属季节 | 年份 | 每次自然翻日或管理员调整日期/季节后发布；扩展键 `day` = 季内日 |
| `season.special_day` | cc-season | — | 配置名单中的联动标识 | 年份 | 自然翻日或管理员调整后命中特别日子名单时发布；扩展键 `season` = 季节、`day` = 季内日 |

> cc-season 三个事件均由主线程同步发布；同一次翻日的固定顺序为 `season.changed`（仅翻季）→ `season.day_changed` → `season.special_day`（命中时）。订阅方只缓存季节值，不得持有 cc-season 内部状态对象。

### 规划中（已有订阅方，发布方待实现）

| 事件 | 发布方（规划） | playerId | target | 订阅方 | 现网订阅方行为 |
|---|---|---|---|---|---|
| `street.world_enter` | cc-street（规格 v1.1） | 入街玩家 | — | cc-demon | 清除玩家周围 `radiusMax` 半径内存量饿魔（持续不刷怪由 `spawn.excluded-worlds` 保证） |
| | | | | cc-survival | 发送 `messages.street-calm` 提示（压力折减由 `street.worlds` 名单持续生效） |
| `quest.chapter_cleared` | cc-quest | 玩家 | 章节 ID | 1 | 章节内全部任务进入 CLAIMED 后发布一次；无扩展字段 |
| `quest.quest_completed` | cc-quest | 玩家 | 任务 ID | 1 | 任务领取成功、状态进入 CLAIMED 后发布一次；无扩展字段 |
| `events.world_event_start` | cc-events（未立项） | —（世界级） | 事件 ID | 暂无（规划：全模块联动） | 扩展键 `duration` = 时长秒；`playerId` 为 null 表示全服事件 |
| `economy.market_trade` | cc-economy（未立项） | 玩家 | 交易类型（`stall`/`auction`/`barter`） | 暂无（规划：cc-core 灵魂结算） | `amount` = 成交灵魂金额 |
| `street.market_trade` | cc-street（未立项） | 玩家 | 摊位 ID | 暂无（规划：cc-economy 记账、cc-core 灵魂结算） | `amount` = 成交灵魂金额 |
| `park.game_clear` | cc-street（未立项） | 玩家 | 项目 ID | 暂无（规划：cc-quest、cc-soul、cc-events） | `amount` = 成绩分数 |
| `street.souvenir_complete` | cc-street（未立项） | 玩家 | 纪念品集 ID | 暂无（规划：cc-quest、cc-events） | 纪念品集齐时发布一次 |

> 事件名先于实现存在是本项目常态：订阅不依赖发布方编译期存在，发布方未上线时事件静默不触发。新增事件先在本文档登记，再写代码。

## 灵魂货币记账约定

灵魂变动**必须**带 `reason` 字符串留痕（台账 `LedgerEntry`），命名蛇形：`arena_loser_consolation`（下等马安慰礼）、`arena_champion_reward`、`demon_mob_killed` 等。余额校验扣减用 `spendSoul`（不足返回 false 零变更），无校验增减用 `addSoul`（amount 可负）。
