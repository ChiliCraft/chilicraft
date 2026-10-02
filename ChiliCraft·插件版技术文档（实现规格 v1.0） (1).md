# ChiliCraft·插件版技术文档（实现规格 v1\.1）

**本文回答：**插件版 ChiliCraft 按什么规格实现、按什么顺序开发、按什么标准验收。

- **核心主张：**11 个 jar（cc\-core ＋ 10 附属）的 Gradle 多工程；附属只依赖核心 API，模块间通过事件总线解耦；SQLite 统一存储；零贴图零模型，全部内容用原版机制＋配置实现。

- **读者行动：**开发者按本文第九节顺序实施；主创按各模块验收标准验收；数值与文案以总纲 v4\.0 与《文案手册》为准。

- **边界：**本文定义接口、事件、数据与验收规格；歌词文案与具体数值初值需实测校准。

**v1\.1 变更记录（2026\-09\-27）：**联动 ChiliChill 2026 巡演主题「混入人类计划II：方街」与「方街游乐园」——新增第 10 个附属 cc\-street（方街独立世界＋方街游乐园二期园区）、巡演主题映射表（歌曲/舞台元素→机制）、cc\-quest 终章后篇「欢迎来到方街」；事件表＋4、数据表＋4、配置示例＋cc\-street 段、开发里程碑＋第六步；cc\-core 零改动，方街参数由 cc\-street 自管。

# 实现范围与总体架构

本工程实现 v5\.0–v5\.3 全部设计：双玩法（闯世界/过日子）、六章节主线任务书＋终章后篇「欢迎来到方街」、歌词机制、零资产落地、模块化插件组。2026 巡演主题「方街/方街游乐园」由独立附属 cc\-street 承载（见第十个附属模块规格）。平台锁定 Paper 1\.20\.4 / 1\.21\.x，不引入贴图/模型资产，但允许与主流老牌插件软依赖联动（见“外部插件联动策略”），插件缺失时自动降级到原版实现。

|项|选型|说明|
|---|---|---|
|服务端|Paper 1\.20\.4 / 1\.21\.x|与总纲一致，锁定 1\.20\.4 起步|
|语言/构建|Java 17 / 21 · Gradle ＋ Shadow|多模块工程，各模块独立 jar|
|存储|SQLite（HikariCP），预留 MySQL|核心统一建库，附属不得自建库|
|外部插件|可选·软依赖（推荐清单见第 4 节）|联动成熟插件补能力；贴图/模型零引入——生物原版模型＋改名、物品原版图标|
|权限（软依赖）|LuckPerms|未装时功能降级，不阻塞运行|

**仓库结构：**一个 Gradle 根工程包含 cc\-core 与 10 个附属子工程；cc\-core 输出必备 jar，附属输出可选 jar。每个 jar 即一个可独立更新/开关的模块。

# cc\-core 主核心规格

主核心不装玩法，只装骨架。它是唯一"更新需谨慎"的模块，因此高频变化的内容全部下沉到附属。核心按六大子系统实现。

|子系统|职责|实现要点|
|---|---|---|
|玩家档案|PlayerProfile：mode、境界、灵魂、职业、成就、任务进度索引|UUID 主键，内存缓存＋异步落库；切换模式 24h 冷却；冒险成长封存、养老成长保留（v5\.1）|
|per\-player 参数|按 mode 返回饱食衰减、掉落、负重、刷怪倍率|统一参数表，附属经 API 查询；家园区半径 128 格内刷怪 ×0\.1、无饿魔|
|ChiliCraftAPI|附属唯一入口|版本化接口（见下）；核心升级保留兼容层|
|事件总线|跨模块通信|字符串事件名＋EventData；发布/订阅解耦；异步分发的慢订阅者自动降级同步|
|数据库|全部表结构（见第五节）|HikariCP 连接池；启动建表＋轻量迁移；禁止附属直连|
|指令与 GUI 框架|/cc 家族指令、原版箱子 GUI 工具|GUI 用 InventoryHolder 绑定，防拖拽越界|

