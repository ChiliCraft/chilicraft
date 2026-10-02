# ChiliCraft 后续开发计划（基于实现规格 v1.1）

- **基准文档**：《ChiliCraft·插件版技术文档（实现规格 v1.1）》（2026-09-27 发布，根目录）
- **编制日期**：2026-09-28；时间以周为单位，W1 = 2026-09-28 当周
- **口径**：验收标准与性能红线一律以规格 v1.1 与 [performance.md](performance.md) 为准，本文不重抄总纲数值；实施顺序遵循规格「开发顺序与验收里程碑」第三～六步

---

## 1. 现状基线

### 1.1 已实现（5 / 11 jar，均与 v1.1 规格对齐）

| 模块 | 覆盖范围 | 备注 |
|---|---|---|
| cc-core | 档案 / 模式（24h 冷却）/ 参数×家园区 / 事件总线（主线程同步）/ SQLite（WAL＋单写者）/ 灵魂台账 / `/cc` 命令 / 主菜单 GUI / IntegrationManager 软依赖检测 | 数据库已预建 12 表（含 `cc_quest_progress`、`cc_skills`、`cc_expedition_runs`、`cc_auctions` 等未来表） |
| cc-survival | 饥饿 / 体温 / 负重 / 耐久，1 秒统一压力循环 | 已含方街 `street.worlds` 压力减半联动 |
| cc-demon | 三型饿魔（原版改属性＋改名）/ 饿意追踪 / 区块全服双限额 / 白天清除 / 掉落 | 已含 `spawn.excluded-worlds` 方街安全区联动 |
| cc-soul | 死亡结算 / 临终遗言 / 灵魂碎片 / 葬礼（旧眼镜供品）/ 12 遗物 / 收容所播报 | 遗物定义 12 条已满配 |
| cc-martial | 6 境界 / 5 流派全套技能（skills.yml 数据驱动）/ 45 词缀 / 每日擂台 20:00＋22:00 / 下等马安慰礼 / 「演」夺冠演出 | 已带 MythicAdapter 与 `%chilimartial_*` PlaceholderAPI 扩展 |

测试服 `test-server/` 已就绪：Paper 1.20.4 ＋ 全部联动插件（PlaceholderAPI / Vault / MythicMobs 5.13.0 / Citizens 2.0.44 / EssentialsX / LuckPerms / WorldEdit / WorldGuard）＋ smoke-test 脚本。

### 1.2 差距清单

| 类别 | 项 | 说明 |
|---|---|---|
| A. 现有模块补差 | cc-demon × MythicMobs 接管 | 规格：安装 MM 时怪物技能与行为由 MM 接管（原版模型不变）；现状无 MM 分支 |
| A | cc-core PlaceholderAPI 全局扩展 | HUD 占位符（灵魂 / 模式 / 境界 / 体温 / 负重等）未注册；martial 仅自有变量 |
| A | 兼容基线 | 规格「更新发布与兼容策略」：附属 plugin.yml 声明 `compatible-core`、升级配置合并（保留旧值＋新键补默认）——现状未落地 |
| B. 未立项模块 | cc-adventure | 10 地城（实例化副本）/ 远征（5 层 9 种房间）/ 5 世界 Boss |
| B | cc-quest | 6 章节主线＋终章后篇「欢迎来到方街」/ 20 主线任务 / 8 任务类型 / 旅途手册 GUI / 歌词结算 |
| B | cc-cozy | 四轴成长 / 宠物 / NPC 好感 / recipes.yml |
| B | cc-events | 9 世界事件调度 / 说书人 / 廉价商店 / 氛围演出 |
| B | cc-economy | 四层市场 / 6 职业 / 物品流转 / Vault 灵魂注册 |
| B | cc-street（v1.1 新增） | 方街世界＋街区设施＋厚嘴唇集市＋游乐园二期＋限定纪念品 |
| C. 登记缺口 | 事件 | events-protocol.md「规划中」仅登记 `street.world_enter`、`adventure.dungeon_clear`、`adventure.boss_killed`；规格事件表另 8 个事件待登记 |
| C | 数据表 | `cc_street_memories`、`cc_mail_letters`、`cc_park_records`、`cc_souvenirs` 未建；RowStore 白名单未含 |

---

## 2. 开发目标与优先级

**总目标**：完成规格 v1.1 全部 11 jar，实现「主线可走通、双玩法全量、巡演主题全融入」，终版发布 v1.1.0 全量包。cc-core 保持契约零破坏（受控改动仅限建表＋白名单＋全局占位符注册）。

**优先级排序**（依据规格开发顺序第三～六步；事件依赖决定先后）：

