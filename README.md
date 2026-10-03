# ChiliCraft

ChiliChill 2026 巡演「混入人类计划 II：方街」主题 Minecraft 服务器插件组，运行于 **Paper 1.21.1**。核心提供档案 / 全局模式 / 参数服务 / 事件总线 / 灵魂货币等基础能力，各玩法以附属模块形式挂接，通过事件总线与参数服务联动，互不硬依赖。

## 模块总览

| 模块 | 状态 | 职责 |
|---|---|---|
| `cc-core` | 已实现 | 核心：档案、全局模式（adventure/cozy）、参数服务、事件总线、灵魂货币、家园区、DB、`/menu` 与 `/cc` 统一入口、分类菜单 |
| `cc-survival` | 已实现 | 生存压力：饥饿加速、体温、负重、耐久损耗；统一入口 `/cc survival`，提供只读状态页 |
| `cc-demon` | 已实现 | 饿魔：夜晚刷怪与索敌、饿意事件；统一入口 `/cc demon`，兼容 `/demon`，普通玩家查看状态、管理员保留管理 GUI |
| `cc-soul` | 已实现 | 灵魂：死亡灵魂损失与遗物、遗物葬礼、收容播报；统一入口 `/cc soul menu`，兼容 `/soul` |
| `cc-martial` | 已实现 | 武学：竞技场对战、武学境界、技能与演出；统一入口 `/cc martial`，兼容 `/martial` |
| `cc-adventure` | 已实现（M1） | 冒险：地城、远征与世界 Boss；统一入口 `/cc adventure`，兼容 `/adventure`；内容文件 `dungeons.yml` / `bosses.yml` |
| `cc-season` | 已实现（v1） | 季节日历、天气、生态与指南；统一入口 `/cc season`，兼容 `/season`、`/ccseason`；注册 `%ccseason_*%` 占位符 |
| `cc-quest` | 已实现（首版） | 任务与章节状态、事件推进、灵魂奖励、旅途手册；统一入口 `/cc quest` |
| `cc-street` | **规划中** | 方街：规划入口 `/cc street` 在分类菜单和 Tab 中灰显并提示开发中，业务尚未实现 |

## 快速开始

### 克隆与初始化子仓