## API 契约（骨架）

```java
public interface ChiliCraftAPI {
    PlayerProfile getProfile(UUID playerId);
    GameMode getMode(UUID playerId);                 // ADVENTURE / COZY
    void setMode(UUID playerId, GameMode mode);      // 触发 24h 冷却校验
    int getSoul(UUID playerId);
    void addSoul(UUID playerId, int amount, String reason);
    boolean spendSoul(UUID playerId, int amount, String reason);
    void subscribe(String eventName, EventHandler handler);
    void publish(String eventName, EventData data);
    double getParam(UUID playerId, ParamKey key);    // 饱食衰减/掉落/负重倍率等
    HomeZone getHomeZone();
    ModuleConfig getModuleConfig(String moduleId);   // 附属配置统一读取
}
```

**身份约定：**事件名统一前缀「模块名\.事件名」，如 demon\.mob\_killed；EventData 至少含 playerId、target、amount 三个字段，扩展字段用键值对。API 从 1\.0 起向后兼容，破坏性变更必须升主版本并保留旧方法兼容层。

# 事件总线与跨模块协议

# 外部插件联动策略

所有联动均为**软依赖**：启动时检测插件，存在则启用对应能力，缺失则自动降级，不影响任何模块加载与运行。主创只需在 cc\-core/config\.yml 的 integrations 段开关，无需改代码；联动同样不引入贴图/模型资产——生物用原版模型＋改名，物品用原版图标。

|插件|用途|联动模块|缺失时降级|
|---|---|---|---|
|LuckPerms|权限与组|cc\-core（软依赖）|无权限插件可运行|
|PlaceholderAPI|HUD/告示牌/聊天占位符（温度、负重、境界、灵魂）|cc\-core 提供扩展|actionbar 原生显示|
|Vault|经济兼容层，灵魂货币注册|cc\-economy|内部货币直接结算|
|MythicMobs|怪物技能/行为、Boss 技能定义|cc\-demon、cc\-adventure|原版生物＋属性修改器|
|Citizens|NPC 实体化（说书人、武师父、好感 NPC）|cc\-quest、cc\-cozy、cc\-events|村民改名＋对话 GUI|
|EssentialsX|home/warp/tpa 基础指令|cc\-core（可选）|/cc 内置传送|
|WorldGuard|家园区/擂台区域保护|cc\-core、cc\-martial|自定义区域检查|
|CoreProtect|方块记录与防破坏回滚|全服运维|建议部署，非联动必需|
|QuickShop / ChestShop|玩家摊位交易|cc\-economy|自研箱子商店|

```yaml
integrations:
  placeholders: true   # PlaceholderAPI：HUD/告示牌占位符
  vault: true          # Vault：灵魂货币注册（供商店插件收灵魂）
  mythicmobs: true     # MythicMobs：怪物/Boss 技能与行为
  citizens: true       # Citizens：NPC 实体化
```

这是模块间唯一的通信通道。下表为首期必须实现的事件（cc\-street 相关事件在第六步实现）；实现新增事件时遵循同一前缀规范。

