package com.chilicraft.soul;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * /soul 主面板门面（状态概览 / 碎片导航 / 死亡记录 / 遗物图鉴 / 葬礼仪式）。
 *
 * <p>复用 cc-core 的 GuiHolder/GuiListener（instanceof 识别，本模块零监听器）；
 * 快照式菜单——每次打开全新构建，动作完成后重建刷新（cc-adventure / cc-martial
 * 同范式）。消息键平铺 kebab-case（SoulSettings 消息为浅键加载，禁止嵌套小节）。</p>
 */
final class SoulGui {

    private final SoulSettings settings;
    private final ChiliCraftAPI api;
    private final RelicService relics;
    private final FragmentService fragments;
    private final DeathService deaths;
    private final FuneralService funeral;
    private final RelicGui relicGui;

    SoulGui(SoulSettings settings, ChiliCraftAPI api, RelicService relics,
            FragmentService fragments, DeathService deaths, FuneralService funeral) {
        this.settings = settings;
        this.api = api;
        this.relics = relics;
        this.fragments = fragments;
        this.deaths = deaths;
        this.funeral = funeral;
        this.relicGui = new RelicGui(settings, relics, this);
    }

    /** 打开主面板（主线程）：槽 4 状态头 / 11 碎片 / 13 死亡 / 15 图鉴 / 16 葬礼 / 22 关闭 */
    void openMain(Player player) {
        GuiHolder holder = new GuiHolder(27,
                SoulGuiItems.text(settings, "gui-title-main", "<dark_gray>灵魂菜单"));

        holder.set(4, statusHead(player));

        // 碎片导航：反馈由服务层 sendNav 统一给出（与 /soul fragments 同口径），随后重建刷新
        holder.set(11, SoulGuiItems.button(Material.ECHO_SHARD,
                        SoulGuiItems.text(settings, "gui-main-fragments-name", "<gold>灵魂碎片"),
                        List.of(SoulGuiItems.text(settings, "gui-main-fragments-lore",
                                "<gray>点击导航到最近一片碎片"))),
                (p, type) -> {
                    fragments.sendNav(p);
                    openMain(p);
                });

        holder.set(13, deathButton(player));

        holder.set(15, SoulGuiItems.button(Material.RECOVERY_COMPASS,
                        SoulGuiItems.text(settings, "gui-main-relics-name", "<gold>遗物图鉴"),
                        List.of(SoulGuiItems.text(settings, "gui-main-relics-lore",
                                "<gray>查看 12 遗物与在场状态"))),
                (p, type) -> openRelics(p));

        // 葬礼：发起与中断的反馈走 FuneralService.start，费用 / 召回率读配置展示
        List<Component> funeralLore = new ArrayList<>();
        funeralLore.add(SoulGuiItems.text(settings, "gui-main-funeral-lore-cost",
                "<gray>费用：<yellow><cost></yellow> 灵魂</gray>")
                .replaceText(b -> b.matchLiteral("<cost>").replacement(
                        String.valueOf(settings.funeralCost))));
        funeralLore.add(SoulGuiItems.text(settings, "gui-main-funeral-lore-rate",
                "<gray>召回率：基础 <pct>%（篝火 / 月亮井 / 死亡点可加成）</gray>")
                .replaceText(b -> b.matchLiteral("<pct>").replacement(
                        trimPct(settings.funeralRecoveryBasePct))));
        funeralLore.add(SoulGuiItems.text(settings, "gui-main-funeral-lore-relic",
                "<gray>手持沉眠遗物可送别入土</gray>"));
        if (funeral.activePlayers().contains(player.getUniqueId())) {
            funeralLore.add(SoulGuiItems.text(settings, "gui-main-funeral-lore-active",
                    "<gold>仪式进行中，站定等待……</gold>"));
        } else {
            funeralLore.add(SoulGuiItems.text(settings, "gui-main-funeral-lore-none",
                    "<yellow>点击开始仪式</yellow>"));
        }
        holder.set(16, SoulGuiItems.button(Material.CAMPFIRE,
                        SoulGuiItems.text(settings, "gui-main-funeral-name", "<gold>葬礼仪式"), funeralLore),
                (p, type) -> {
                    funeral.start(p);
                    openMain(p);
                });

        holder.set(22, SoulGuiItems.button(Material.BARRIER,
                SoulGuiItems.text(settings, "gui-close", "<red>关闭"), List.of()),
                (p, type) -> p.closeInventory());
        holder.open(player);
    }

