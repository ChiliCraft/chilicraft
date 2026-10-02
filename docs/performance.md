# 性能红线

全项目共同遵守的硬性纪律。违反任何一条都不允许合入（含 AI agent 生成的代码）。多数条目源自规格文档第五节与现有代码注释，此处汇总为验收口径。

## 1. 周期任务驱动，禁止高频事件监听

温度、负重、刷怪等持续状态一律由**低频周期任务**驱动，禁止监听 `PlayerMoveEvent`、背包变化等高频 Bukkit 事件做重算（[PressureTask.java](<../cc-survival/src/main/java/com/chilicraft/survival/PressureTask.java>) 类注释为原始出处）。

现网周期表：

| 任务 | 周期 | 覆盖业务 |
|---|---|---|
| cc-survival 压力循环 | 1 秒（20L, 20L） | 负重效果 / 体温步进与失温伤害 / 饥饿耗竭 / HUD |
| cc-demon 刷怪循环 | 2 秒（40L, 40L） | 饿意名单维护 / 刷怪 / 索敌 |
| cc-adventure 冒险循环 | 1 秒（20L, 20L） | 地城 / 远征推进、BossBar 更新；Boss 触发与技能按配置间隔执行 |
| cc-core 档案自动保存 | `auto-save.interval-seconds`（下限 5 秒，防误配打爆调度器） | 档案落库 |
| cc-season 主循环 | 1 秒（20L, 20L） | 日历推进、HUD、低频天气检查 |
| cc-season 动物迁徙 | `migration.period-seconds`（默认 300 秒） | 主线程扫描配置世界出生点半径内名单动物，单轮最多 `max-entities` 个，安全水平位移并逐实体异常隔离 |
| cc-season biome 视觉 | 随区块发包；换季按客户端视距刷新，每 tick 固定批量 | ProtocolLib 包级重写，不修改真实 biome；biome registry ID 在初始化/重载时缓存；缺失或结构不匹配时跳过并汇总统计 |

新增周期业务：并入所属模块现有任务，不新开调度器句柄。

## 2. 单任务单句柄 + 逐玩家隔离

- 每模块至多一个 `runTaskTimer` 句柄，业务按段组织在 `run()` 内；
- 逐玩家 `try-catch`：单人处理异常记录告警并继续下一人，不中断整轮；
- 句柄存字段，onDisable `cancel()` 置 null。

## 3. 配置读取必须走缓存

- Settings 类 `refresh()` 一次性重建全部字段，字段包内可见；周期路径只读字段，**禁止**每秒穿透 `FileConfiguration`；
- 玩家级强度（模式参数）例外：`api.getParam` 每次调用穿透读取，换取模式切换即刻生效——这是有意设计，不要为它加玩家侧缓存。

## 4. 算法层省扫描

热源检测范式（cc-survival）：仅当环境目标体温低于热源保障值（`temperature.heat-guarantee`）时才扫描周围方块。同类「先廉价判定再昂贵扫描」的短路必须保留。

## 5. 事件总线回调轻量

`publish` 主线程同步分发，回调耗时直接阻塞主线程。回调内只做查表 / 改数值 / 发消息这类微秒级操作；需要 DB 或重计算的，投递到后续任务或 RowStore 异步通道。

## 6. 数据库线程契约

- SQLite（WAL）+ HikariCP；**全部写操作**经核心单线程 executor 串行，主线程禁止同步写；
- `rowStore()` 返回的 future 完成回调在 **DB 线程**：回调内操作游戏状态必须先回主线程；
- 关停顺序：先停 executor 冲刷剩余写任务（上限 10 秒），最后关连接池。

## 7. 内存纪律

- 事件总线持订阅者强引用：每个 `subscribe` 在 onDisable 必有对应 `unsubscribe`；
- 周期效果（药水等）用「每秒续期 + 短时长自动过期」实现（如 40 ticks），插件停止后自然过期，无残留定时清理逻辑；
- 模块内玩家态数据（档案缓存、名单、快照）在 onDisable 统一 `clear()` / 置 null。

## 8. 构建期红线

- 工具链 / 编码 / `release=21` 由根构建脚本统一施加，附属禁止重复声明；
- 全部依赖 `compileOnly`，附属 jar 不 shade（仅 cc-core shadowJar）；
- `-Xlint:deprecation` 保持开启，过时 API 用法必须在注释中说明原因。