|事件名|发布者|主要订阅者|载荷|
|---|---|---|---|
|demon\.mob\_killed|cc\-demon|cc\-quest、cc\-soul、cc\-events|玩家、怪物类型、数量|
|demon\.hunger\_triggered|cc\-demon|cc\-events（氛围演出）|玩家、饱食度|
|martial\.realm\_up|cc\-martial|cc\-quest（章节门槛）|玩家、境界|
|martial\.arena\_win|cc\-martial|cc\-quest、cc\-events|玩家、赛制|
|adventure\.dungeon\_clear|cc\-adventure|cc\-quest、cc\-soul、cc\-events|玩家、地城 ID|
|adventure\.boss\_killed|cc\-adventure|cc\-quest、cc\-soul、cc\-events|玩家、Boss ID|
|adventure\.expedition\_result|cc\-adventure|cc\-economy、cc\-quest|玩家、层数、结果|
|soul\.player\_died|cc\-soul|cc\-quest、cc\-demon、cc\-events|玩家、死因、坐标|
|soul\.relic\_gained|cc\-soul|cc\-quest|玩家、遗物 ID|
|soul\.relic\_funeral|cc\-soul|cc\-quest、cc\-events|玩家、遗物 ID|
|quest\.chapter\_cleared|cc\-quest|cc\-events、cc\-core|玩家、章节|
|quest\.quest\_completed|cc\-quest|cc\-events、cc\-core|玩家、任务 ID|
|events\.world\_event\_start|cc\-events|全部模块|事件 ID、时长|
|economy\.market\_trade|cc\-economy|cc\-core（灵魂结算）|玩家、金额、类型|
|street\.world\_enter|cc\-street|cc\-demon、cc\-survival、cc\-events|玩家、进入/离开|
|street\.market\_trade|cc\-street|cc\-economy、cc\-core|玩家、摊位、金额|
|park\.game\_clear|cc\-street|cc\-quest、cc\-soul、cc\-events|玩家、项目 ID、成绩|
|street\.souvenir\_complete|cc\-street|cc\-quest、cc\-events|玩家、纪念品集|

**禁止规则：**附属之间不得直接引用类、不得直接读写他模块数据；一切跨模块数据经事件或核心 API。删除任一模块后，其余模块不得因缺失引用而加载失败（软依赖＋反射注册）。

# 十个附属模块实现规格

每个模块一张规格表：职责、关键对象与数值（数值为总纲已裁决值或设计初值）、对外事件、配置与验收。数值明细不重抄总纲，实现时以总纲对应章节为准。

## cc\-survival · 生存压力（共享）

|项|规格|验收|
|---|---|---|
|职责|饥饿、温度、负重、耐久四项压力，按玩家 mode 返回不同强度（cozy 模式 ×0\.5 建议值）|—|
|关键数值|饥饿衰减 ×1\.5、夜间 ×2\.0；体温 0–100（90–100 燥热、40–59 微冷、20–39 寒冷、0–19 失温）；负重上限 200 kg、70% 减速 20%、100% 无法跳跃；耐久消耗 ×1\.5|cozy 模式压力明显低于 adventure|
|性能要求|温度用 1 秒周期任务，禁止 PlayerMoveEvent；负重物品变动时计算|30 人 TPS 稳定 20|
|配置|config\.yml（全部数值键值化＋中文注释）|改数字后 /cc reload 生效|

## cc\-demon · 饿魔/饿意（冒险线）

|项|规格|验收|
|---|---|---|
|职责|夜晚饿魔刷新、饿意追踪、击杀掉落；默认原版生物改属性＋改名（小饿魔=僵尸系、猎手=骷髅系、母体=铁傀儡改属性）；安装 MythicMobs 时怪物技能与行为由 MM 接管（仍用原版模型）|夜晚有威胁、白天消失|
|关键数值|小饿魔 12 血/4 攻、成群 3–6；猎手 24/7 无视距离追踪；母体 200/12 每 10 秒召唤；饱食 \<6 时追踪半径 48 格、移速 \+30%；掉落饿魔之牙、饱腹之魂（回 100% 饱食）|越饿越危险可复现|
|事件|发布 demon\.mob\_killed、demon\.hunger\_triggered|cc\-quest 能收到击杀计数|
|性能要求|禁止每 tick 遍历玩家找目标；用原版目标选择器＋属性修改，区块/全服刷怪限额|无 TPS 尖峰|

## cc\-martial · 武学/擂台（冒险线）

|项|规格|验收|
|---|---|---|
|职责|6 层武学境界、5 流派（拙劲/绣花/铜钱/草柄/素青）、每流派 4 主动＋2 被动技能、45 词缀、每日擂台|—|
|关键数值|境界解锁：拙劲=击杀 50、素青纱=通关 1 地城、铜钱挂=击杀 1 Boss、绣花蹄=完成 1 擂台、破天下=击败世界 Boss；技能槽 2→5；擂台每天 20:00/22:00、统一铁套铁剑、冠军 5,000 灵魂；巡演联动：败者发「下等马」安慰礼包（初值 100 灵魂）、冠军触发「演」夺冠演出|境界提升有可感知收益|
|词缀实现|数据驱动：45 条词缀定义在 skills\.yml，效果用事件钩子结算（攻击/受击/饱食/水下等），不做物品贴图|词缀改名即可见，效果可复现|
|事件|发布 martial\.realm\_up、martial\.arena\_win|cc\-quest 章节门槛联动|

