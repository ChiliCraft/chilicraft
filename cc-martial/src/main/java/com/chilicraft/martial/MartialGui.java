package com.chilicraft.martial;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * /martial 主面板与三大子面板门面（流派 / 技能 / 擂台）。
 *
 * <p>复用 cc-core 的 GuiHolder/GuiListener（instanceof 识别，本模块零监听器）；
 * 快照式菜单——每次打开全新构建，动作完成后重建刷新（cc-adventure 同范式）。
 * 消息键平铺 kebab-case（MartialSettings 消息为浅键加载，禁止嵌套小节）。</p>
 */
final class MartialGui {

    private final JavaPlugin plugin;
    private final MartialSettings settings;
    private final RealmService realms;
    private final SkillService skills;
    private final SchoolGui schoolGui;
    private final SkillGui skillGui;
    private final ArenaGui arenaGui;

    MartialGui(JavaPlugin plugin, MartialSettings settings, RealmService realms,
               SkillService skills, ArenaService arenas) {
        this.plugin = plugin;
        this.settings = settings;
        this.realms = realms;
        this.skills = skills;
        this.schoolGui = new SchoolGui(settings, skills, this);
        this.skillGui = new SkillGui(settings, realms, skills, this);
        this.arenaGui = new ArenaGui(settings, arenas, this);
    }

    /** 打开主面板（主线程）：槽 4 状态头 / 10 流派 / 12 技能 / 14 擂台 / 16 手册 / 22 关闭 */
    void openMain(Player player) {
        GuiHolder holder = new GuiHolder(27,
                MartialGuiItems.text(settings, "gui-title-main", "<dark_gray>武学菜单"));

        holder.set(4, statusHead(player));

        // 流派
        PlayerSkillData data = skills.data(player.getUniqueId());
        String schoolId = data != null ? data.school : null;
        SkillContent.SchoolDef school = schoolId != null
                ? skills.content().schools.get(schoolId) : null;
        List<Component> schoolLore = new ArrayList<>();
        if (school != null) {
            schoolLore.add(MartialGuiItems.text(settings, "gui-main-school-lore-joined",
                    "<gray>当前流派：<white><school></gray>")
                    .replaceText(b -> b.matchLiteral("<school>").replacement(
                            MartialGuiItems.raw(school.display))));
        } else {
            schoolLore.add(MartialGuiItems.text(settings, "gui-main-school-lore-none",
                    "<gray>尚未拜师，点击选择流派"));
        }
        holder.set(10, MartialGuiItems.button(
                        school != null ? school.vanillaWeapon : Material.WOODEN_SWORD,
                        MartialGuiItems.text(settings, "gui-main-school-name", "<gold>流派"),
                        schoolLore),
                (p, type) -> openSchool(p));

        // 技能
        List<Component> skillLore = new ArrayList<>();
        if (school != null) {
            String selectedDisplay = "-";
            if (data != null && data.selected != null) {
                SkillContent.SkillDef def = skills.content().skills.get(data.selected);
                selectedDisplay = def != null ? def.display : data.selected;
            }
            final Component selectedRaw = MartialGuiItems.raw(selectedDisplay);
            int learned = data != null ? data.skills.size() : 0;
            skillLore.add(MartialGuiItems.text(settings, "gui-main-skills-lore",
                    "<gray>已学 <count> 门 · 施展：<selected></gray>")
                    .replaceText(b -> b.matchLiteral("<count>").replacement(String.valueOf(learned)))
                    .replaceText(b -> b.matchLiteral("<selected>").replacement(selectedRaw)));
        } else {
            skillLore.add(MartialGuiItems.text(settings, "gui-main-skills-lore-none",
                    "<gray>拜入流派后可领悟技能"));
        }
        holder.set(12, MartialGuiItems.button(Material.BOOK,
                        MartialGuiItems.text(settings, "gui-main-skills-name", "<gold>技能"),
                        skillLore),
                (p, type) -> openSkills(p));

        // 擂台
        holder.set(14, MartialGuiItems.button(Material.GOLDEN_HELMET,
                        MartialGuiItems.text(settings, "gui-main-arena-name", "<gold>每日擂台"),
                        List.of(MartialGuiItems.text(settings, "gui-main-arena-lore",
                                "<gray>报名、规则与冠军奖励"))),
                (p, type) -> openArena(p));

        // 武学手册：已拜师且无手册时点击补领
        List<Component> handbookLore = new ArrayList<>();
        boolean canClaim = school != null && !skills.hasHandbook(player);
        if (school == null) {
            handbookLore.add(MartialGuiItems.text(settings, "gui-main-handbook-lore-locked",
                    "<gray>拜入流派后获得"));
        } else if (canClaim) {
            handbookLore.add(MartialGuiItems.text(settings, "gui-main-handbook-lore-claim",
                    "<yellow>手册遗失？点击补领"));
        } else {
            handbookLore.add(MartialGuiItems.text(settings, "gui-main-handbook-lore-have",
                    "<gray>右键施展 · 潜行+右键切换"));
        }
        holder.set(16, MartialGuiItems.button(settings.handbookMaterial,
                        MartialGuiItems.text(settings, "gui-main-handbook-name", "<gold>武学手册"),
                        handbookLore),
                (p, type) -> {
                    if (canClaim) {
                        skills.giveHandbookTo(p);
                        openMain(p);
                    }
                });

        holder.set(22, MartialGuiItems.button(Material.BARRIER,
                        MartialGuiItems.text(settings, "gui-close", "<red>关闭"), List.of()),
                (p, type) -> p.closeInventory());
        holder.open(player);
    }