| 级别 | 内容 | 理由 |
|---|---|---|
| P0 | cc-adventure → cc-quest | 冒险线主链路；cc-martial 素青纱 / 铜钱挂 / 破天下三个境界门槛全部悬置等待 `adventure.*` 事件；quest 是主线验收载体 |
| P1 | cc-cozy ＋ cc-events ＋ cc-economy | 养老线补齐；economy 的摊位与 Vault 注册是 cc-street 集市的前置；events 氛围演出被 Livehouse / 游乐园开业季复用 |
| P1.5 | cc-street 一期（世界＋设施＋集市）→ 二期（游乐园＋纪念品） | 巡演主题核心交付；安全区联动（demon / survival 订阅）已就绪，随到随接 |
| P2 | 现有模块补差（A 类） | 穿插在所属里程碑执行：MythicMobs 接管随 M1（Boss / 饿魔技能统一用）、全局占位符随 M0、兼容基线随 M0 |

---

## 3. 功能模块划分

| 模块 | 一句话职责 | 关键交付 | 依赖 |
|---|---|---|---|
| cc-adventure | 地城 / 远征 / 世界 Boss（冒险线） | dungeons.yml、远征世界、5 Boss、`adventure.*` 3 事件 | 仅核心；martial 经事件联动 |
| cc-quest | 任务书 / 主线剧情 | chapters.yml、旅途手册 GUI、歌词结算、`quest.*` 2 事件 | 订阅 demon / martial / adventure / soul 事件 |
| cc-cozy | 养老四轴 | recipes.yml、好感 NPC、宠物、`cozy.*` 3 事件 | 仅核心；可选 Citizens |
| cc-events | 世界事件与氛围 | events.yml、说书人、廉价商店、`events.world_event_start` | 订阅全部模块事件 |
| cc-economy | 经济与市场 | 摊位 / 拍卖行 / 以物易物、6 职业、物品流转 PDC、Vault 注册、`economy.market_trade` | 仅核心；可选 Vault / QuickShop |
| cc-street | 方街＋游乐园（巡演主题） | world_street、8 类设施＋彩蛋、集市、6 游乐园项目、纪念品、`street.*` / `park.*` 4 事件 | demon / survival 订阅已就绪；集市复用 economy 摊位；氛围复用 events |

**cc-core 受控改动点**（唯一允许触碰核心的三处，契约零变更）：

1. `SchemaMigrations` 增 4 表：`cc_street_memories`、`cc_mail_letters`、`cc_park_records`、`cc_souvenirs`；
2. `RowStoreImpl` 白名单同步追加上述 4 表；
3. 新增 PlaceholderAPI 全局扩展注册（`compileOnly` 引入 PAPI 依赖，不进 shadowJar）。

---

## 4. 任务分解与时间节点

总量约 **13–14 周**（含缓冲）。每里程碑收尾执行：`gradlew build`（或 `--offline`）→ 文档同步（见第 9 节）→ 测试服冒烟。

| 里程碑 | 周期 | 内容 | 累计进度 |
|---|---|---|---|
| M0 地基 | W1 | 补差＋登记＋兼容基线 | 5.5/11 |
| M1 | W2–W4 | cc-adventure | 6.5/11 |
| M2 | W5–W6.5 | cc-quest | 7.5/11 |
| M3 | W7–W9.5 | cc-cozy / cc-events / cc-economy | 10.5/11 |
| M4 | W10–W11.5 | cc-street 一期（世界＋设施＋集市） | 10.5/11（street 部分完成） |
| M5 | W12–W14 | cc-street 二期＋全量联调＋v1.1.0 发布 | 11/11 |

### M0（W1）：地基与补差

| 任务 | 产出 | 说明 |
|---|---|---|
| T0.1 事件登记 | events-protocol.md「规划中」表 +8 行 | `adventure.expedition_result`、`quest.chapter_cleared`、`quest.quest_completed`、`events.world_event_start`、`economy.market_trade`、`street.market_trade`、`park.game_clear`、`street.souvenir_complete`，含载荷约定（先登记后编码） |
| T0.2 建表 | SchemaMigrations +4 表、RowStore 白名单 +4 | cc-core 受控改动，同步 architecture.md 持久化小节 |
| T0.3 cc-demon × MythicMobs | DemonSettings `mythic` 段 ＋ 检测降级 | 照抄 cc-martial MythicAdapter 范式（本地 `getPlugin` 检测＋自身 config 开关）；MM 缺失走现有属性修改器 |
| T0.4 核心全局占位符 | `ChiliCraftExpansion`（`%chilicraft_soul/_mode/_realm/_temperature/_weight/...`） | build.gradle.kts 加 `compileOnly` PAPI；占位符清单登记 README |
| T0.5 兼容基线 | 附属 plugin.yml `compatible-core` 字段＋升级配置合并评估 | 合并策略实现于核心配置装载，评估工作量后可并入 M1 |
| T0.6 内容提请 | 向主创提交《文案手册》与数值校准需求清单 | 外部依赖，不阻塞开发（占位文案先行） |