## cc\-adventure · 地城/远征/Boss（冒险线）

|项|规格|验收|
|---|---|---|
|职责|10 个地城（实例化副本）、远征（5 层随机房间）、5 个世界 Boss（事件触发）|—|
|地城实现|地城用原版建筑模板＋指令事件驱动；实例化为每队独立副本（数据隔离）；机制（守锅/献祭/承重/水下/双界）用事件与状态机实现|每队独立不串场|
|远征实现|9 种房间按权重随机；祝福/诅咒（每 2 祝福强制 1 诅咒）；远征独立世界按需加载；房间按需生成|远征 5 层可完整走通|
|Boss 实现|默认原版生物改属性＋技能事件（数值同左，饿魔母体·真身 3,000 血、水做之影 2,500、台风之眼 2,800、幻形鹦鹉 2,000、梦魇 4,000）；触发条件按总纲（连续夜间死亡/雨天深海/台风/随机/半梦界深层）|首杀奖励发放正确|
|事件|发布 adventure\.dungeon\_clear、adventure\.boss\_killed、adventure\.expedition\_result|cc\-quest 能推进章节|

## cc\-quest · 任务书/主线剧情（冒险线）

|项|规格|验收|
|---|---|---|
|职责|6 章节主线（欢迎来到地球→深夜食堂→不安的灵魂→水做的世界→半梦半醒→混入人类计划）＋终章后篇「欢迎来到方街」（通关第六章解锁，传送方街世界推进 3–5 个收尾任务）、20 个主线任务、剧情演出与歌词结算|主线可完整走通|
|任务书物品|原版成书改名「旅途手册」，右键打开原版箱子 GUI（任务追踪/剧情回顾/奖励领取）|零贴图零模型|
|任务系统|8 种类型：击杀/收集/合成/探索/对话/生存/挑战/仪式；状态机：未解锁→进行中→可提交→已领取；进度存 cc\_quest\_progress|任务进度跨线/跨天不丢|
|歌词机制|任务完成 actionbar 弹歌词；章节推进 title＋天气/粒子氛围；文案统一读 messages\.yml|文案可整包替换|
|内容文件|chapters\.yml 填表式定义章节与任务（见第六节示例）|主创不写代码可加任务|
|事件|订阅 demon/martial/adventure/soul 事件推进任务；发布 quest\.chapter\_cleared、quest\.quest\_completed|章节门槛联动境界|

## cc\-soul · 灵魂/死亡（共享）

|项|规格|验收|
|---|---|---|
|职责|死亡结算、临终遗言、灵魂收容所、12 遗物、葬礼仪式、物品流转史|—|
|关键数值|死亡掉 50% 物品＋50% 灵魂；灵魂碎片 24h 过期可拾取；葬礼消耗 10,000 灵魂＋站桩 60 秒，篝火 \+10%、月亮井 \+25%、死亡点 \+50% 并留墓碑；12 遗物全服唯一、耐久耗尽进葬礼；巡演联动：灵魂收容所场景以《不安灵魂收容所》命名、葬礼可选祭品「旧眼镜」（致敬《眼镜的葬礼》，站桩时长减半·初值）|死亡惩罚可感知但不劝退|
|遗物实现|原版物品＋PDC 打 ID 与流转史；效果用事件钩子；不可重铸保唯一|遗物流转史可查询|
|事件|发布 soul\.player\_died、soul\.relic\_gained、soul\.relic\_funeral|cc\-quest 第三章联动|

## cc\-cozy · 养老/过日子（养老线）

