# ChiliCraft 开源插件缝合候选调研报告

> 本报告整合 cc\-core / cc\-survival / cc\-demon / cc\-adventure / cc\-martial / cc\-quest / cc\-soul / cc\-cozy / cc\-events / cc\-economy 共 10 个模块与通用基础件的开源候选调研结果，供主创团队决策参考。
> 
> 

## 一、调研概览

- **调研日期**：2026\-09\-28

- **目标服务器版本**：Paper 1\.20\.4 / 1\.21\.x（含 1\.21\.4\+ Hardfork 后）

- **渠道覆盖**：GitHub、SpigotMC、CurseForge、Modrinth、Hangar（PaperMC 官方），中英文项目均收录

- **候选总数**：约 90 项（含通用基础件 29 项、业务模块约 61 项）

### 许可证合规分级

|合规结论|许可证|处置方式|
|---|---|---|
|可安全缝合|MIT / Apache\-2\.0 / BSD|可直接嵌入代码改造（保留版权声明）|
|仅参考设计|GPL\-3\.0 / LGPL\-2\.1 / LGPL\-3\.0 / AGPL\-3\.0|禁止复制代码入闭源；可软依赖运行、可参考架构|
|不可用 / 风险极高|无 LICENSE / SSPL / All Rights Reserved / 付费闭源|不采用，仅可借鉴 UI 或产品形态|

### 缝合三档定义

- **A 直接软依赖联动**：作为独立 jar / Maven 依赖或可选插件运行，通过 API / 事件联动，不复制其源码。

- **B 嵌入代码改造**：把源码摘进 ChiliCraft 工程 fork 改造后重新发布；要求许可证宽松、零资源资产、体量小而专。

- **C 仅参考设计**：只借鉴架构、接口划分、配置 schema 与数值策略，不复制代码（GPL/LGPL/AGPL 或许可证存疑项目一律按此档）。

### 零资产约束

ChiliCraft 不依赖任何资源包 / 自定义模型 / 材质贴图。候选项目若仅通过 soft\-hook 接入 ItemsAdder / Nexo / Oraxen / HeadDatabase 等才提供额外头颅或自定义物品，标注为"否（仅软挂钩可选）"，原版物品可独立运行。凡强制资源包者一律排除出 B 档。

---

## 二、核心结论速览

### 最值得缝合的 TOP 10（每个模块挑 1 个最优先）