### M1（W2–W4）：cc-adventure

| 周 | 任务 | 产出 |
|---|---|---|
| W2 | 模块五件套骨架；dungeons.yml 数据驱动（10 地城定义）；地城实例化引擎（地块模板按需复制、每队独立区域、进出与卸载） | 地城框架可加载任意地城定义 |
| W3 | 5 类机制事件状态机（守锅 / 献祭 / 承重 / 水下 / 双界占位）；`adventure.dungeon_clear` 发布；首城「深夜食堂」走通 | martial 素青纱境界随事件解锁 |
| W4 | 远征（独立世界按需加载、9 种房间权重随机、祝福：诅咒 = 2:1、5 层制、`cc_expedition_runs` 落库）；5 世界 Boss（原版属性＋技能事件，MM 在场时技能托管；触发条件按总纲；首杀奖励）；`adventure.boss_killed` / `adventure.expedition_result` 发布 | 远征 5 层走通；破天下境界可解锁 |

### M2（W5–W6.5）：cc-quest

| 周 | 任务 | 产出 |
|---|---|---|
| W5 | chapters.yml 填表引擎；8 任务类型适配器（击杀 / 收集 / 合成 / 探索 / 对话 / 生存 / 挑战 / 仪式——击杀 / 挑战 / 仪式走事件订阅，其余走低频 Bukkit 事件）；任务状态机（未解锁→进行中→可提交→已领取，`cc_quest_progress`）；旅途手册（成书改名＋箱子 GUI：追踪 / 回顾 / 领奖） | 任务系统闭环 |
| W6 | 6 章节内容骨架（主创填表）；歌词结算（完成 actionbar 弹词、章节推进 title＋天气 / 粒子氛围，文案全量可替换）；`quest.*` 2 事件发布；终章后篇「欢迎来到方街」占位（3–5 收尾任务定义先行，`/street` 未上线前静默挂起） | 主线可完整走通（内容允许占位） |

### M3（W7–W9.5）：cc-cozy ＋ cc-events ＋ cc-economy（三模块按周串行，互不阻塞）

| 周 | 模块 | 任务 |
|---|---|---|
| W7 | cc-cozy | 四轴：农业等级（种收积分）/ 料理图鉴（recipes.yml 数据驱动）/ 家园评分 / NPC 好感（村民改名＋对话 GUI，Citizens 在场升级气泡）；宠物（可驯服生物＋改名）；`cozy.*` 3 事件；进度复用 `cc_skills` / `cc_achievements` 表（免建表） |
| W8 | cc-events | events.yml 调度器（9 事件：频率 / 时长 / 内容可配，游乐园开业季留 cc-street 二期开关位）；说书人 NPC（承接剧情补叙）；廉价商店（低价回收 / 随机出售——「五块钱的伞」）；氛围演出订阅器（雨 / 暮色 / 粒子 / 音效，整包可替换）；`events.world_event_start` 发布 |
| W9 | cc-economy | 摊位（箱子＋GUI，预留 street 集市复用入口）；拍卖行 48h（`cc_auctions`）；以物易物；6 职业（切换冷却 24h，`profile.profession` 核心已有字段，加成走事件钩子）；物品流转（PDC 历任持有者，拾取有故事物品得额外灵魂）；**Vault 灵魂货币注册**（Economy 包装 SoulService，统一事务＋台账）；`economy.market_trade` 发布 |

### M4（W10–W11.5）：cc-street 一期

| 周 | 任务 | 产出 |
|---|---|---|
| W10 | 技术验证 spike（world_street 生成方案二选一定案：jar 内置模板释放 vs 蓝图代码生成）；世界管理（首次启动释放＋加载、`/street` 传送、全境安全区：冒险模式 / 饱食不衰减 / 无死亡掉落 / 不刷怪）；发布 `street.world_enter`（demon 清怪＋survival 提示已就绪，随到随接）；GUI 设施基座 | 入街 TPS 无衰减、无生存压力 |
| W11 | 8 类设施：居委会留言板（`cc_street_memories`）/ 纪念品店 / KTV 点歌 GUI（好感联动）/ Livehouse 定时演出（复用 events 氛围）/ 旅馆床位（离线挂机点）/ 餐厅（联动 cozy 料理图鉴）/ 邮筒（`cc_mail_letters`）/ 公告栏；街道彩蛋（公交站牌 / 信号灯鸽鸽 / 斑马线 / 凸面镜 / 理发店 / 衡山路宛平路路牌）；厚嘴唇集市（周末开市、摊位复用 economy、`street.market_trade` 记账、扭蛋机＋观众信箱） | 设施全部可用、彩蛋可触发、记账完整 |

### M5（W12–W14）：cc-street 二期 ＋ 联调发布