|项|规格|验收|
|---|---|---|
|职责|农业等级、料理图鉴、家园评分、NPC 好感、宠物、社区（廉价商店/躺平日）；方街为养老线主社交场景（餐厅联动料理图鉴、KTV/Livehouse 联动好感）|cozy 模式玩家有独立成长轴|
|成长轴|四轴：农业等级（种收积分）、料理图鉴（食谱收集数）、家园评分（建筑/装饰评分）、NPC 好感（礼物/对话）；每日节奏：起床→照料→料理→社交→躺平日|四轴各自可成长、可查询|
|实现|料理=原版合成表＋数据驱动食谱（recipes\.yml）；NPC=默认村民改名＋对话 GUI，安装 Citizens 时升级为可移动 NPC＋气泡对话；宠物=原版可驯服生物＋改名|零贴图零模型|
|事件|发布 cozy\.farm\_level\_up、cozy\.recipe\_unlocked、cozy\.npc\_affinity\_up|cc\-events 可做社区联动|

## cc\-events · 情绪事件（共享）

|项|规格|验收|
|---|---|---|
|职责|9 个世界事件（混入人类计划赛季·主题场景延伸至方街世界、七月群星岛屿、擂台大会、梦境入侵、躺平日、流星雨、新春游园、深夜食堂限时、游乐园开业季·cc\-street 二期开放窗口）、说书人 NPC、廉价商店|—|
|实现|事件调度器（频率/时长/内容在 events\.yml）；说书人=村民改名＋对话 GUI（承接剧情补叙）；廉价商店=低价回收/随机出售（五块钱的伞）|事件按调度触发、可手动开关|
|歌词氛围|订阅其他模块事件做氛围演出（雨、暮色、粒子、音效）|演出可整包替换|

## cc\-economy · 经济/市场（共享）

|项|规格|验收|
|---|---|---|
|职责|四层市场（NPC 商店/玩家摊位/拍卖行 48h/以物易物）、6 职业、物品流转（五块钱的伞）；玩家摊位可联动 QuickShop/ChestShop（收灵魂）|—|
|灵魂结算|灵魂货币注册 Vault（可选），商店/经济插件可直接收灵魂；金额走 cc\-core 货币 API（统一事务＋日志）|交易有完整账目|
|职业|农夫/矿工/铁匠/厨师/猎人/药师，切换冷却 24h；加成用事件钩子结算|职业加成可复现|
|物品流转|PDC 记录历任持有者；拾取有故事物品得额外灵魂|流转史可查|

## cc\-street · 方街与方街游乐园（2026 巡演主题·共享）

|项|规格|验收|
|---|---|---|
|职责|承载 2026 巡演主题「方街」与「方街游乐园」：独立街区世界＋二期游乐园园区、街区设施社交、厚嘴唇集市、城市限定纪念品|方街世界可自由闲逛、游乐园项目可通关|
|方街世界|world_street 独立世界，模板随 jar 分发或内置蓝图生成（实现取其一）；首次启动释放并加载；全境安全区（冒险模式、饱食不衰减、无饿魔、无死亡掉落）；/street 指令传送；进入/离开发布 street\.world\_enter，cc\-demon/cc\-survival 订阅后停刷怪、压力减半（cc\-core 参数表零改动）|进入方街 TPS 无衰减、无生存压力|
|街区设施|居委会留言板（GUI 留言/翻阅）、纪念品店（城市限定方巾/排号卡）、KTV 点歌 GUI（唱歌提升好感）、Livehouse 定时演出（歌词氛围演出复用 cc\-events）、旅馆床位租赁（离线挂机点）、餐厅（联动 cc\-cozy 料理图鉴）、邮筒（离线信件）、公告栏；街角公交站牌/信号灯（鸽鸽彩蛋）/斑马线/凸面镜/理发店等街道彩蛋|设施全部可用、彩蛋可触发|
|厚嘴唇集市|周末开市的无料集市：摊位复用 cc\-economy 摊位逻辑、交易走 street\.market\_trade 记账；含扭蛋机、观众信箱|集市周末开市、记账完整|
|游乐园二期|方街东侧预留空地按结构模板分批生成；6 个项目：矿车过山车（计圈计时）、摩天轮（每日登轮 buff）、雪球套圈、鬼屋（复用 cc\-demon 属性变体·白天开放）、弩靶射击场、时光盲盒扭蛋机（随机歌曲彩蛋）；排号卡防拥堵；门票 10 灵魂/项目·周末免费（初值）；通关发布 park\.game\_clear|6 项目可通关、成绩入库|
|限定纪念品|PDC 唯一 ID＋流转史（复用 cc\-soul 遗物结构）＋城市标签；集齐一套发布 street\.souvenir\_complete，奖励由 cc\-quest 联动发放|纪念品唯一、可流转、集齐有奖|
|事件|发布 street\.world\_enter、street\.market\_trade、park\.game\_clear、street\.souvenir\_complete|订阅方按事件表联动|
|配置|street\.yml（世界与安全区＋设施坐标）、park\.yml（项目数值＋奖励池）、souvenirs\.yml（纪念品定义）|改数字 /cc reload 生效|