|模块|首选项目|档位|一句话理由|
|---|---|---|---|
|cc\-core（架构蓝本）|[LuckPerms](https://github.com/LuckPerms/LuckPerms)|C 参考|玩家档案内存缓存\+异步落库的业界范本|
|cc\-survival|[DurabilityPlus](https://modrinth.com/plugin/durabilityplusplugin)|B 改造|Apache\-2\.0 原生耐久倍率，正对耐久×1\.5|
|cc\-demon|[VortexMobs](https://github.com/Sauron05/VortexMobs)|B/A|Apache\-2\.0 原版怪挂属性倍率\+精英词缀|
|cc\-adventure|[SinceDungeon](https://github.com/SinceRPG/SinceDungeon)|B 改造|MIT 实例化隔离副本\+随机房骨架|
|cc\-martial|[Fabled](https://github.com/promcteam/fabled)|B 改造|MIT 触发器→条件→机制全数据化技能树|
|cc\-quest|[PikaMug/Quests](https://github.com/PikaMug/Quests)|A/B|MIT 任务目标引擎，自研章节层即可|
|cc\-soul|[SoulGraves](https://github.com/FaultyFunctions/SoulGraves)|B 改造|MIT 死亡→灵魂→拾取→超时爆裂生命周期|
|cc\-cozy|[SimpleDialogue](https://github.com/Kcajpanda/simple_dialogue)|B 改造|MIT YAML 分支对话树，接好感度钩子|
|cc\-events|[Christmas Season](https://www.curseforge.com/minecraft/bukkit-plugins/christmas-season)|B 改造|MIT 季节限时活动完整范例|
|cc\-economy|[EzAuction](https://github.com/ez-plugins/EzAuction)|B/A|MIT GUI 拍卖行，48h 上架可配置|

### 必须自研的子模块清单

- **cc\-core**：字符串事件名 \+ EventData 发布/订阅总线（MC 生态均按事件类分发，无对口库）；ChiliCraftAPI 版本化接口（自有契约）。

- **cc\-survival**：体温 0\-100 四档（ThermoSurvival 无 LICENSE）；负重重量\-速度映射曲线（Inventory Weight/Weight\-RPG 闭源）。

- **cc\-adventure**：祝福/诅咒系统（无专门开源实现）。

- **cc\-martial**：6 层武学境界解锁条件（修真类候选均为 Forge/Fabric 模组或 All Rights Reserved）。

- **cc\-quest**：歌词 actionbar / 章节 title\+天气\+粒子演出；旅途手册成书 GUI 外壳（虽简单但无现成件）。

- **cc\-soul**：12 遗物全服唯一 \+ PDC 流转史；葬礼仪式（10000 灵魂\+站桩 60 秒\+地点加成）；灵魂收容所。

- **cc\-cozy**：家园评分（建筑/装饰自动评分，只有地皮互评插件范式不符）。

- **cc\-events**：歌词氛围演出调度器（订阅模块事件播粒子/音效/天气）。

- **cc\-economy**：6 职业 \+ 24h 切换冷却（Jobs Reborn 为 GPL 且绑闭源 CMILib）；物品 PDC 历任持有者核心写入。

---

## 三、按模块归类候选清单

### 3\.1 cc\-core 主核心

**职责**：插件组主核心，打包指令\+GUI\+异步 DB\+模块生命周期\+消息，承载玩家档案与货币 API。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[lucko/helper](https://github.com/lucko/helper)|GitHub|MIT|维护态（停增 API）|部分兼容（Folia 复核）|是|B|函数式事件/Promise/SQL 全家桶可摘子包|
|[PeachLib](https://hangar.papermc.io/PeachBiscuit174/PeachLib)|Hangar|MIT|业余项目|兼容|是|B|运行时自动拉取 DB 驱动不 shaded|
|[LuckPerms](https://github.com/LuckPerms/LuckPerms)|GitHub|MIT|极活跃（2026\-08，支持 1\.21\.4）|兼容|否|C|玩家档案异步落库架构最佳蓝本|
|[DzusillCore](https://www.spigotmc.org/resources/dzusillcore-commands-•-gui-•-async-db-•-module-framework.136452/)|SpigotMC|需人工复核|活跃（2026\-08）|兼容|是|C|指令\-GUI\-异步DB\-模块生命周期一体对标|
|[KLibrary](https://www.spigotmc.org/resources/klibrary.134990/)|SpigotMC|需人工复核|较新（2026\-05）|兼容|是|C|动作引擎\+条件引擎配置化抽象可借鉴|
|[Lattice](https://modrinth.com/plugin/latticekit)|Modrinth|需人工复核|活跃（2026\-06）|兼容|是（软挂钩可选）|C|多 DB\+HikariCP\+迁移\+独立池管理|
|[QinhCoreLib](https://github.com/JIULIVE/QinhCoreLib)|GitHub|需人工复核|活跃（2026\-06，测 1\.21/26\.x）|兼容|是|C|中文社区同类核心，对照诊断/桥接|

### 3\.2 cc\-survival 生存压力

**职责**：饥饿/温度/负重/耐久四维生存压力，用 1 秒周期任务驱动，禁止 PlayerMoveEvent。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[DurabilityPlus](https://modrinth.com/plugin/durabilityplusplugin)|Modrinth|Apache\-2\.0|2025\-11 起活跃|1\.21–1\.21\.10（1\.20\.4 待复核）|是|B|原生耐久消耗倍率，正对耐久×1\.5|
|[SurvivalMethod](https://github.com/13raur0/SurvivalMethod)|GitHub|MIT|约 1 年未更新|Paper（小版本待复核）|是|B|后台周期任务扣减\+原版 HUD 显示骨架|
|[Hydration](https://hangar.papermc.io/voidspace1x/Hydration)|Hangar|MIT|2026\-07 首发|Paper 26\.x / 1\.21\.x|是|A/B|0\-100 数值\+分档效果与体温同构|
|[ThermoSurvival](https://github.com/parlamentum/ThermoSurvival)|GitHub|无 LICENSE（风险高）|2025\-12|1\.20/1\.20\.6/1\.21|是|C|8 级温度档\+环境因子加权设计可参考|
|[Temperature999](https://modrinth.com/plugin/temperature999)|Modrinth|未公开（不可用）|2026\-07|1\.21\.x|待复核|C/不可用|昼夜温度速率不同，仅产品形态参考|
|[Inventory Weight / Weight\-RPG](https://www.spigotmc.org/resources/70929/)|SpigotMC|闭源（不可缝合代码）|2024\-12 / 2022 停更|1\.16–1\.21|是|C|重量\-速度映射曲线与需求数值一致|

### 3\.3 cc\-demon 饿魔怪物

**职责**：夜晚刷新小饿魔/猎手/母体，饿意追踪玩家，原版生物改属性\+改名，禁止每 tick 遍历找目标。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[VortexMobs](https://github.com/Sauron05/VortexMobs)|GitHub|Apache\-2\.0（LICENSE 待复核）|2026\-05|Paper/Folia/Spigot 1\.16\.5\+|是|B/A|原版怪挂属性倍率\+命名前缀\+精英词缀|
|[EliteMobs](https://github.com/MagmaGuy/EliteMobs)|GitHub|GPL\-3\.0|极活跃（2026\-09）|现代 Paper|可选 ModelEngine（可关闭）|A/C|独立软依赖运行，饱食低追踪玩家设计参考|
|[TheMob](https://github.com/3o3y/TheMob)|GitHub|NOASSERTION（风险高）|2026\-02|Paper/Spigot 1\.21\.10|待复核|C|多阶段 Boss\+生命周期非每 tick 遍历可参考|
|[MythicMobs](https://www.spigotmc.org/resources/mythicmobs.5727/)|SpigotMC|专有/付费（非开源）|持续维护|现代 Paper|可选模型包|A（软依赖）|运行时联动目标，检测存在则委托技能层|

### 3\.4 cc\-adventure 地城/远征/世界 Boss

**职责**：10 地城实例化、远征 5 层随机房、5 世界 Boss 事件触发\+血条 2000\-4000、祝福/诅咒系统。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[SinceDungeon](https://github.com/SinceRPG/SinceDungeon)|GitHub|MIT|2026\-08|Paper/Folia 1\.21\+|倾向是（付费包待复核）|B|实例隔离\+随机中房\+概率房骨架|
|[DungeonGates](https://github.com/evnrca/DungeonGates)|GitHub|MIT|2026\-08|Paper\+WorldGuard（待复核）|依赖 WG 插件（非资源包）|A/B|WorldGuard 区域=地城模板\+门事件驱动|
|[EliteMobs](https://github.com/MagmaGuy/EliteMobs)|GitHub|GPL\-3\.0|极活跃（2026\-09）|现代 Paper|可选 ModelEngine|A/C|世界 Boss 冷却刷新\+血条分级\+技能 power|
|[OhTheDungeon\-Redux](https://github.com/roschreiber/OhTheDungeon-Redux)|GitHub|GPL\-3\.0|2026\-08|Paper（待复核）|是|C|程序化地牢布局/模板拼接算法参考|
|[DungeonRooms](https://github.com/evnrca/DungeonRooms)|GitHub|无 LICENSE（风险高）|2026\-08|1\.21|依赖 WG\+可选 MM|C|房锁\+击杀目标\+进度追踪状态机参考|

> **祝福/诅咒系统**：未发现专门开源实现，建议自研；可参考 SinceDungeon 的概率房/稀有奖励房权重机制。
> 
> 

### 3\.5 cc\-martial 武学/擂台

**职责**：6 层境界、5 流派（每流派 4 主动\+2 被动）、45 词缀数据驱动、每日 20:00/22:00 定时擂台统一铁套铁剑。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[Fabled](https://github.com/promcteam/fabled)|GitHub|MIT|2025\-12 发布/2026\-07 仍更新|Paper 1\.16–1\.21|是|B|触发器→条件→机制全数据化技能注册层|
|[EzSkills](https://github.com/ez-plugins/EzSkills)|GitHub|MIT|2026\-05（2\.0\.2）|Paper 1\.16–26\.1\.x|是|B|SkillDefinitionRegistry 运行时注册技能骨架|
|[DuelPlugin](https://github.com/yangzijian52/DuelPlugin)|GitHub|MIT（需复核 jar）|2026\-07|Paper 26\.2（1\.21\.x）|是|B|排队 1v1\+自动 kit 装备，中文原生|
|[PulseEvents](https://github.com/plugglab/PulseEvents)|GitHub|MIT|2026\-08（v3\.2）|Paper 1\.20–1\.21|是|A|定时调度\+广播\+BossBar\+奖励发放底座|
|[AbilityClass](https://gitlab.com/Saltyy/minecraft-abilityclass-plugin)|GitLab|GPL\-3\.0\-or\-later|2026\-04（v1\.5\.0）|Paper（待复核）|是|C|trigger→cooldown→skill 的 skills\.yml schema 参考|
|[BattleArena](https://modrinth.com/plugin/battlearena)|Modrinth|GPL\-3\.0\-only|约 2 个月前更新|Paper（待复核）|是|C|Arena/Skirmish/Colosseum 多模式架构参考|

> **6 层境界解锁条件**：无合格开源 Paper 插件候选（修真类多为 Forge/Fabric 模组或 All Rights Reserved），建议自研；降级参考 Fabled 的 class 解锁 gate 机制。
> 
> 

### 3\.6 cc\-quest 任务书/主线剧情

**职责**：6 章节 20 主线任务、8 种任务类型、状态机、chapters\.yml 填表、旅途手册成书 GUI、歌词 actionbar\+章节氛围。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[Quests \(PikaMug\)](https://github.com/PikaMug/Quests)|GitHub|MIT（源码；分发包待复核）|2026\-08|Paper 1\.8–1\.21\.11|是|A/B|多目标/奖励 YAML 任务引擎，自研章节层|
|[BetonQuest](https://github.com/BetonQuest/BetonQuest)|GitHub|GPL\-3\.0\-only|极活跃（3 天前更新）|Paper 1\.18–26\.1\.2|是|A/C（需法务确认）|YAML 包结构是 chapters\.yml 最佳模板|
|[QuestTracker](https://github.com/BekoLolek/QuestTracker)|GitHub|需人工复核|2026\-06（v1\.1）|Paper 1\.19–1\.21|是|C|GUI 接任务\+难度分级模式参考|
|[QuestLogs](https://modrinth.com/plugin/quest-logs)|Modrinth|需人工复核|2026\-01|Paper（待复核）|是|C|箱子 GUI 展示任务列表最小参考|

> **旅途手册成书 GUI**（PlayerInteractEvent 监听成书→开 ChestInventory）仅数十行代码，建议自研；**仪式任务与歌词/天气/粒子演出**无直接开源候选，建议自研。推荐路径：A 档软依赖 PikaMug/Quests（MIT）承载任务目标引擎，cc\-quest 自研章节编排\+GUI 外壳\+演出层。
> 
> 

### 3\.7 cc\-soul 灵魂/死亡/遗物

**职责**：死亡掉 50% 物品\+50% 灵魂、临终遗言、灵魂收容所、12 遗物全服唯一、葬礼仪式、灵魂碎片 24h 过期。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[SoulGraves](https://github.com/FaultyFunctions/SoulGraves)|GitHub|MIT|2025\-08（v1\.5\.1）|Paper 1\.20\.6–1\.21|是|B/A|死亡→灵魂实体→拾取→超时爆裂生命周期|
|[GraveStonesPlus](https://github.com/BenCodez/GraveStonesPlus)|GitHub|需人工复核|2026\-04/07 Jenkins|Spigot/Paper 1\.13–1\.21|是|C|墓碑存物品\+死亡点坐标提示参考|
|[Chronicles](https://github.com/juniodevs/Chonicle-Plugin)|GitHub|需人工复核|2026\-01（1\.0\.0）|Paper 1\.20–1\.21|是|C|自动监听事件记录物品流转历史参考|
|[Death\-Penalty](https://hangar.papermc.io/Ikkino/Death-Penalty)|Hangar|需人工复核|2026\-05|Paper（待复核）|是|C|可配置死亡惩罚\+消耗品图腾思路参考|
|[Tracked Items](https://modrinth.com/plugin/trackeditems)|Modrinth|需人工复核|2026\-05|Paper（待复核）|是|C|随流转更新归属 lore，改用 PDC 参考|
|[HereDed](https://modrinth.com/plugin/hereded)|Modrinth|需人工复核|2026\-06|Paper（待复核）|是|C|NORMAL/TOMBSTONE 双死亡模式参考|

> **12 遗物全服唯一\+PDC 流转史、葬礼仪式、灵魂收容所**均为独创玩法，无开源候选，建议自研；技术上用 Paper 原生 PDC 打 UUID\+流转 List。降级选项：A 档软依赖 SoulGraves 处理灵魂拾取与过期，cc\-soul 叠加 50% 掉率\+灵魂经济\+葬礼层。
> 
> 

### 3\.8 cc\-cozy 养老/料理/家园/NPC 好感

**职责**：农业等级、料理图鉴、家园评分、NPC 好感四轴成长；宠物=原版可驯服生物\+改名。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[SimpleDialogue](https://github.com/Kcajpanda/simple_dialogue)|GitHub|MIT|2026\-05 活跃|Paper（小版本待复核）|是|B|YAML 分支对话树，接好感度加减钩子|
|[ProgressionNPCWork](https://modrinth.com/plugin/progressionnpcwork)|Modrinth|MIT|约半年前|Paper（待复核）|是|B|NPC\+对话\+商店\+伙伴一体，裁剪好感/伙伴|
|[Baby Pets](https://www.curseforge.com/minecraft/bukkit-plugins/baby-pets)|CurseForge|MIT|2026\-04|Paper/Spigot 1\.2x|是|B|原版狼/动物跟随\+幸福度 Buff|
|[HeroPets](https://hangar.papermc.io/Mr_Pickles83/HeroPets)|Hangar|MIT|2026\-04|Paper 1\.2x|是|B|幸福度\>80% 给 Buff，宠物好感回报参考|
|[DoggyPlus](https://modrinth.com/plugin/doggyplus)|Modrinth|MIT|2025–2026|Paper 1\.2x|是|B|原版狗增强，零资产小而专|
|[EzSeasons（农业等级旁证）](https://github.com/master3716/skills)|GitHub|需人工复核|2025\-10（v1\.1\.4）|1\.20/1\.20\.6/1\.21|是|B/C|Farming/Combat/Fishing 等级最小实现|
|[EmakiCooking](https://github.com/jiuwu02/Emaki_Series)|GitHub|需人工复核|2026\-06（3\.3\.0）|1\.20\.6/1\.21|需复核|B/C|七个烹饪工作站\+YAML 食谱范式|
|[Craftorithm](https://github.com/YufiriaMazenta/Craftorithm)|GitHub|需人工复核|2026\-09（支持 26\.3）|1\.13–1\.21/Folia|否（需复核）|C|原版合成表\+YAML 扩展食谱分层设计|
|[NotQuests](https://github.com/AlessioGr/NotQuests)|GitHub|GPL\-3\.0|2026\-09 活跃|Paper 1\.17–1\.21\.x|是|C|声望=NPC 好感数据模型参考|
|[MyPet](https://github.com/MyPetORG/MyPet)|GitHub|LGPL\-3\.0（需复核）|2025\-10 活跃|1\.20\.4–1\.21\.x|是|C（可 A）|宠物好感/升级曲线参考，不嵌源码|
|[CustomCrafting](https://github.com/WolfyScript/CustomCrafting)|GitHub|GPL\-3\.0\-or\-later|2026\-06 活跃|1\.17\.1–1\.20\.6（1\.21 待复核）|是|C|原版配方类型\+扩展元数据注册抽象参考|
|[AuraSkills](https://modrinth.com/plugin/auraskills)|Modrinth|GPL\-3\.0\-only|2026\-09 活跃|1\.17–1\.21\.x|是|C|Farming 经验曲线与被动加成对照|

> **家园评分**：无合格开源候选（PlotSquared 等为地皮世界管理\+玩家互评，范式不符且 GPL），建议自研——扫描保护区内方块按装饰价值表加权打分；降级 C 档参考 PlotSquared Plot Rating 数据流作"社区人气分"补充。
> 
> 

### 3\.9 cc\-events 世界事件

**职责**：8 个世界事件\+说书人 NPC\+廉价商店，调度器频率/时长/内容走 events\.yml，歌词氛围订阅其他模块事件做粒子/音效演出。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[Christmas Season](https://www.curseforge.com/minecraft/bukkit-plugins/christmas-season)|CurseForge|MIT|2026\-07（Java 21）|Paper/Spigot 1\.21|是|B|季节限时活动完整范例（天气\+掉落\+NPC）|
|[EzSeasons](https://github.com/ez-plugins/EzSeasons)|GitHub|推断 MIT（需复核）|2026\-04（v2\.1\.2）|1\.11–1\.21|是|B|按日历/周期触发任务调度器|
|[PulseEvents](https://www.curseforge.com/minecraft/bukkit-plugins/pulseevents)|CurseForge|需人工复核|2026\-08 新|Paper（待复核）|是|B/C|可插拔事件类型\+自动调度\+GUI 管理|
|[WorldMood](https://github.com/Rex-Micky/WorldMood-Plugin)|GitHub|需人工复核|2026\-07（v1\.3\.0）|1\.16\.5–1\.21\.x|是|B/C|7 种定时 mood 改刷怪/掉落/天气/光照|
|[BloodMoon\-Reborn](https://github.com/julion12/BloodMoon-Reborn)|GitHub|需人工复核|2026\-07（v1\.0\.1）|1\.20/1\.20\.6/1\.21|是|B/C|定时血月\+夜间刷怪增强\+禁睡觉模板|
|[Meteor\-Shower](https://github.com/benardamorkoc/Meteor-Shower)|GitHub|需人工复核|2025\-08|1\.8–1\.21|是|B/C|定时天降陨石\+粒子\+爆炸\+坑|
|[Meteoriti](https://github.com/Cubixon-McD/Meteoriti)|GitHub|需人工复核|2026\-08（0\.0\.1）|1\.21/26\.x|是|B/C|贝塞尔轨迹\+屏幕震动视觉更炫|
|[DailyEvent](https://modrinth.com/plugin/dailyevent)|Modrinth|GPL\-3\.0\-only|约 1 年前停更|Bukkit/Paper|是|C|定时切换全局规则包配置结构参考|

> **说书人 NPC** 复用 cc\-cozy 的 SimpleDialogue/ProgressionNPCWork；**廉价商店** 复用 cc\-economy 的 NPC 商店/ChestTrade；**歌词氛围调度器** 无专门开源件，建议自研事件订阅器播 Particle/Sound/Weather，参考 WorldMood 天气改造手法。
> 
> 

### 3\.10 cc\-economy 经济/市场

**职责**：四层市场（NPC 商店/玩家摊位/拍卖行 48h/以物易物）、6 职业 24h 切换冷却、物品 PDC 历任持有者、灵魂货币注册 Vault。

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[EzAuction](https://github.com/ez-plugins/EzAuction)|GitHub|MIT|2025\-12|1\.13–26\.1|是|B/A|GUI 拍卖行\+买单\+价格记录，48h 可配|
|[Smiley Player Trader](https://github.com/Mrcomputer1/SmileyPlayerTrader)|GitHub|MIT|2026\-06|1\.19–1\.21\.x|是|B|原版村民交易 GUI 做玩家间以物易物|
|[ChestTrade](https://hangar.papermc.io/wineredbbqsauce/ChestTrade)|Hangar|MIT|2026\-04 早期|Paper|是|B|箱子挂牌纯物物交换无需货币|
|[CrossTrade](https://hangar.papermc.io/yangzijian52/CrossTrade)|Hangar|MIT|2026\-07 新|Paper\+Vault\+Floodgate|是|B|54 格对称交易 GUI\+双方确认\+货币按钮|
|[Vault](https://github.com/MilkBowl/Vault)|GitHub|LGPL\-3\.0|标准稳定|全版本 API|是|A|灵魂货币注册统一 Economy 接口桥|
|[QuickShop\-Hikari](https://github.com/Ghost-chu/QuickShop-Hikari)|GitHub|**AGPL\-3\.0\-only**|极活跃（6\.2\.x）|1\.20–1\.21\.x|是|A|箱子挂牌玩家商店，hook 事件收灵魂|
|[ChestShop\-3](https://github.com/ChestShop-authors/ChestShop-3)|GitHub|LGPL\-2\.1|维护中（3\.13\-pre\-1）|1\.21\.x|是|A|最轻量告示牌\+箱子开店，低配备选|
|[Jobs Reborn](https://github.com/Zrips/Jobs)|GitHub|GPL\-3\.0|极活跃（5\.2\.6\.6）|Paper 1\.20\.4/1\.21\.x|是|C|职业\-动作\-收益事件钩子表参考|
|[Chronicles](https://www.spigotmc.org/resources/chronicles.131460/)|SpigotMC|需人工复核|2026\-01|Paper 1\.21\.x|是|C|PDC\+Lore 追加物品历史范式参考|
|[Tracked Items](https://modrinth.com/plugin/trackeditems)|Modrinth|需人工复核|2026\-05|Paper|是|C|ORIGINAL/STORED/STOLEN 状态机参考|
|[ErrorShop](https://github.com/error0403/ErrorShop)|GitHub|需人工复核|2026\-05|1\.20\.6/1\.21|是|C|中文轻量商店\+玩家市场 UX 参考|
|[GoodsTrade](https://github.com/polang233/GoodsTrade)|GitHub|GPL\-3\.0|2026\-08|Paper 1\.21|是|C|中文玩家间交易 UX 参考|

> **6 职业\+24h 切换冷却**：Jobs Reborn 为 GPL 且强依赖闭源 CMILib，不可嵌代码；建议自研，用 Bukkit 事件（Break/Fish/Brew/Craft/Kill）做加成钩子，PDC 存切换时间戳。
> 
> 

---

## 四、通用基础件

**职责**：跨模块复用的技术底座——GUI 库、配置/消息库、命令框架、数据库、事件总线、NBT/PDC、工具库。

### 4\.1 GUI 库

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[TriumphGUI](https://github.com/TriumphTeam/triumph-gui)|GitHub|MIT|活跃（3\.1\.13，2025\-09）|兼容（1\.20\.4 需核对）|是|A|背包/分页/滚动 GUI\+逐格动作\+ItemBuilder|
|[SmartMenus\-V2](https://github.com/el211/SmartMenus-V2)|GitHub|需人工复核|活跃（v3\.5，2026\-08）|兼容（1\.21/Folia）|是（软挂钩可选）|A|SmartInvs 血统\+现代 Folia 维护|
|[CustomGuiReworked](https://modrinth.com/plugin/customguireworked)|Modrinth|MIT|极活跃（2 天前）|兼容（1\.21\.x）|是|B|InventoryHolder 模式可摘用改造|
|[AuMenus](https://github.com/auvq/AuMenus)|GitHub|需人工复核|活跃（v1\.0\.6，2026\-05）|仅 1\.21\.1\+（1\.20\.4 不兼容）|是|C|无闪烁 bundle 换页平滑过渡思路|
|[SmartInvs（原版）](https://github.com/MinusKube/SmartInvs)|GitHub|Apache\-2\.0|长期停更（到 1\.14）|不兼容|是|C|InventoryListener 单监听器路由模式参考|

### 4\.2 配置/消息库

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[Adventure \+ MiniMessage](https://github.com/KyoriPowered/adventure)|GitHub|MIT|极活跃（Paper 1\.18\+ 内置）|兼容（原生内置）|是|A（原生）|组件化消息\+per\-player i18n，直接用|
|[Configurate](https://github.com/SpongePowered/Configurate)|GitHub|Apache\-2\.0|活跃|兼容（纯 Java）|是|A|树形配置 YAML/HOCON/JSON 多格式|
|[okaeri\-configs](https://github.com/OkaeriPoland/okaeri-configs)|GitHub|MIT|维护中|兼容|是|A|POJO 声明式配置 Maven 坐标成熟|
|[ConfigLib](https://github.com/Exlll/ConfigLib)|GitHub|MIT|维护中|需人工复核|是|B|类型安全 YAML\+注释保留\+热更新|

### 4\.3 命令框架

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[Paper Commands（Brigadier）](https://jd.papermc.io/paper/1.21.3/io/papermc/paper/command/brigadier/Commands.html)|Paper Javadoc|Paper MIT|随 Paper 演进|1\.20\.6\+ 原生（1\.20\.4 无）|是|A（原生）|零第三方依赖，子命令树\+客户端校验|
|[ACF](https://github.com/aikar/commands)|GitHub|MIT|维护中|兼容（acf\-paper 工件）|是|A|注解声明子命令\+参数解析\+Tab 补全|
|[CommandAPI](https://github.com/CommandAPI/CommandAPI)|GitHub|MIT|极活跃（v11\.1\.0）|部分兼容（1\.20\.4 桥接待核对）|是|A|50\+ 参数类型\+权限校验\+datapack 兼容|
|[Despical/CommandFramework](https://github.com/Despical/CommandFramework)|GitHub|需人工复核|活跃（到 26\.1\.2）|兼容（跨 1\.7–最新）|是|C|跨版本兼容策略可借鉴|

### 4\.4 数据库/持久化

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[HikariCP](https://github.com/brettwooldridge/HikariCP)|GitHub|Apache\-2\.0|极活跃|兼容（纯 Java）|是|A|事实标准 JDBC 连接池，shade\+relocate|
|[sqlite\-jdbc](https://github.com/xerial/sqlite-jdbc)|GitHub|Apache\-2\.0|极活跃|兼容|是|A|SQLite 官方驱动，运行时拉取不打包|
|[Flyway](https://github.com/flyway/flyway)|GitHub|Apache\-2\.0|活跃|兼容（纯 Java）|是|B/A|版本化 SQL 迁移，启动建表\+轻量迁移|
|[PeachLib](https://hangar.papermc.io/PeachBiscuit174/PeachLib)|Hangar|MIT|业余项目|兼容|是|B|Paper libraries API 运行时自动下载驱动|
|[LuckPerms Storage 层](https://github.com/LuckPerms/LuckPerms)|GitHub|MIT|极活跃|兼容|否|C|loadUser 返 Future\+写回队列异步落库参考|
|[SunDB](https://www.spigotmc.org/resources/sundb.134165/)|SpigotMC|需人工复核|alpha（2026\-04）|兼容|是|C|Fluent Query Builder\+跨库迁移设计参考|

### 4\.5 事件总线

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[Paper Event API](https://docs.papermc.io/paper/dev/event-listeners/)|Paper 文档|Paper MIT|随 Paper 演进|兼容|是|A（原生\+自封装）|MC 世界事件用原生，自定义总线自研薄封装|
|[helper\-events](https://github.com/lucko/helper)|GitHub|MIT|维护态|部分兼容（Folia 复核）|是|B|函数式监听器注册改造为字符串事件名总线|
|[Guava EventBus](https://github.com/google/guava)|GitHub|Apache\-2\.0|极活跃|兼容（纯 Java）|是|C|订阅者注册/异常分发范式参考|

> **事件总线无完美对口开源候选**——MC 生态均按事件类分发，而 ChiliCraft 要"字符串事件名\+EventData"。建议自研薄封装，参考 lucko/helper 函数式订阅与 Guava EventBus 异常分发；降级=直接用 Bukkit 原生 @EventHandler。
> 
> 

### 4\.6 物品/NBT/PDC 工具库

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[NBT\-API](https://hangar.papermc.io/tr7zw/NBTAPI)|Hangar|MIT|极活跃（v2\.16\.0，2026\-09）|兼容（1\.14–1\.21\.5）|是|A|无 NMS 给物品/方块/实体加自定义 NBT|
|[MorePersistentDataTypes](https://github.com/mfnalex/MorePersistentDataTypes)|GitHub|MIT（需复核）|维护中|兼容|是|B|扩展 PDC 可存 List/Map/嵌套集合|
|[AnhyLibAPI](https://dev.curseforge.com/projects/anhylibapi)|CurseForge|MIT|2026\-05|需人工复核|是|C|NBT\+i18n 组合设计参考|

> 优先用 Paper 原生 PDC（零依赖）；需复杂集合存 PDC 时摘 MorePersistentDataTypes；需裸 NBT 时软依赖 NBT\-API。
> 
> 

### 4\.7 其他通用工具库

|项目名|URL|许可证|最近活跃度|Paper 兼容|零资产|缝合档位|核心理由|
|---|---|---|---|---|---|---|---|
|[PaperLib](https://github.com/PaperMC/PaperLib)|GitHub|MIT|活跃（PaperMC 官方）|兼容（反射降级）|是|A|异步区块加载/调度一行 API|
|[BKCommonLib](https://www.spigotmc.org/resources/bkcommonlib.39590/)|SpigotMC|需人工复核|成熟（2026\-09 仍更新）|兼容|是|C|高性能 YAML 注释保留模型参考|
|[AlpineCore](https://hangar.papermc.io/Alpine/AlpineCore)|Hangar|需人工复核|早期（2024 起）|兼容（1\.8–1\.21\.3）|是|C|低样板\+现代技术整合路线对标|

---

## 五、许可证风险警示

### 高危 GPL / LGPL / AGPL 项目合规边界

|项目|许可证|合规处置|
|---|---|---|
|**QuickShop\-Hikari**|**AGPL\-3\.0\-only**|最严。只能 A 档独立插件运行\+hook 交易事件收灵魂；**绝对不能复制/修改任何源码进闭源 cc\-economy**，否则触发 AGPL 网络开源义务。|
|EliteMobs|GPL\-3\.0|可 A 档独立软依赖运行（进程间事件/指令通信不传染闭源 ChiliCraft）；**禁止把类复制进 ChiliCraft**。Model Engine 资产可选关闭。|
|BetonQuest|GPL\-3\.0\-only|若软依赖需法务确认 GPL 对闭源插件软依赖边界；严格隔离则降为 C 档仅参考其 YAML 包结构。|
|Jobs Reborn|GPL\-3\.0|禁止嵌代码；且强依赖闭源 CMILib，不可整体软依赖；仅 C 档参考职业\-动作\-收益钩子表。|
|AbilityClass|GPL\-3\.0\-or\-later|禁止复制代码；仅借鉴 skills\.yml trigger→cooldown→effect 表结构。|
|BattleArena|GPL\-3\.0\-only|禁止复制代码；仅参考 Arena/Skirmish/Colosseum 多模式架构。|
|NotQuests|GPL\-3\.0|禁止抄代码；参考声望=NPC 好感数据模型。|
|MyPet|LGPL\-3\.0|可 A 档软依赖联动，不可把源码拷进闭源。|
|CustomCrafting|GPL\-3\.0\-or\-later|禁止抄代码；参考配方注册抽象。|
|AuraSkills|GPL\-3\.0\-only|仅参考 Farming 经验曲线，不整体引入。|
|OhTheDungeon\-Redux|GPL\-3\.0|不可并入闭源；仅参考程序化地牢布局算法。|
|DailyEvent|GPL\-3\.0\-only|停更\+GPL，仅参考全局规则包配置结构。|
|GoodsTrade|GPL\-3\.0|仅作中文 UX 参考，不嵌代码。|
|Vault|LGPL\-3\.0|只能 A 档软依赖运行（调用 VaultAPI jar），不可拷源码。|
|ChestShop\-3|LGPL\-2\.1|可 A 档软依赖 hook 交易事件，不可嵌源码。|

### 需人工复核许可证清单（引入前必须点开仓库 LICENSE 文件确认 SPDX）

- **cc\-core**：DzusillCore、KLibrary、Lattice、QinhCoreLib

- **通用基础件**：SmartMenus\-V2、AuMenus、Despical/CommandFramework、SunDB、BKCommonLib、AlpineCore、MorePersistentDataTypes

- **cc\-survival**：DurabilityPlus 的 GitHub 仓库地址与 1\.20\.4 兼容；Hydration 的 GitHub 源码仓库

- **cc\-demon**：VortexMobs 根 LICENSE 文件

- **cc\-adventure**：SinceDungeon 是否付费分发包（SpigotMC 标 premium\-grade）

- **cc\-martial**：DuelPlugin 的 jar 是否真 MIT；AbilityClass 的 MC 版本下限

- **cc\-quest**：PikaMug/Quests"源码 MIT"与"CurseForge 分发 All Rights Reserved"是否矛盾；QuestTracker、QuestLogs 的 SPDX

- **cc\-soul**：GraveStonesPlus、Chronicles、Death\-Penalty、Tracked Items、HereDed 的 SPDX

- **cc\-cozy**：EmakiCooking、Craftorithm、master3716/skills 的 SPDX，以及是否强制资源包

- **cc\-events**：PulseEvents、WorldMood、BloodMoon\-Reborn、Meteor\-Shower、Meteoriti、EzSeasons 的 SPDX

- **cc\-economy**：ErrorShop 的 SPDX；Chronicles/Tracked Items 的 SPDX；Citizens2 的 LICENSE（按 GPL 系处置，仅软依赖不抄码）

> 复核完成前，以上项目一律按 **C 档（仅参考）** 处置，不得嵌入代码。
> 
> 

---

## 六、零资产红线复核

ChiliCraft 全程不使用资源包/自定义模型/材质贴图。以下项目可能涉及资源包/自定义模型依赖，需特别注意替代路径：

|项目|潜在资产依赖|替代路径|
|---|---|---|
|EliteMobs|可选 Model Engine 自定义模型|关闭 Model Engine 集成，走原版模型路径|
|MythicMobs|原生支持 Model Engine/自定义模型|只用技能层，不挂 Model Engine 资源，仍用原版模型|
|EmakiCooking|"工作站"很可能用自定义方块/模型数据（待复核）|若确认依赖资源包则降级 C 档，改用原版工作站方块\+YAML 食谱|
|Craftorithm|数据驱动配方不强制资源包（待复核）|确认无自定义模型后才考虑 B 档|
|SmartMenus\-V2 / Lattice / AuMenus|仅**可选软挂钩** HeadDatabase/ItemsAdder/Nexo/Oraxen 提供额外头颅/物品|原版物品可独立运行，不构成资产依赖|
|SinceDungeon|SpigotMC 标 premium\-grade，是否付费资源待复核|确认 GitHub MIT 仓库为完整可商用源码后再 B 档|
|Temperature999|资产依赖未声明|不可用，仅产品形态参考|
|TheMob|未声明自定义模型（待复核）|按 C 档仅参考设计|

> **红线结论**：凡强制 Oraxen/Nexo/ItemsAdder 自定义模型/资源包的项目（如 Allium Harvest、部分 Custom\-Crops/CraftEngine 衍生）一律排除出 B 档；ChiliCraft 全部用原版箱子 GUI、原版方块、粒子、音效、BossBar、原版生物模型实现。
> 
> 

---

## 七、缝合落地路线图建议

按 ChiliCraft 开发五步顺序推进，每步优先缝合以下项目：

### 第 1 步：cc\-core 打地基

**优先缝合（A/B 档，搭起基础件骨架）**：

- GUI：TriumphGUI（A，MIT）

- 命令：ACF（A，MIT）；1\.21\.x 走原生 Brigadier

- 数据库：HikariCP \+ sqlite\-jdbc \+ Flyway（全 Apache\-2\.0，A/B）

- 配置：Configurate（A，Apache\-2\.0）\+ ConfigLib（B，MIT 注释保留）

- 消息：Paper 原生 Adventure/MiniMessage（直接用）

- 工具：PaperLib（A，MIT）

- NBT：NBT\-API（A，MIT）；PDC 集合摘 MorePersistentDataTypes（B）

**架构参考（C 档）**：LuckPerms 玩家档案异步落库蓝本；DzusillCore/KLibrary 整体模块划分对标。

**必须自研**：字符串事件名事件总线；ChiliCraftAPI 版本化接口。

### 第 2 步：cc\-survival \+ cc\-demon

**cc\-survival 优先缝合**：

- DurabilityPlus（B，Apache\-2\.0）——耐久×1\.5 倍率注入

- SurvivalMethod（B，MIT）——1 秒周期任务扣减骨架

- Hydration（A/B，MIT）——0\-100 分档状态机范式

- 自研：体温四档、负重\-速度曲线（参考 ThermoSurvival/Weight\-RPG 设计）

**cc\-demon 优先缝合**：

- VortexMobs（B/A，Apache\-2\.0）——原版怪挂属性倍率\+词缀注入器

- EliteMobs（A 软依赖，GPL）——世界 Boss/掉落表软依赖运行

- MythicMobs（A 软依赖，付费）——运行时联动目标

- 参考：TheMob 非每 tick 遍历目标选择

### 第 3 步：cc\-martial \+ cc\-soul \+ cc\-adventure

**cc\-martial 优先缝合**：

- Fabled（B，MIT）——技能注册\+YAML 解析层

- EzSkills（B，MIT）——运行时注册 4\+2 技能骨架

- DuelPlugin（B，MIT）——排队\+kit 装备改造成定时擂台

- PulseEvents（A，MIT）——擂台定时调度\+广播\+BossBar

- 自研：6 层境界解锁 gate

**cc\-soul 优先缝合**：

- SoulGraves（B，MIT）——灵魂碎片 24h 过期生命周期

- 参考：Chronicles/Tracked Items 的 PDC/Lore 历史范式

- 自研：12 遗物 PDC UUID、葬礼仪式、灵魂收容所

**cc\-adventure 优先缝合**：

- SinceDungeon（B，MIT）——实例隔离\+随机房骨架

- DungeonGates（A/B，MIT）——区域事件驱动进度

- EliteMobs（A 软依赖，GPL）——世界 Boss 血条分级

- 自研：祝福/诅咒系统

### 第 4 步：cc\-quest

**优先缝合**：

- PikaMug/Quests（A/B，MIT）——任务目标引擎（击杀/收集/对话/探索等）

- BetonQuest（A/C，GPL，需法务确认）——YAML chapters 包结构模板

- 自研：章节编排层、旅途手册成书 GUI、歌词 actionbar\+天气\+粒子演出、仪式任务

### 第 5 步：cc\-cozy \+ cc\-events \+ cc\-economy

**cc\-cozy 优先缝合**：

- SimpleDialogue（B，MIT）——NPC 分支对话接好感度钩子

- ProgressionNPCWork（B，MIT）——裁剪 NPC 好感\+伙伴逻辑

- Baby Pets / HeroPets / DoggyPlus（全 B，MIT）——原版宠物增强\+幸福度 Buff

- 参考：EmakiCooking/Craftorithm 的 YAML 食谱 schema（待复核许可）

- 自研：家园评分、料理图鉴 recipes\.yml 解析

**cc\-events 优先缝合**：

- Christmas Season（B，MIT）——新春游园季节活动骨架（换皮中式）

- EzSeasons（B，待复核 MIT）——events\.yml 按日历/周期调度器

- WorldMood / BloodMoon\-Reborn / Meteor\-Shower（B/C，待复核）——世界规则改造\+夜间/流星雨事件模板

- 自研：歌词氛围事件订阅器

**cc\-economy 优先缝合**：

- EzAuction（B/A，MIT）——48h 拍卖行

- Smiley Player Trader / ChestTrade / CrossTrade（全 B，MIT）——以物易物三层

- Vault（A，LGPL\-3\.0）——灵魂货币注册

- QuickShop\-Hikari（A，AGPL\-3\.0，最严）——玩家箱子摊位，仅 hook 事件收灵魂

- ChestShop\-3（A，LGPL\-2\.1）——低配服备选

- 自研：6 职业\+24h 切换冷却、物品 PDC 历任持有者写入

---

> 本报告所有 URL 均来自 GitHub / SpigotMC / CurseForge / Modrinth / Hangar 的真实调研结果；标注"需人工复核"处为检索片段未直接展示 LICENSE 全文或版本实测矩阵，落地前请由维护者/法务点开仓库 LICENSE 文件确认。
> 
> 

## 八、不可缝合 → 联动替代方案总表

> **主创决策**：「不能缝合的看看能不能联动，直接联动也行」。
> 
> **关键法律澄清**：缝合（抄代码进 ChiliCraft 闭源工程）受许可证严格限制；但**软依赖联动**是指把候选插件作为独立 jar 由服主自行安装，ChiliCraft 通过其公开 API / 自定义事件 / PlaceholderAPI 占位符 / Vault 经济接口与之通信，进程间互不分发源码——这种方式对 GPL/LGPL/AGPL 甚至无许可证插件都**不产生衍生作品关系**，只要 ChiliCraft 不打包其 jar、不反编译修改其代码即可。下表据此逐个重评。
> 
> **已拍板软依赖清单（冲突参考）**：LuckPerms（权限）、MythicMobs（怪物技能）、Citizens（NPC）、Vault（经济桥）、PlaceholderAPI（占位符）、QuickShop\-Hikari（玩家摊位）、EssentialsX、WorldGuard（区域）、CoreProtect（查档）。
> 
> 

### 8\.1 联动结论分档

- **可直接联动**：插件独立运行 \+ 有公开 API/事件/PAPI \+ 零资产 \+ 配置键值开关级。

- **联动但有门槛**：可独立运行但存在配置复杂 / 需开发者介入 / 版本部分兼容 / 许可证限制 / 与已拍板插件功能重叠需二选一。

- **联动不可行 / 不适用**：停更不兼容、是开发库而非运行插件、强制资源包、或无任何对外接口。

### 8\.2 GPL / AGPL / LGPL 类候选重评

|项目名|原许可证/档位|维护状态|联动接口（API/事件/PAPI/Vault）|零资产|配置复杂度|对应模块|联动结论|与已选插件冲突说明|
|---|---|---|---|---|---|---|---|---|
|[QuickShop\-Hikari](https://github.com/Ghost-chu/QuickShop-Hikari)|AGPL\-3\.0 / A|极活跃（6\.2\.x，支持 1\.21\.3/1\.21\.4）|公开 quickshop\-api 模块；ShopSuccessPurchaseEvent 等交易事件；PAPI `%qs_metrics_...%`；InteractionManager 扩展点；Vault 经济对接|是（原版箱子/告示）|简单（键值开关）|cc\-economy 玩家摊位|\*\*可直接联动**（已拍板）**|已在清单内；AGPL 最严，严禁反编译修改其代码\*\*，仅 hook 交易事件收灵魂|
|[EliteMobs](https://github.com/MagmaGuy/EliteMobs)|GPL\-3\.0 / A/C|极活跃（2026\-09，v10\.7\.3）|自定义事件 ArenaCompleteEvent / CustomEventStartEvent / DungeonInstallEvent；PAPI 排行榜占位符；Lua power/npc\_scripts 钩子|可选 ModelEngine/ResourcePackManager（**可关闭走原版模型**）|中等（世界 Boss 配置需开发者介入）|cc\-demon \+ cc\-adventure 世界 Boss|**可直接联动**|与 MythicMobs 功能重叠：**二选一建议**——ChiliCraft 已选 MythicMobs 做技能层，EliteMobs 仅作"世界 Boss\+动态地牢"软依赖，二者分工不冲突|
|[BetonQuest](https://github.com/BetonQuest/BetonQuest)|GPL\-3\.0\-only / A/C|极活跃（3 天前更新，支持到 26\.1\.2）|ServicesManager 注册的 BetonQuestApi；PAPI `%betonquest_...%`；条件可经 PAPI 暴露；nujobs\_/shopkeeper\_ 等事件动作|是（纯文本对话\+原版物品）|中等（YAML 包结构，剧情编写需策划介入）|cc\-quest 主线章节|**可直接联动**（需法务最终确认 GPL 软依赖边界）|与 PikaMug/Quests 重叠：二选一建议——选 BetonQuest 做主线对话演出，PikaMug 仅作日常任务备选|
|[NotQuests](https://github.com/AlessioGr/NotQuests)|GPL\-3\.0 / C|活跃（2026\-09 文档更新）|PAPI `%notquests_player_has_completed_quest_...%` 等；公开 API 教程（可注册自定义 objective/variable）；原生兼容 MythicMobs/EliteMobs Kill 目标|是（盔甲架/原版 NPC）|中等（GUI 配置任务，比 BetonQuest 直观）|cc\-cozy NPC 好感 / cc\-events 说书人|**可直接联动**|与 BetonQuest/PikaMug 三选一：NotQuests 声望系统最贴 NPC 好感，但主线剧情仍建议 BetonQuest|
|[Jobs Reborn](https://github.com/Zrips/Jobs)|GPL\-3\.0 / C|极活跃（5\.2\.6\.6，2026\-07）|公开 5\+ 事件 JobsJoinEvent/Leave/LevelUp/Payment/ExpGainEvent；Maven Jobs API；PAPI `%jobsr_user_jobs_...%`|是|中等（职业动作表 YAML）|cc\-economy 6 职业|**联动有门槛**|强依赖闭源 CMILib（必须一起装）；与 cc\-core 统一灵魂货币 API 冲突（它自己发 Vault 工资）；建议**仅当服主愿自带 Jobs 体系时联动**，ChiliCraft 6 职业仍自研|
|[AuraSkills](https://modrinth.com/plugin/auraskills)|GPL\-3\.0\-only / C|活跃（2\.3，2026\-09 支持 1\.21\.5）|公开 Developer API（可注册自定义 skill/stat/ability）；LootDropEvent/SkillsLoadEvent 等；PAPI `%auraskills_...%`|是|中等（skills\.yml 经验曲线）|cc\-cozy 农业等级轴|**可直接联动**|与自研农业等级重叠：建议直接 A 档软依赖 AuraSkills 承接 Farming/Fishing/Foraging 经验轴，cc\-cozy 仅叠加料理/家园/NPC 好感三轴，避免重造经验曲线|
|[MyPet](https://github.com/MyPetORG/MyPet)|LGPL\-3\.0（待复核） / C|维护中（2025\-10）|公开 API（宠物骑乘/战斗/升级）；PAPI 占位符|是（原版生物模型）|中等|cc\-cozy 宠物轴|**联动有门槛**|与 ChiliCraft"宠物=原版驯服生物\+改名"轻定位不符；MyPet 过重，建议仅 C 参考好感曲线，不软依赖|
|[Vault](https://github.com/MilkBowl/Vault)|LGPL\-3\.0 / A|事实标准稳定|VaultAPI 接口（Economy/Permission/Chat）|是（纯 API）|零（直接依赖 VaultAPI jar）|cc\-economy 货币桥|**可直接联动**（已拍板）|已在清单内；仅依赖 VaultAPI jar，不拷源码|
|[ChestShop\-3](https://github.com/ChestShop-authors/ChestShop-3)|LGPL\-2\.1 / A|维护中（3\.13\-pre\-1，2026\-07）|交易事件 hook；Vault 经济对接|是|简单（告示牌\+箱子）|cc\-economy 玩家摊位备选|**可直接联动**（低配备选）|与 QuickShop\-Hikari 二选一：高配用 QSH，低配服可用 ChestShop\-3 更轻|
|[AbilityClass](https://gitlab.com/Saltyy/minecraft-abilityclass-plugin)|GPL\-3\.0\-or\-later / C|活跃（v1\.5\.0，2026\-04）|无公开 PAPI/API 文档；YAML 配置技能，触发事件走原版 Bukkit|是|中等（skills\.yml）|cc\-martial 词缀事件钩子|**联动有门槛**|无公开对外接口，只能独立运行；ChiliCraft 45 词缀建议自研，仅参考其 schema|
|[BattleArena](https://modrinth.com/plugin/battlearena)|GPL\-3\.0\-only / C|约 2 个月前更新|config 定义模式；无公开 PAPI/API 文档|是|复杂（竞技场框架）|cc\-martial 擂台|**联动有门槛**|框架过大，ChiliCraft 只需定时 1v1 擂台，已有 DuelPlugin\+PulseEvents 更轻；不软依赖|
|[OhTheDungeon\-Redux](https://github.com/roschreiber/OhTheDungeon-Redux)|GPL\-3\.0 / C|活跃（2026\-08）|程序化地牢生成，无公开 PAPI/API|是（原版结构）|复杂（地牢模板）|cc\-adventure 地城建筑|**联动有门槛**|与 SinceDungeon 重叠：SinceDungeon（MIT B 档）更贴实例隔离需求；OhTheDungeon 仅作程序化布局参考，不软依赖|
|[DailyEvent](https://modrinth.com/plugin/dailyevent)|GPL\-3\.0\-only / C|约 1 年前停更|无公开 PAPI/API|是|简单（赛季轮换）|cc\-events 赛季轮换|**联动有门槛**|已停更，版本兼容风险；WorldMood（活跃）可替代，不推荐软依赖|
|[CustomCrafting](https://github.com/WolfyScript/CustomCrafting)|GPL\-3\.0\-or\-later / C|活跃（2026\-06）|GUI 配方创建；无公开 PAPI；1\.21 兼容待复核|是|中等（GUI 建配方）|cc\-cozy 料理图鉴|**联动有门槛**|1\.21 兼容未确认；料理 recipes\.yml 建议自研，仅参考其配方注册抽象|
|[GoodsTrade](https://github.com/polang233/GoodsTrade)|GPL\-3\.0 / C|活跃（2026\-08，中文 QQ 群维护）|无公开 API 文档|是|简单（玩家间交易）|cc\-economy 玩家交易|**联动有门槛**|与 CrossTrade（MIT B 档）功能重叠；CrossTrade 许可证干净，优先 CrossTrade|

### 8\.3 无许可证 / 闭源类候选重评

> **法律说明**：下列项目虽无开源 LICENSE 或闭源分发，但**作为独立 jar 由服主自行安装运行**、ChiliCraft 不打包不反编译，不构成衍生作品。ChiliCraft 仅在服主主动安装后通过原版 Bukkit 世界事件间接协作，不直接调用其内部类。
> 
> 

|项目名|原许可证/档位|维护状态|联动接口|零资产|配置复杂度|对应模块|联动结论|替代路径/冲突说明|
|---|---|---|---|---|---|---|---|---|
|[ThermoSurvival](https://github.com/parlamentum/ThermoSurvival)|无 LICENSE / C|停更（2025\-12）|无公开 API；完整体温插件（\-30\~50℃，8 级渐变，60\+ 群系，WorldGuard 豁免）|是（BossBar\+原版 vignette）|中等（群系权重 YAML）|cc\-survival 体温|**可直接联动**（直接当体温系统用）|无 LICENSE 仅禁止抄码，独立运行合法；ChiliCraft 体温四档直接用它，不自研|
|[Temperature999](https://modrinth.com/plugin/temperature999)|未公开 / C|活跃（v1\.0\.3，2026\-07）|无公开 API；完整昼夜温度速率配置|待复核|简单（按世界配置昼夜速率）|cc\-survival 体温夜间×2\.0|**可直接联动**|与 ThermoSurvival 二选一：Temperature999 活跃但闭源，ThermoSurvival 开源可读配置；建议选 ThermoSurvival|
|[Inventory Weight](https://www.spigotmc.org/resources/70929/)|闭源 / C|维护（v2\.21\.0，2024\-12，支持 1\.16–1\.21）|无公开 API；完整负重系统（按材料/Lore 配重量，70% 减速/100% 禁跳）|是|中等（物品重量表）|cc\-survival 负重|**可直接联动**|数值模型与需求完全一致，直接软依赖运行，重量表按需求配 200kg/70%/100%|
|Weight\-RPG|闭源 / C|2022 停更|同上|是|中等|cc\-survival 负重|**联动不可行**|停更 4 年，用 Inventory Weight 替代|
|[TheMob](https://github.com/3o3y/TheMob)|NOASSERTION / C|停更（2026\-02）|无公开 API；YAML 自定义怪\+多阶段 Boss|待复核|中等（YAML 怪配置）|cc\-demon 母体|**联动有门槛**|license 未明且停更；与 VortexMobs（Apache\-2\.0 B 档）重叠，优先 VortexMobs|
|[DungeonRooms](https://github.com/evnrca/DungeonRooms)|无 LICENSE / C|活跃（2026\-08）|无公开 API；WorldGuard 建房\+房锁\+击杀目标|依赖 WorldGuard（非资源包）|中等（区域配置）|cc\-adventure 远征逐房|**联动有门槛**|无 LICENSE 仅独立运行；与 DungeonGates（MIT A/B）重叠，优先 DungeonGates|

### 8\.4 待人工复核许可证类候选重评

> 下列项目许可证 SPDX 未从聚合页坐实。**作为独立运行插件**，无论许可证为何，服主自行安装均合法；ChiliCraft 不打包其 jar、不调内部类即可。许可证复核仅影响"B 档嵌代码"，不影响"A 档软依赖运行"。
> 
> 

|项目名|原许可证/档位|维护状态|联动接口|零资产|配置复杂度|对应模块|联动结论|备注|
|---|---|---|---|---|---|---|---|---|
|[PikaMug/Quests](https://github.com/PikaMug/Quests)|MIT（分发协议待复核）/ A/B|活跃（2026\-08，支持 1\.21\.11）|公开事件 PlayerStartTrackQuestEvent 等；YAML 任务目标|是|中等|cc\-quest 任务引擎|**可直接联动**|已选；复核分发 jar 与源码 MIT 是否一致即可|
|[PulseEvents](https://github.com/plugglab/PulseEvents)|MIT（CurseForge 标注）/ A|活跃（v3\.2，2026\-08）|模块化事件 API；BossBar/GUI；奖励挂钩|是|简单（事件模块开关）|cc\-martial 擂台调度 / cc\-events|**可直接联动**|已是擂台调度底座；CurseForge 页明示 MIT|
|[Christmas Season](https://www.curseforge.com/minecraft/bukkit-plugins/christmas-season)|MIT / B|活跃（2026\-07，Java 21）|无公开 API；完整季节活动包|是（原版雪/礼物）|简单（换皮中式新春）|cc\-events 季节活动|**可直接联动**|独立运行即活动模板，无需 ChiliCraft 对接|
|[VortexMobs](https://github.com/Sauron05/VortexMobs)|Apache\-2\.0（LICENSE 待复核）/ B/A|活跃（2026\-05）|词缀注入器；原版怪增强|是|中等|cc\-demon 饿魔|**可直接联动**（已 B/A）|复核根 LICENSE 后可嵌代码|
|[SinceDungeon](https://github.com/SinceRPG/SinceDungeon)|MIT / B|活跃（2026\-08）|实例化副本 API；队伍数据隔离|待复核（是否付费资源）|中等|cc\-adventure 地城|**可直接联动**（已 B）|复核 SpigotMC "premium\-grade" 是否付费分发包|
|[Hydration](https://hangar.papermc.io/voidspace1x/Hydration)|MIT / A/B|新（2026\-07，Paper 26\.x）|无公开 API；0\-100 口渴状态机|是（ActionBar\+粒子）|简单|cc\-survival 体温范式|**可直接联动**（已 A/B）|要求 Java 25/Paper 26\.x，老版本服需降级|
|[QuestTracker](https://github.com/BekoLolek/QuestTracker)|待复核 / C|较新（v1\.1，2026\-06）|GUI 接任务；无公开 PAPI|是|简单（难度分级）|cc\-quest GUI|**联动有门槛**|独立运行即可；与 PikaMug/BetonQuest 重叠，不推荐|
|[QuestLogs](https://modrinth.com/plugin/quest-logs)|待复核 / C|较新（2026\-01）|无公开 API；箱子 GUI 展示任务|是|简单|cc\-quest 旅途手册参考|**联动有门槛**|旅途手册 GUI 仅数十行，自研更简单，不软依赖|
|[GraveStonesPlus](https://github.com/BenCodez/GraveStonesPlus)|待复核 / C|活跃（2026\-04/07 Jenkins）|无公开 API；墓碑存物品|是（原版方块/头颅）|简单|cc\-soul 墓碑|**联动有门槛**|与 cc\-soul"掉 50%\+葬礼"方向相反；仅参考，不软依赖|
|[Chronicles](https://github.com/juniodevs/Chonicle-Plugin)|待复核 / C|较新（1\.0\.0，2026\-01）|无公开 API；自动监听事件记 lore 历史|是|简单|cc\-soul 遗物流转史|**联动有门槛**|它用 lore 存历史，ChiliCraft 要求 PDC；自研 PDC 写入更可控|
|[Death\-Penalty](https://hangar.papermc.io/Ikkino/Death-Penalty)|待复核 / C|较新（2026\-05）|无公开 API；可配置死亡惩罚图腾|是|简单|cc\-soul 死亡惩罚|**联动有门槛**|cc\-soul 50% 掉率\+灵魂经济是自研数值，不软依赖|
|[Tracked Items](https://modrinth.com/plugin/trackeditems)|待复核 / C|较新（2026\-05）|无公开 API；lore 标原主/流转状态|是|简单|cc\-soul 遗物归属|**联动有门槛**|同上，改用 PDC 自研|
|[HereDed](https://modrinth.com/plugin/hereded)|待复核 / C|较新（2026\-06）|无公开 API；双模式死亡结算|是|简单|cc\-soul 墓碑形态|**联动有门槛**|无灵魂经济/葬礼机制，不软依赖|
|[EmakiCooking](https://github.com/jiuwu02/Emaki_Series)|待复核 / B/C|活跃（3\.3\.0，2026\-06，中文）|无公开 API；七工作站\+YAML 食谱|**需复核**（可能自定义方块模型）|中等|cc\-cozy 料理|**联动有门槛**|若工作站强制资源包则违反零资产，**必须先复核资产**；否则可独立运行当料理系统|
|[Craftorithm](https://github.com/YufiriaMazenta/Craftorithm)|待复核 / C|活跃（2026\-09，支持 26\.3/Folia）|脚本化合成；无公开 PAPI|否（需复核）|复杂（脚本配置）|cc\-cozy 料理|**联动有门槛**|脚本配置对非技术主创过重；recipes\.yml 自研更可控|
|[master3716/skills](https://github.com/master3716/skills)|待复核 / B/C|较新（v1\.1\.4，2025\-10）|无公开 API；Farming/Combat/Fishing 等级|是|简单|cc\-cozy 农业等级|**联动有门槛**|与 AuraSkills（GPL 但 API 成熟）重叠；二选一建议 AuraSkills|
|[WorldMood](https://github.com/Rex-Micky/WorldMood-Plugin)|待复核 / B/C|新（v1\.3\.0，2026\-07）|无公开 API；7 种定时 mood 改世界规则|是|中等|cc\-events 世界事件|**可直接联动**|独立运行即赛季/梦境入侵类事件，无需 ChiliCraft 对接|
|[BloodMoon\-Reborn](https://github.com/julion12/BloodMoon-Reborn)|待复核 / B/C|新（v1\.0\.1，2026\-07）|无公开 API；定时血月\+夜间刷怪增强|是（原版天气）|简单|cc\-events 梦境入侵|**可直接联动**|独立运行即夜间限时事件模板|
|[Meteor\-Shower](https://github.com/benardamorkoc/Meteor-Shower)|待复核 / B/C|2025\-08|无公开 API；定时陨石\+粒子\+爆炸|是|简单|cc\-events 流星雨|**可直接联动**|独立运行即流星雨事件|
|[Meteoriti](https://github.com/Cubixon-McD/Meteoriti)|待复核 / B/C|新（0\.0\.1，2026\-08）|无公开 API；贝塞尔轨迹\+屏幕震动|是|简单|cc\-events 流星雨|**联动有门槛**|alpha 阶段，稳定性待观察；Meteor\-Shower 更成熟|
|[EzSeasons](https://github.com/ez-plugins/EzSeasons)|推断 MIT（待复核）/ B|活跃（v2\.1\.2，2026\-04）|无公开 API；按日历/周期触发任务|是|简单|cc\-events 调度器|**可直接联动**|EzPlugins 系普遍 MIT；独立运行做 events\.yml 调度|
|[ErrorShop](https://github.com/error0403/ErrorShop)|待复核 / C|较新（2026\-05）|无公开 API；轻量商店\+玩家市场\+自定义菜单|是|简单|cc\-economy NPC 商店|**联动有门槛**|与 EzAuction（MIT B/A）重叠；优先 EzAuction|
|[Citizens2](https://github.com/CitizensDev/Citizens2)|待复核（GPL 系）/ A|极活跃|NPC API；聊天气泡；被 NotQuests/BetonQuest 原生兼容|是（原版生物模型）|中等（NPC 生成指令）|cc\-cozy / cc\-events NPC|**可直接联动**（已拍板）|已在清单内；仅软依赖不抄码，按 GPL 系处置|

### 8\.5 开发库/框架类候选（不走联动路线）

> 下列项目是**开发库/插件框架**而非终端运行插件，由 ChiliCraft 工程直接 shade/嵌入或作为 Maven 依赖引入，不存在"软依赖联动"场景。它们已在第四章通用基础件中按 A/B/C 档处置，此处不再重评：DzusillCore、KLibrary、Lattice、QinhCoreLib、AuMenus、SmartMenus\-V2、Despical/CommandFramework、SunDB、BKCommonLib、AlpineCore、MorePersistentDataTypes、ConfigLib、AnhyLibAPI、PeachLib、lucko/helper、Guava EventBus、SmartInvs（原版停更，仅参考）、LuckPerms Storage 层（LuckPerms 本体已拍板软依赖权限）。
> 
> 

### 8\.6 联动落地建议（叠加在原第七节路线图之上）

1. **第 2 步（survival\+demon）新增软依赖**：体温直接用 **ThermoSurvival**（独立运行，不嵌码）；负重直接用 **Inventory Weight**（闭源但数值模型一致，配重量表即可）；饿魔怪物 VortexMobs（B）\+ EliteMobs（A 软依赖世界 Boss）\+ MythicMobs（A 已拍板）三层分工。

2. **第 3 步（martial\+soul\+adventure）新增软依赖**：擂台 PulseEvents（A）；地城 SinceDungeon（B）\+ DungeonGates（A/B）\+ EliteMobs（A 世界 Boss）；灵魂部分仍以 SoulGraves（B）为核心，其余墓碑/遗物插件不软依赖。

3. **第 4 步（quest）新增软依赖**：主线 **BetonQuest（A，需法务确认）** 或 **PikaMug/Quests（A/B，MIT）** 二选一；NotQuests（A）仅在需要声望=NPC 好感时启用。

4. **第 5 步（cozy\+events\+economy）新增软依赖**：农业等级直接 **AuraSkills（A）** 承接 Farming/Fishing；季节活动 **Christmas Season（B）** 换皮；世界事件 WorldMood/BloodMoon/Meteor\-Shower（独立运行）；经济层 Vault\+QuickShop\-Hikari\+ChestShop\-3\+EzAuction\+Smiley/CrossTrade 已齐；Jobs Reborn **不软依赖**（CMILib 闭源\+货币冲突），6 职业自研。

> **AGPL/GPL 使用警示（重申）**：QuickShop\-Hikari（AGPL\-3\.0）、EliteMobs/BetonQuest/Jobs Reborn/NotQuests/AuraSkills/CustomCrafting/DailyEvent/GoodsTrade/BattleArena/OhTheDungeon（GPL）、Vault/ChestShop\-3/MyPet（LGPL）均**只能作为独立插件由服主安装运行**，ChiliCraft 通过其公开 API/事件/PAPI/Vault 对接；**严禁反编译、修改、复制其源码进 ChiliCraft 闭源工程**，严禁将其 jar 打包进 ChiliCraft 分发。
> 
> 

> （注：部分内容可能由 AI 生成）