| 周 | 任务 | 产出 |
|---|---|---|
| W12 | 游乐园 6 项目（矿车过山车计圈计时 / 摩天轮每日登轮 buff / 雪球套圈 / 鬼屋复用 demon 属性变体·白天开放 / 弩靶射击场 / 时光盲盒扭蛋机·随机歌曲彩蛋）；排号卡防拥堵；门票 10 灵魂·周末免费（初值）；`park.game_clear` 发布＋`cc_park_records` 落库 | 6 项目可通关、成绩入库 |
| W13 | 限定纪念品（PDC 唯一 ID＋流转史——复用 cc-soul 遗物结构范式＋城市标签＋`cc_souvenirs`）；集齐发布 `street.souvenir_complete`（奖励由 quest 联动发放）；巡演映射表 19 行逐条核销 | 纪念品唯一可流转；映射表全核销 |
| W14 | 全量联调（全事件链回归）；性能验收（30 人 TPS、performance.md 红线逐条）；文档全面同步；发布 v1.1.0 全量包（11 jar＋更新说明） | 终验通过 |

---

## 5. 资源需求评估

| 类别 | 需求 | 现状 |
|---|---|---|
| 开发人力 | 主开发 1 人；AI agent 按模块开发指南五件套范式批量铺量（骨架 / 配置 / 消息模板可并行生成，人工做机制与联调） | 具备 |
| 内容输入 | 主创提供：《文案手册》、chapters / recipes / events / dungeons 内容表、数值实测校准（规格明确内容填充不占开发产能，但 M2 / M3 验收需要至少占位内容） | 待提供（T0.6 已提请，需求清单见 [copywriting-requests.md](copywriting-requests.md)） |
| 构建环境 | JDK 21 工具链（缺失自动下载）、papermc 网络（代理）或 `--offline` 缓存 | 具备 |
| 测试环境 | test-server：Paper 1.20.4 ＋ 8 外部插件已部署，需锁版本记录并在每里程碑做「缺插件降级」冒烟（删插件验证软依赖） | 就绪，补回归清单 |
| 压测资源 | 30 人并发 TPS 验收：组织玩家多端进服，或以小样本（10 人）× 3 负荷场景折算口径报备 | 需协调 |
| 新依赖 | 仅 `compileOnly`：PlaceholderAPI（M0）、可选 QuickShop / ChestShop（M9 评估）；附属一律不 shade | 符合 AGENTS 约束 |
| 存储容量 | world_street 独立世界＋远征世界（按需加载卸载）；若走内置模板方案注意 jar 体积（评估上限 ≤ 数十 MB） | M4 spike 定案 |

---

## 6. 技术实现方案概述

### 6.1 通用约束（全部新模块遵守）

- 五件套范式（主类 / Settings / Service / Listener / Task），装配流程照抄 module-dev-guide Step 0–5；
- 事件先登记后编码；`publish` 仅主线程、回调轻量；周期任务单句柄＋逐玩家 try-catch，并入模块现有任务；
- 持久化只走 RowStore 白名单表（异步、回调回主线程）＋ PDC 存物品级数据，禁止附属直连 DB、禁止手拼 NBT；
- 内容一律数据驱动 YAML（chapters / recipes / events / dungeons / street / park / souvenirs），主创不写代码可加内容；非法值回退默认告警。

### 6.2 关键设计

| 模块 | 方案要点 |
|---|---|
| cc-adventure 地城 | 地块复制优先原版结构模板（结构方块 / `.nbt`），WorldEdit API 作软依赖增强；每队实例 = 独立区域坐标＋状态机（进入→推进→结算→卸载），房间按需生成（红线：禁止一次性全生成）；队列满时排队复用实例区 |
| cc-adventure Boss | 原版生物＋属性修改器＋技能事件钩子（沿 cc-demon 范式）；MythicMobs 在场时技能定义交 MM 配置（`mythic-*` 键），缺失降级 |
| cc-quest | 状态机 4 态落 `cc_quest_progress`（联合主键防重）；任务类型 = 适配器表（type → 事件 / 低频 Bukkit 监听映射），新增类型不改引擎；旅途手册 GUI 用 `InventoryHolder` 绑定（红线：禁 getTitle 比对） |
| cc-cozy | 四轴进度复用 `cc_skills`（axis 作为 skill_id）与 `cc_achievements`，避免扩表；料理 = 原版合成表＋recipes.yml 解锁图鉴 |
| cc-events | 单调度器周期任务扫描 events.yml 时间窗（并入模块唯一周期句柄）；氛围演出 = 订阅其他模块事件后发 title / 粒子 / 音效，回调轻量 |
| cc-economy | 交易统一入口（灵魂结算必经核心 `spendSoul` / `addSoul` 事务＋台账）；Vault Economy 实现包装 SoulService，注册失败静默降级内部货币；拍卖行到期由周期任务扫描结算；物品流转史存 PDC（继承 `soul` 遗物 history 范式） |
| cc-street 世界 | 安全区 = 世界级规则（GameMode.ADVENTURE 强制、饱食锁定、死亡不掉落）＋ 发 `street.world_enter` 让 demon / survival 既有名单机制生效（核心参数表零改动，符合 v1.1 决策）；设施 = 固定坐标＋交互 GUI（坐标进 street.yml，热载） |
| cc-street 游乐园 | 红线：禁止设施全实体实时模拟——过山车用原版矿车＋检测点方块计圈；套圈 / 弩靶用投掷物命中事件判定；鬼屋 = demon 属性变体（白天开放反向利用夜间逻辑）；成绩写 `cc_park_records`（联合主键存最佳成绩） |
| cc-street 纪念品 | PDC 唯一 ID＋流转史＋城市标签，结构复用 cc-soul `cc_relics` 范式；集齐判定由 quest 订阅 `street.souvenir_complete` 发奖 |