    void openRelics(Player player) {
        relicGui.open(player);
    }

    /** 状态头：灵魂余额 / 待拾碎片 / 葬礼状态 */
    private ItemStack statusHead(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(SoulGuiItems.text(settings, "gui-main-status-soul",
                "<gray>灵魂余额：<aqua><soul></gray>")
                .replaceText(b -> b.matchLiteral("<soul>").replacement(
                        String.valueOf(api.getSoul(player.getUniqueId())))));
        lore.add(SoulGuiItems.text(settings, "gui-main-status-fragments",
                "<gray>待拾碎片：<count> 片</gray>")
                .replaceText(b -> b.matchLiteral("<count>").replacement(
                        String.valueOf(fragments.countOf(player.getUniqueId())))));
        lore.add(funeral.activePlayers().contains(player.getUniqueId())
                ? SoulGuiItems.text(settings, "gui-main-status-funeral-active",
                        "<gray>葬礼：<gold>进行中</gold></gray>")
                : SoulGuiItems.text(settings, "gui-main-status-funeral-none",
                        "<gray>葬礼：无</gray>"));
        return SoulGuiItems.head(player.getUniqueId(),
                SoulGuiItems.text(settings, "gui-main-status-name", "<aqua>我的灵魂"), lore);
    }

    /** 死亡记录按钮：有记录展示死因 / 时间 / 地点，无记录退化提示（纯展示） */
    private ItemStack deathButton(Player player) {
        DeathService.DeathRecord rec = deaths.latestDeath(player.getUniqueId());
        if (rec == null) {
            return SoulGuiItems.button(Material.SKELETON_SKULL,
                    SoulGuiItems.text(settings, "gui-main-death-name", "<gold>最近死亡"),
                    List.of(SoulGuiItems.text(settings, "gui-main-death-lore-none",
                            "<gray>暂无死亡记录</gray>")));
        }
        final Component causeRaw = SoulGuiItems.raw(DeathService.causeText(rec.cause()));
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(rec.diedAt()));
        List<Component> lore = new ArrayList<>();
        lore.add(SoulGuiItems.text(settings, "gui-main-death-lore-cause",
                "<gray>死因：<cause></gray>")
                .replaceText(b -> b.matchLiteral("<cause>").replacement(causeRaw)));
        lore.add(SoulGuiItems.text(settings, "gui-main-death-lore-time",
                "<gray>时间：<time></gray>")
                .replaceText(b -> b.matchLiteral("<time>").replacement(
                        SoulGuiItems.raw(time))));
        lore.add(SoulGuiItems.text(settings, "gui-main-death-lore-where",
                "<gray>地点：<world> <x>, <y>, <z></gray>")
                .replaceText(b -> b.matchLiteral("<world>").replacement(SoulGuiItems.raw(rec.world())))
                .replaceText(b -> b.matchLiteral("<x>").replacement(
                        String.valueOf((int) Math.floor(rec.x()))))
                .replaceText(b -> b.matchLiteral("<y>").replacement(
                        String.valueOf((int) Math.floor(rec.y()))))
                .replaceText(b -> b.matchLiteral("<z>").replacement(
                        String.valueOf((int) Math.floor(rec.z())))));
        return SoulGuiItems.button(Material.SKELETON_SKULL,
                SoulGuiItems.text(settings, "gui-main-death-name", "<gold>最近死亡"), lore);
    }

    private static String trimPct(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