## 巡演主题映射表（歌曲/舞台元素→机制）

零贴图零模型约束不变：歌曲与舞台元素全部通过命名、GUI、氛围演出与事件机制落地。

|曲目/舞台元素|游戏机制|状态|落点模块|
|---|---|---|---|
|《时光盲盒》|扭蛋机随机歌曲彩蛋（游乐园项目之一）|新增|cc\-street|
|《眼镜的葬礼》|葬礼可选祭品「旧眼镜」（站桩时长减半·初值）|新增（命名）|cc\-soul|
|《不安灵魂收容所》|灵魂收容所场景命名|新增（命名）|cc\-soul|
|《下等马》|擂台败者安慰礼包|新增（命名）|cc\-martial|
|《演》|擂台冠军夺冠演出|新增（演出）|cc\-martial|
|《橙子汽水》|夏日限定饮品（廉价商店/方街餐厅出售）|新增|cc\-economy、cc\-street|
|《五块钱的伞》|廉价商店低价回收/随机出售|已有|cc\-events|
|《双人船》|双人船绑定（cc\_boat\_pairs）|已有|cc\-core|
|《新春游园》|新春游园世界事件|已有|cc\-events|
|《想和你迎着台风去看海》|台风世界 Boss 与雨天氛围|已有|cc\-adventure、cc\-events|
|《我的悲伤是水做的》|水做之影 Boss／雨天氛围|已有|cc\-adventure|
|《山遥路远》|远征玩法命名彩蛋（房间名/祝福词）|新增（命名）|cc\-adventure|
|《衡山路宛平路》|方街街道命名彩蛋（路牌）|新增（命名）|cc\-street|
|《飞鸟说》|信号灯上的鸽鸽彩蛋|新增（命名）|cc\-street|
|《灰色鹦鹉》|幻形鹦鹉 Boss|已有|cc\-adventure|
|《别动我头发》|理发店彩蛋（发型/帽子互动）|新增|cc\-street|
|《让风告诉你》|邮筒离线信件（方街寄出）|已有＋扩展|cc\-street、cc\-soul|
|厚嘴唇集市|周末开市无料集市（摊位/扭蛋机/观众信箱）|新增|cc\-street|
|街角站台/信号灯/斑马线/凸面镜|街道彩蛋布景（原版方块＋改名）|新增|cc\-street|

# 数据模型

全部表由 cc\-core 管理（HikariCP＋启动建表＋轻量迁移）。以下为首期核心表；完整字段与索引在实现时补齐，口径以总纲第十九章为准。