### 6.3 兼容性方案（对齐规格「更新发布与兼容策略」）

- cc-core API 维持 1.0 契约，本次规划**不新增** `com.chilicraft.api` 接口方法（附属经 RowStore 与事件解决全部需求）；
- 每模块 plugin.yml 声明 `compatible-core: ">=1.0"`，核心装载时校验并给出可读告警；
- 升级配置合并：模块配置装载时对缺失键补默认值（Settings 层已有回退逻辑，M0 补文档口径即可，无需破坏性改动）；
- 发布物固定两件：`cc-xxx-版本.jar` ＋《更新说明.md》（新增配置键 / 是否需删旧配置 / 新增功能）。

---

## 7. 风险识别与应对

| # | 风险 | 等级 | 应对策略 |
|---|---|---|---|
| R1 | 地城实例化复杂度高（多队并发、数据隔离、地块复制） | 高 | 分两阶段：W2 先静态多入口验证机制，W3 再真实例化；地块复制首选原版结构模板（无 API 风险），WorldEdit 增强可缺省 |
| R2 | 游乐园实体模拟触碰性能红线 | 高 | 设计期即锁定原版机制＋事件判定（见 6.2）；M5 压测不达标则削减实体特效，机制不减 |
| R3 | 事件总线主线程回调被重逻辑阻塞 | 中 | 回调只做查表 / 数值 / 发消息；DB 一律走 RowStore 异步通道；code review 按 performance.md 第 5 条把关 |
| R4 | SQLite 单写者吞吐不足（拍卖行 / 流转史高频写） | 中 | 复用「触发式内存态＋批量落库」范式（cc-soul 灵韵 `db-flush-interval` 已验证）；拍卖结算合并写 |
| R5 | 《文案手册》与数值校准滞后 | 中 | 全配置驱动＋默认值兜底＋占位文案先行（规格已确认不阻塞开发）；每个里程碑向主创发内容清单 |
| R6 | 外部插件版本漂移 / 缺失 | 中 | 软依赖＋本地检测＋降级路径固化；每里程碑回归「缺插件冒烟」（MythicMobs / Citizens / Vault 三项必测） |
| R7 | cc-core 受控改动引发回归 | 中 | 改动面锁定三处（建表 / 白名单 / PAPI 注册）；改前 events-protocol / architecture 登记，改后全量构建＋全模块装载冒烟 |
| R8 | world_street 生成方案不确定（模板体积 vs 蓝图还原度） | 中 | M4 第一周 spike 二选一定案；内置蓝图代码生成为兜底方案（体积零风险，还原度靠分段生成器） |
| R9 | 巡演命名与世界观引用边界 | 低 | 全部经配置 / messages 段落地可整包替换；对外发布前按规格「待确认项」复核 |
| R10 | 单人＋AI 产能瓶颈 | 低 | 严格范式化（五件套 / YAML 内容），AI 批量铺骨架；里程碑验收卡点防止半成品堆积；必要时 M3 三模块调序并行 |

---

## 8. 阶段交付物与验收标准

### 8.1 每模块交付物

1. `cc-xxx-1.0.0.jar`（附属普通 jar；cc-core 另出 shadowJar）；
2. `src/main/resources` 全套配置（config.yml ＋ 内容文件，全中文注释）；
3. 《更新说明.md》；
4. 文档同步（见 9 节）。

### 8.2 全局验收标准（每个里程碑末执行）

| # | 标准 | 口径 |
|---|---|---|
| 1 | `gradlew.bat build`（或 `--offline`）BUILD SUCCESSFUL | 构建级 |
| 2 | test-server 全模块装载无 ERROR，各模块命令可用 | smoke-test |
| 3 | 30 人并发 TPS 稳定 20（规格口径） | M5 全量压测；中间里程碑以 10 人抽样＋红线自检代偿 |
| 4 | performance.md 红线逐条通过（周期任务纪律 / 缓存纪律 / DB 线程契约 / PDC / InventoryHolder / 事件回调轻量） | 评审勾稽表 |
| 5 | events-protocol.md 与代码一致：全部 publish / subscribe 在清单登记（先登记后编码） | 文档核对 |
| 6 | 兼容性：cc-core API 无破坏；删除任一附属其余模块正常加载；升级配置保留旧值新键补默认 | 手工验证 |