本仓保留聚合构建；八个 `cc-*` 插件仓和 [`ChiliCraft/docs`](https://github.com/ChiliCraft/docs) 文档仓均以 Git 子模块挂在原目录，提交版本由父仓固定。

首次克隆时一次拉齐全部子仓：

```bash
git clone --recurse-submodules https://github.com/ChiliCraft/chilicraft.git
```

已有克隆可运行统一初始化入口（重复运行安全，不会自动追踪子仓最新提交）：

```bash
bash scripts/init-repos.sh
```

Windows PowerShell：

```powershell
.\scripts\init-repos.ps1
```

也可直接执行 `git submodule update --init --recursive`。插件子仓保留独立构建与 CI；从父仓构建时，`com.chilicraft:cc-core` 自动替换为本地 `:cc-core`，无需先发布核心到 Maven。修改模块时先在对应子仓提交并推送，再在父仓更新该路径的 gitlink；文档改动同理在 `docs/` 内提交，避免父仓引用尚未推送的提交。

### 环境

- JDK 21（Gradle 工具链，缺失时由 Foojay resolver 自动下载；编译目标 Java 21 字节码）
- Gradle Wrapper 自带，无需本地安装 Gradle
- 中文 Windows 下请用项目自带配置——GBK 编码坑已在根 [build.gradle.kts](build.gradle.kts) 注释中说明并规避

### 构建

```powershell
# 常规构建（需可访问 repo.papermc.io，国内网络需代理）
.\gradlew.bat build

# 依赖已缓存时可用离线模式（SNAPSHOT 元数据刷新失败时的兜底）
.\gradlew.bat build --offline
```

构建产物在各模块 `build/libs/*.jar`（cc-core 额外产出 shadowJar，已合并 shade 依赖）。执行 `.\gradlew.bat dist` 会把全部模块 jar 与 `test-server/plugins/` 下的运行时软依赖汇总到根 `build/dist/`，解压即为一层裸 jar，可直接整体丢进服务端 `plugins/`。

每次 push 由 [.github/workflows/build.yml](.github/workflows/build.yml) 递归检出固定版本的全部子仓，并在 JDK 21 上自动执行 `gradlew build dist`；模块通过 Maven 坐标解析编译期依赖，CI 另外补齐 `test-server/plugins/` 下的第三方运行时软依赖 jar（这些 jar 不入库），由 `dist` 任务把各模块 jar 与软依赖汇总到同一扁平目录，上传为构建产物。

### 部署

1. 将全部 jar 放入服务端 `plugins/` 目录；
2. 启动一次生成各模块默认配置（`plugins/<模块名>/config.yml`）；
3. 按需修改配置后 `/cc reload` 即时生效（配置经 `core.reload` 事件热载，无需重启）。

加载顺序无需手工干预：核心无依赖，全部附属声明 `depend: [cc-core]`；附属启动时通过 `ServicesManager` 取核心 API，取不到则自动禁用并提示。统一入口由核心集中路由：`/menu`、`/cc`、`/cc menu` 打开同一分类主菜单，`/cc <module> [子命令]` 分发到已启用模块；`/chilicraft` 继续作为 `/cc` 别名。旧的 `/demon`、`/soul`、`/martial`、`/adventure`、`/season`、`/ccseason` 均保留。规划中的 `quest`、`cozy`、`events`、`economy`、`street` 只显示灰显入口并提示开发中，不伪造业务功能。

统一入口权限为 `chilicraft.menu`（兼容 `chilicraft.use`）；模块使用权限采用 `chilicraft.<module>.use`，并兼容现有旧 `user` 节点，管理操作仍由模块代码二次校验 `chilicraft.<module>.admin`（季节另兼容 `ccseason.admin`）。Tab 补全按前缀、可用性和权限过滤，不向普通玩家暴露管理子命令。

## 巡演联动一览（已落地部分）

| 联动点 | 模块 | 机制 |
|---|---|---|
| 巡演地城 ×10 | cc-adventure | `dungeons.yml`：守锅 / 献祭 / 承重 / 水下 / 双界五机制各两座，命名取自深夜食堂、眼镜的葬礼、山遥路远、水做的回廊、方街镜面巷口等主题 |
| 远征祝福 / 诅咒池 | cc-adventure | `config.yml` `expedition.blessings/curses`：让风告诉你（移速）、飞鸟说（跳跃）、饿魔注视（饥饿）等曲目命名 |
| 世界 Boss ×5 | cc-adventure | `bosses.yml`：饿魔母体·真身按玩家夜间连续死亡触发（逐人累计），水做之影 / 台风之眼 / 梦魇按环境概率触发；幻形鹦鹉 `RANDOM` 0.1%/60tick，概率触发型统一套用「能力值」（`config.yml` `boss.capability`：背包攻击/防御/击败/账号年龄/生存五项相乘，`/adventure boss debug` 查看分项） |
| 旧眼镜祭品 | cc-soul | 葬礼仪式主手持 SPYGLASS 上供，站桩时长 ×0.5（`funeral.offerings.old-glasses`） |
| 《不安灵魂收容所》 | cc-soul | 死亡播报以收容所口吻命名（`messages.death-sanctuary`，模板缺失静默） |
| 「下等马」安慰礼 | cc-martial | 竞技场败者获灵魂安慰礼（`arena.loser-consolation-souls`，0 关闭） |
| 「演」夺冠演出 | cc-martial | 冠军全服标题「演」＋音效（`arena.champion-show`） |
| 方街安全区·停刷怪 | cc-demon | `spawn.excluded-worlds` 名单内世界不刷饿魔、不索敌；入街时额外清除玩家周围存量饿魔 |
| 方街安全区·压力减半 | cc-survival | `street.worlds` 名单内世界饥饿/燥热/失温伤害 ×`pressure-multiplier`，入街发送提示 |

以上安全区联动依赖 cc-street 发布的 `street.world_enter` 事件（字符串订阅，无需编译期依赖）；cc-street 未上线前，名单机制本身已可用（按世界名配置）。

### cc-season 占位符

安装 PlaceholderAPI 后，cc-season 注册：`%ccseason_season%`、`%ccseason_year%`、`%ccseason_day%`、`%ccseason_days_per_season%`。PlaceholderAPI 缺失时仅跳过注册，不影响季节功能。

## 全局占位符（PlaceholderAPI）

安装 PlaceholderAPI 后 cc-core 自动注册标识符 `chilicraft` 的占位符（`integrations.placeholders` 配置可关闭）：

| 占位符 | 说明 |
|---|---|
| `%chilicraft_soul%` | 灵魂余额 |
| `%chilicraft_mode%` | 全局模式（adventure / cozy） |
| `%chilicraft_realm%` | 武学境界等级（0 = 未入门） |
| `%chilicraft_profession%` | 职业标识（空 = 无职业） |
| `%chilicraft_season_points%` | 赛季积分 |
| `%chilicraft_home_set%` | 是否已设置家园区锚点（true / false） |

占位符只读核心档案；模块内部数值由对应附属自建 expansion 暴露（已实现：`%chilimartial_*%` 武学变量，见 [MartialExpansion](cc-martial/src/main/java/com/chilicraft/martial/MartialExpansion.java)）。

## 文档地图

| 文档 | 内容 |
|---|---|
| [docs/architecture.md](docs/architecture.md) | 架构总览：分层、API 注册模式、事件总线、参数流、持久化 |
| [docs/module-dev-guide.md](docs/module-dev-guide.md) | 模块开发指南：从零新增一个附属的完整步骤与代码范式 |
| [docs/events-protocol.md](docs/events-protocol.md) | 事件与数据协议：EventData 契约、全量事件清单、发布/订阅范式 |
| [docs/performance.md](docs/performance.md) | 性能红线：周期任务纪律、缓存纪律、DB 线程契约 |
| [docs/development-plan.md](docs/development-plan.md) | 后续开发计划：现状差距、M0–M5 里程碑排期、风险与验收标准 |
| [docs/copywriting-requests.md](docs/copywriting-requests.md) | T0.6 内容提请：《文案手册》与数值校准需求清单（按里程碑分四批，可直接转交主创） |
| [AGENTS.md](AGENTS.md) | AI agent 操作指引：构建/验收/约定速查 |
| [ChiliCraft·插件版技术文档（实现规格 v1.1）](<docs/ChiliCraft·插件版技术文档（实现规格 v1.1）.md>) | 完整实现规格（v1.1，含 cc-street 规格与巡演映射表） |