|表|关键字段|
|---|---|
|cc\_players|player\_id\(PK\)、mode、martial\_realm、profession、soul、season\_points、home\_x/y/z、mode\_switch\_at、created\_at、updated\_at|
|cc\_quest\_progress|player\_id、chapter\_id、quest\_id、state、progress、completed\_at（联合主键）|
|cc\_death\_records|id\(PK\)、player\_id、cause、x、y、z、died\_at|
|cc\_soul\_fragments|id\(PK\)、owner\_id、x、y、z、amount、expires\_at|
|cc\_relics|id\(PK\)、relic\_id、owner\_id、durability、history\_json、state|
|cc\_achievements|player\_id、achievement\_id、unlocked\_at（联合主键）|
|cc\_skills|player\_id、skill\_id、level、xp（联合主键）|
|cc\_expedition\_runs|id\(PK\)、team\_json、deepest\_layer、result、ended\_at|
|cc\_auctions|id\(PK\)、seller\_id、item\_data、price、ends\_at|
|cc\_boat\_pairs|pair\_id\(PK\)、player\_a、player\_b、bound\_at|
|cc\_discovered\_map|player\_id、chunk\_x、chunk\_z、flags（联合主键）|
|cc\_street\_memories|id\(PK\)、player\_id、content、created\_at（居委会留言）|
|cc\_mail\_letters|id\(PK\)、from\_id、to\_id、content、read、created\_at（邮筒信件）|
|cc\_park\_records|player\_id、game\_id、best\_score、clears、last\_played\_at（联合主键）|
|cc\_souvenirs|id\(PK\)、souvenir\_id、owner\_id、city\_tag、history\_json、state|

**物品与区块数据：**自定义物品 ID、词缀、遗物、流转史、记忆碎片用 PDC（PersistentDataContainer）存储，禁止手拼 NBT。

# 配置规范与内容文件

延续"配置尽量简单"约束：每个模块在 plugins/cc\-xxx/ 下自带 config\.yml（键值＋中文注释，100–200 行）；内容文件（掉落表、食谱、章节任务、事件、文案）随模块分发。改数字 /cc reload 生效；换内容替换 jar 重启加载。

```yaml
# ChiliCraft 主核心配置
mode:
  default: cozy          # 新玩家默认模式：cozy / adventure
  switch-cooldown-hours: 24
home-zone:
  radius: 128            # 家园区半径（格）
  mob-multiplier: 0.1    # 家园区刷怪倍率
  demon-enabled: false   # 家园区不刷饿魔
economy:
  soul-name: "灵魂"
  soul-loss-pct: 50      # 死亡灵魂保留比例（配合 cc-soul）
```

```yaml
chapter-1:
  name: 欢迎来到地球
  unlock: "mode=adventure"
  quests:
    q1-1:
      type: craft
      target: stone_pickaxe
      amount: 1
      reward: "灵魂 50 · 铁锭 2"
      lyric: "你好，地球。请多指教。"
    q1-4:
      type: talk
      npc: 武师父
      reward: "武学面板解锁"
      lyric: "三分拙劲破天下。"
```

```yaml
# cc-street 方街配置
street:
  world-name: world_street   # 独立世界
  safe-zone: true            # 安全区：冒险模式·饱食不衰减·无饿魔·无死亡掉落
  market-weekend-only: true  # 厚嘴唇集市仅周末开市
park:
  enabled: false             # 二期开关：游乐园开业季事件期间置 true
  ticket-soul: 10            # 门票（灵魂），周末免费
  games:
    coaster: { lap-seconds: 90, reward: "灵魂 30" }   # 矿车过山车
    hoops: { attempts: 5, hits-to-clear: 3 }          # 雪球套圈
souvenirs:
  city-tag: "本服限定"        # 限定纪念品城市标签
```

# 更新发布与兼容策略

|操作|步骤|适用范围|
|---|---|---|
|① 调数值|改 config\.yml → /cc reload|平衡调整、事件开关|
|② 换内容|替换 cc\-xxx\.jar → 重启服务器|章节、食谱、掉落、词缀等玩法内容|
|③ 开关玩法|放 jar＝开 / 删 jar＝关|冒险线、养老线整体开关|

**发布物：**每个模块每次更新发布两个文件——cc\-xxx\-版本\.jar 与《更新说明\.md》（写明新增配置键、是否需删旧配置、新增功能）。