### 8.3 模块级验收标准（承接规格 v1.1 各模块「验收」列）

| 里程碑 | 验收要点 |
|---|---|
| M1 | 每队地城独立不串场；远征 5 层完整走通；Boss 首杀奖励发放正确；martial 三个悬置境界随事件解锁 |
| M2 | 任务书 GUI 可用；6 章节主线完整走通（内容允许占位）；歌词结算触发；章节门槛联动境界；进度跨线跨天不丢 |
| M3 | cozy 四轴各自可成长可查询；世界事件按调度触发、可手动开关；市场交易记账完整；职业加成可复现；Vault 商店可收灵魂（装 Vault 时） |
| M4 | 方街安全区规则生效（入街 TPS 无衰减、无生存压力）；街区设施可用、彩蛋可触发；集市周末开市、记账完整 |
| M5 | 6 个游乐园项目可通关、成绩入库；限定纪念品唯一可流转、集齐有奖；巡演映射表 19 行全部可触发；终版 v1.1.0 全量包发布 |

---

## 9. 文档同步清单（每里程碑硬性核对，依据 AGENTS.md）

| 改动 | 必须同步 |
|---|---|
| 新建任一附属模块 | settings.gradle.kts include；README 模块总览表；architecture.md 工程结构；module-dev-guide.md（沉淀新范式时） |
| 新增 / 变更事件 | events-protocol.md 清单（先登记后编码，含字段约定与订阅方行为） |
| 新增周期任务 | performance.md 周期表 |
| 新增数据表 / 白名单 | architecture.md 持久化小节 |
| 巡演映射表新增核销项 | README 巡演联动一览 |
| 装配范式 / API 契约变化 | architecture.md、module-dev-guide.md 对应段落 |

---

> 本计划随开发推进滚动修订：里程碑实际偏差超过 1 周时，更新第 4 节时间表并注明原因；规格 v1.1 再版时以新规格为准重排优先级。

---

# 插件整合计划 v2：Paper 1.21.1 全量模块路线

> 本节根据《ChiliCraft 开源插件缝合候选调研报告》制定，覆盖范围为 12 个 ChiliCraft 模块。它取代本文件前文中以 Paper 1.20.4 为基线的执行顺序；前文保留为历史基线和既有功能说明。

## 10. 计划目标与交付边界

### 10.1 目标

- 目标服务端：Paper 1.21.1，Java 21。
- 目标模块：`cc-core`、`cc-survival`、`cc-demon`、`cc-soul`、`cc-martial`、`cc-adventure`、`cc-season`、`cc-quest`、`cc-cozy`、`cc-events`、`cc-economy`、`cc-street`。
- 最终交付：只发布 ChiliCraft 自有 jar；第三方插件作为单独安装的可选软依赖，不打包进 ChiliCraft。
- 验收方式：构建成功 + 静态审计 + 测试服冒烟，不以运行压测作为本计划的硬门槛。

### 10.2 整合原则

1. **源码改造优先，但不是无条件嵌入**：MIT / Apache-2.0 候选必须先通过许可证、源码完整性、Paper 1.21.1 构建、零资产、数据边界五项闸门。
2. **许可证隔离**：GPL / LGPL / AGPL、闭源、无许可证项目不复制源码，不打包 jar；只能独立运行联动或作为设计参考。
3. **模块隔离**：附属模块仅 `compileOnly` 依赖 `cc-core`，模块间通过字符串事件和配置联动，禁止编译期硬依赖。
4. **零资产**：不强制资源包、自定义模型、材质贴图；所有功能须可用原版方块、物品、生物、粒子、音效和 BossBar 运行。
5. **降级优先**：每个软依赖都有缺失路径；第三方插件缺失不得阻止 ChiliCraft 核心和自有玩法启动。

## 11. 候选整合分层

| 层级 | 处理方式 | 首批代表项目 |
|---|---|---|
| 核心自有层 | 保留或自研，不引入外部玩法状态源 | `cc-core`、事件总线、PDC、MiniMessage、RowStore |
| 源码改造层 | 许可证闸门通过后，提取小而清晰的骨架并改造成 ChiliCraft 代码 | Fabled、SinceDungeon、SoulGraves、SimpleDialogue、EzAuction |
| 软依赖适配层 | 独立安装，通过公开 API、事件、PAPI、Vault 对接 | MythicMobs、Citizens、Vault、PlaceholderAPI、WorldGuard、QuickShop |
| 参考层 | 只借鉴架构、配置 schema、状态机和数值策略 | LuckPerms、EliteMobs、AuraSkills、ThermoSurvival |
| 自研玩法层 | ChiliCraft 独有规则，不寻找外部替代 | `cc-season`、章节剧情、家园评分、职业、巡演事件、纪念品 |