    void openSchool(Player player) {
        schoolGui.open(player);
    }

    void openSkills(Player player) {
        skillGui.open(player);
    }

    void openArena(Player player) {
        arenaGui.open(player);
    }

    /** 状态头：境界 / 加成 / 计数 / 下一境界进度（数据缺失退化为默认展示） */
    private ItemStack statusHead(Player player) {
        PlayerMartialData data = realms.data(player.getUniqueId());
        int realm = data != null ? data.realm : 0;
        List<Component> lore = new ArrayList<>();
        lore.add(MartialGuiItems.text(settings, "gui-main-status-realm",
                "<gray>境界：<yellow><realm></yellow>")
                .replaceText(b -> b.matchLiteral("<realm>").replacement(
                        MartialGuiItems.raw(settings.realmDisplay[realm]))));
        lore.add(MartialGuiItems.text(settings, "gui-main-status-bonus",
                "<gray>伤害 +<dmg>% · 减伤 <dr>% · 生命 +<hp></gray>")
                .replaceText(b -> b.matchLiteral("<dmg>").replacement(
                        trimPct(settings.meleeDamagePct[realm])))
                .replaceText(b -> b.matchLiteral("<dr>").replacement(
                        trimPct(settings.damageReductionPct[realm])))
                .replaceText(b -> b.matchLiteral("<hp>").replacement(
                        String.valueOf(settings.maxHealthBonus[realm]))));
        if (data != null && realm < 5) {
            lore.add(MartialGuiItems.text(settings, "gui-main-status-progress",
                    "<gray>击杀 <kills> · 地城 <dungeons> · Boss <bosses> · 擂台 <arena></gray>")
                    .replaceText(b -> b.matchLiteral("<kills>").replacement(String.valueOf(data.kills)))
                    .replaceText(b -> b.matchLiteral("<dungeons>").replacement(String.valueOf(data.dungeons)))
                    .replaceText(b -> b.matchLiteral("<bosses>").replacement(String.valueOf(data.bosses)))
                    .replaceText(b -> b.matchLiteral("<arena>").replacement(String.valueOf(data.arenaWins))));
            lore.add(MartialGuiItems.text(settings, "gui-main-status-next",
                    "<gray>下一境界：<yellow><next></yellow>（<progress>）</gray>")
                    .replaceText(b -> b.matchLiteral("<next>").replacement(
                            MartialGuiItems.raw(settings.realmDisplay[realm + 1])))
                    .replaceText(b -> b.matchLiteral("<progress>").replacement(
                            MartialGuiItems.raw(nextProgress(data, realm + 1)))));
        } else {
            lore.add(MartialGuiItems.text(settings, "gui-main-status-max",
                    "<gold>已臻武学极致"));
        }
        return MartialGuiItems.head(player.getUniqueId(),
                MartialGuiItems.text(settings, "gui-main-status-name", "<aqua>我的武学"),
                lore);
    }

    /** 下一境界进度文本（与 RealmService.nextRealmUnlocked 同口径，仅展示用） */
    private String nextProgress(PlayerMartialData data, int next) {
        return switch (next) {
            case 1 -> data.kills + "/" + settings.unlockKillThreshold;
            case 2 -> data.dungeons + "/" + settings.unlockDungeonClears;
            case 3 -> data.bosses + "/" + settings.unlockBossKills;
            case 4 -> data.arenaWins + "/" + settings.unlockArenaWins;
            case 5 -> (data.worldBoss ? 1 : 0) + "/1";
            default -> "-";
        };
    }

    private static String trimPct(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