**兼容策略：**cc\-core 的 API 版本化（1\.0 起向后兼容）；每个附属在 plugin\.yml 声明 compatible\-core 版本范围；核心升级保留旧 API 兼容层，不允许强制同步升级全部附属。配置合并：升级时保留已存配置，新键按默认值补齐。

# 性能与安全红线

以下为总纲裁定的实现红线，评审与 QA 逐条检查，违反即打回。

|性能红线（禁止）|正确做法|
|---|---|
|PlayerMoveEvent 做温度/负重|1 秒周期任务|
|每 tick 遍历玩家找饿魔目标|原版目标选择器＋区块限额|
|梦界方块全量实时同步（半梦界 P2 若做）|差异表＋按需同步|
|无上限刷怪|区块限额＋全服限额|
|同步写数据库|异步＋批量|
|远征房间一次性全生成|按需加载|
|游乐园设施全实体实时模拟|原版方块/矿车/漏斗机制＋事件判定|
|每 tick 刷新 HUD|1 秒周期|
|getTitle\(\) 比对 GUI|InventoryHolder|
|手拼 NBT|PersistentDataContainer|
|自研 tick 寻路|原版目标选择器/属性修改|

**安全要点：**SQL 全 PreparedStatement；经济统一入口＋事务＋日志；GUI 拦截 InventoryDragEvent；物品唯一 ID 校验防复制；玩家输入过滤颜色代码与长度。

# 开发顺序与验收里程碑

按依赖顺序实施，每步有独立验收，先让"能玩"再让"好玩"。

|阶段|模块|验收标准|
|---|---|---|
|第一步|cc\-core（档案/mode/参数/API/事件总线/数据库/家园区/货币/指令/GUI）|10 人并发 TPS 稳定 20；mode 切换有 24h 冷却；家园区参数生效|
|第二步|cc\-survival ＋ cc\-demon|饥饿/温度/负重/耐久可感知；夜晚饿魔刷新可击杀、掉落入袋；cozy 压力减半|
|第三步|cc\-martial ＋ cc\-soul ＋ cc\-adventure|境界随击杀/地城/Boss 提升；死亡掉物与灵魂保留正确；深夜食堂地城可通关|
|第四步|cc\-quest|任务书 GUI 可用；6 章节主线完整走通；歌词结算触发；章节门槛联动境界|
|第五步|cc\-cozy ＋ cc\-events ＋ cc\-economy|双玩法全量；世界事件按调度触发；市场交易记账完整|
|第六步（巡演主题）|cc\-street（方街世界＋游乐园二期）|方街安全区规则生效；街区设施可用；6 个游乐园项目可通关；集市记账完整；限定纪念品唯一可流转；巡演映射彩蛋可触发|

**容量建议：**每步预留接线、数据验证、QA 与修复时间；内容填充（任务、食谱、事件、文案）由主创按配置规范完成，不占用开发产能；联动成熟插件后（见第 4 节），怪物技能/NPC/商店等自研量显著减少。

# 待确认项与依赖

|事项|状态|影响|
|---|---|---|
|《ChiliCraft\-文案手册》提供后替换全部歌词/文案|待提供|文案可配置化，不阻塞开发|
|全部数值初值（奖励、权重、概率）|设计初值|第二步起实测校准|
|半梦界（双界维度）是否首期实现|建议延后 P2|第五章可先用占位机制|
|世界 Boss 行为增强深度|原版属性＋技能事件|复杂 AI 后续迭代|
|模组版方案|已搁置|本文档为插件版唯一实现规格|
|方街/游乐园官方世界观引用边界（地名、歌词、命名）|参考巡演公开信息设计|歌词整包以《文案手册》为准；对外发布前需复核|

已确认决策（2026\-09\-26）：外部插件联动默认全开——第 4 节推荐清单全部启用，均为软依赖，缺失自动降级；CoreProtect 属运维建议项，由服务器管理员决定。

已确认决策（2026\-09\-27）：方街落位为独立新增街区世界（cc\-street），方街游乐园为方街二期扩展园区；巡演主题按映射表全模块融入；cc\-core 不承载方街玩法。

> （注：部分内容由豆包工作 AI 生成）