### 11.1 源码改造闸门

候选项目在进入对应模块前必须完成以下记录：

- 根仓库 LICENSE 与源码版权头确认；
- 实际使用的源码文件和资源文件清单；
- 1.21.1 构建结果与 API 适配差异；
- 是否依赖资源包、模型、私有服务或强制第三方插件；
- 数据表、PDC 命名空间、任务句柄和事件边界是否能独立迁移；
- ChiliCraft 发布物中的版权声明和 NOTICE 处理方式。

任何一项不通过，自动降级为软依赖或设计参考，不阻塞主线开发。

## 12. 分阶段整合路线

### M0：版本与合规基线

1. 全项目 Paper API 和 `plugin.yml` 升级到 1.21.1 / `api-version: "1.21"`。
2. 将编译目标调整为 Java 21，审计 Attribute、PotionEffectType 等接口化 API 用法。
3. 建立第三方插件版本锁定表、许可证清单、源码改造闸门记录。
4. 统一 `compatible-core`、配置缺失键补默认、软依赖检测和降级规范。
5. 在 `settings.gradle.kts`、README、architecture、events-protocol 中登记 `cc-season` 和新模块边界。
6. 先登记所有新增事件，再开始对应模块编码。

**出口条件**：现有模块全部构建成功；缺失软依赖不影响核心加载；1.21.1 兼容性问题有逐项处理记录。

### M1：核心及既有模块补差

- `cc-core` 只做受控改动：全局 PlaceholderAPI 扩展、必要数据表和 RowStore 白名单；不破坏 `ChiliCraftAPI` 契约。
- `cc-demon` 增加 MythicMobs 适配器，缺失时保留原版属性和行为路径。
- `cc-survival` 继续使用自有体温、负重、耐久状态源，不默认接入 ThermoSurvival 或 Inventory Weight，避免重复扣血和重复减速。
- `cc-soul` 保持 PDC 遗物、死亡结算、灵魂台账的单一状态源；SoulGraves 只有在闸门通过后才考虑提取生命周期骨架。
- `cc-martial` 保持现有技能数据模型；Fabled、EzSkills 仅作为可选源码参考，不直接替换已实现的技能系统。

**出口条件**：六个既有模块在 1.21.1 测试服加载，命令和既有事件冒烟通过。

### M2：cc-season

- 日历：跟随锚世界、真实时间、睡觉推进三模式；持久化 `data/calendar.yml`。
- 功能：季节天气、HUD、季节作物、温室、动物迁徙、村民季节类型、季节时钟、指南书和 PAPI。
- 视觉：ProtocolLib 可选适配器执行逐玩家 biome palette 发包重映射；不修改真实世界，无 ProtocolLib 时只关闭视觉。
- 事件：`season.changed`、`season.day_changed`、`season.special_day`，均先登记后编码。
- 与 `cc-cozy`、`cc-events` 仅通过事件通信，不产生编译期依赖。

**出口条件**：三种推进模式、换季事件、特别日子名单、ProtocolLib 缺失降级和 `/ccseason` 命令完成冒烟。

### M3：cc-adventure

- 自研地城和远征实例状态机，确保队伍、区域、实体、战利品和任务互不串场。
- SinceDungeon 仅在闸门通过后提取实例骨架；否则只参考。
- WorldGuard / DungeonGates 作为可选区域适配，不作为核心状态源。
- MythicMobs 作为 Boss 技能托管适配器，缺失时走原版技能事件。
- 完成 `adventure.*` 事件并联动 `cc-martial`。

**出口条件**：每队独立、远征流程、Boss 结算和卸载清理冒烟通过。

### M4：cc-quest

- 自研章节状态机、旅途手册 GUI、任务进度持久化、歌词和章节演出。
- PikaMug/Quests、BetonQuest、NotQuests 不嵌入源码；最多选择一个作为可选适配器，默认不阻塞自有任务系统。
- 任务目标通过适配器表接入 Bukkit 事件或 ChiliCraft 事件，奖励由模块自有逻辑结算。

**出口条件**：章节解锁、任务状态迁移、领奖防重、事件订阅和 GUI 关闭清理完整。

### M5：cc-cozy、cc-events、cc-economy

- `cc-cozy`：自研料理图鉴、家园评分、NPC 好感和宠物；Citizens 只做可选 NPC 适配。
- `cc-events`：自研单调度器、世界事件和氛围演出，订阅其他模块事件；不直接依赖季节内部类。
- `cc-economy`：自研职业、灵魂货币和物品流转；Vault 只做 Economy 适配；QuickShop、ChestShop、CrossTrade、EzAuction 只做可选联动。
- AuraSkills 不作为默认成长系统，避免与 ChiliCraft 四轴进度重复。

**出口条件**：料理、好感、世界事件、市场交易、职业冷却、Vault 缺失降级和事件记账冒烟通过。

### M6：cc-street 与全量联调

- 方街世界、安全区、设施、集市、游乐园和纪念品。
- 复用 `cc-season` 的季节事件、`cc-events` 的演出、`cc-economy` 的交易、`cc-cozy` 的料理。
- 发布并验证 `street.*`、`park.*` 事件。
- 完成 12 模块全量装载、任意软依赖删除、配置升级和事件链冒烟。

**出口条件**：全量模块可独立装载，跨模块事件链不串场，插件关闭和玩家退出后任务、实体、缓存和临时世界全部清理。

## 13. 依赖关系与整合禁区

```text
cc-core
├── cc-survival
├── cc-demon ──(可选) MythicMobs
├── cc-soul
├── cc-martial
├── cc-adventure ──(可选) WorldGuard / MythicMobs
├── cc-season ──(可选) ProtocolLib / PlaceholderAPI
├── cc-quest
├── cc-cozy ──(可选) Citizens / PlaceholderAPI
├── cc-events
├── cc-economy ──(可选) Vault / QuickShop / ChestShop
└── cc-street
```

- 不允许附属模块直连数据库；持久化走 RowStore，物品级历史走 Paper PDC。
- 不允许 GPL、AGPL、LGPL、闭源或无许可证源码进入 ChiliCraft 自有包。
- 不允许同时启用两个同职责状态源，例如外部体温与 `cc-survival`、外部职业与 `cc-economy`、外部任务主线与 `cc-quest`。
- 不允许把第三方 jar 复制到 `libs`、`shadowJar` 或最终 ChiliCraft 发布包。
- 不允许用外部插件的内部类代替公开 API；适配器检测失败必须静默降级并给出可读日志。

## 14. 里程碑验收清单

### 构建

- `gradlew.bat build` 或 `--offline` 成功。
- 六个既有模块和六个新增模块的依赖、资源、plugin.yml 一致。
- 生成物不包含外部插件 jar 和构建目录。

### 静态审计

- 事件已在 `events-protocol.md` 登记后才编码。
- 异步任务不触碰世界、实体、物品栏、计分板和 Bukkit 状态。
- 长生命周期 Map 使用 UUID，不使用 Player 对象。
- 任务句柄可取消，插件关闭、比赛结束、玩家退出和世界卸载均有清理路径。
- 配置键有默认值，错误值有回退和日志。
- 每个软依赖有检测、适配和缺失降级路径。
- 多竞技场、多队伍、多世界的聊天、可见性、计分板、实体和掉落相互隔离。

### 测试服冒烟

- 12 个模块全量加载无 ERROR。
- 逐个移除 ProtocolLib、MythicMobs、Citizens、Vault、PlaceholderAPI、WorldGuard 后，核心和自有玩法仍能启动。
- 命令发送者类型检查、权限、Tab 补全和非法参数反馈正常。
- 代表性事件链可走通：季节翻日 → 世界事件、地城通关 → 任务推进、市场交易 → 灵魂台账、街区进入 → 安全区联动。
- 重载、玩家退出、死亡、比赛结束和插件禁用后无重复任务、残留实体、残留观战状态或跨模块广播。

## 15. 风险与回退

| 风险 | 回退 |
|---|---|
| 1.21.1 API 适配量超出预期 | 先保证既有六模块构建，逐模块修复；新模块不阻塞版本基线 |
| MIT/Apache 候选许可证或源码不完整 | 降级为参考或软依赖，使用 ChiliCraft 自研边界 |
| ProtocolLib 1.21.1 发包结构不稳定 | 关闭 biome 视觉，保留日历、天气、HUD 和农业功能 |
| 外部插件 API 漂移 | 适配器隔离版本差异，缺失时走原版实现 |
| 多模块事件语义冲突 | 以 `events-protocol.md` 为唯一契约，先登记、再编码、再联调 |
| 资源包或模型依赖突破零资产红线 | 立即排除候选，改用原版物品、方块、生物和 GUI |

## 16. 文档与发布物

每个里程碑必须同步：

- `settings.gradle.kts`、README 模块表、`architecture.md`；
- `events-protocol.md` 新增事件及字段；
- `performance.md` 新增周期任务和发包性能口径；
- 源码改造候选的 LICENSE / NOTICE 记录；
- 第三方部署清单（插件名、版本、是否必须、缺失降级行为）；
- ChiliCraft 自有模块的配置迁移说明和更新说明。

最终发布物仅包含 ChiliCraft 自有模块 jar、配置示例、文档和第三方部署清单，不包含任何外部插件 jar。
