package com.chilicraft.martial;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 擂台面板：槽 4 状态头 / 11 报名 / 13 统一装备说明 / 15 取消报名 / 22 返回。
 *
 * <p>报名/取消仅在对应状态展示按钮（IDLE 不显示操作钮）；点击走
 * ArenaService.join/quitArena（服务层自带聊天反馈），完成后重建刷新。
 * 只读访问器均为主线程快照，无并发风险。</p>
 */
final class ArenaGui {

    private final MartialSettings settings;
    private final ArenaService arenas;
    private final MartialGui main;

    ArenaGui(MartialSettings settings, ArenaService arenas, MartialGui main) {
        this.settings = settings;
        this.arenas = arenas;
        this.main = main;
    }

    void open(Player player) {
        GuiHolder holder = new GuiHolder(27,
                MartialGuiItems.text(settings, "gui-title-arena", "<dark_gray>每日擂台"));

        holder.set(4, statusHead(player.getUniqueId()));

        boolean signup = arenas.signupOpen();
        boolean signed = arenas.signedUp(player.getUniqueId());
        if (signup && !signed) {
            holder.set(11, MartialGuiItems.button(Material.EMERALD,
                            MartialGuiItems.text(settings, "gui-arena-join-name", "<green>报名"),
                            List.of(MartialGuiItems.text(settings, "gui-arena-join-lore",
                                    "<gray>点击报名本届擂台"))),
                    (p, type) -> {
                        arenas.join(p);
                        main.openArena(p);
                    });
        } else if (signup) {
            holder.set(15, MartialGuiItems.button(Material.REDSTONE,
                            MartialGuiItems.text(settings, "gui-arena-quit-name", "<red>取消报名"),
                            List.of(MartialGuiItems.text(settings, "gui-arena-quit-lore",
                                    "<gray>点击取消本届报名"))),
                    (p, type) -> {
                        arenas.quitArena(p);
                        main.openArena(p);
                    });
        }

        // 统一装备说明（kit 项缺失时不展示）
        List<Component> kitLore = new ArrayList<>();
        kitLore.add(MartialGuiItems.text(settings, "gui-arena-lore-rule",
                "<gray>统一装备，赛后归还随身物品"));
        kitLore.add(settings.arenaAllowSkills
                ? MartialGuiItems.text(settings, "gui-arena-lore-skills-allowed",
                        "<gray>允许施展武学技能")
                : MartialGuiItems.text(settings, "gui-arena-lore-skills-denied",
                        "<red>禁止施展武学技能"));
        holder.set(13, MartialGuiItems.button(settings.arenaKitWeapon,
                MartialGuiItems.text(settings, "gui-arena-rule-name", "<gold>擂台规则"),
                kitLore));

        holder.set(22, MartialGuiItems.button(Material.ARROW,
                        MartialGuiItems.text(settings, "gui-back", "<gray>返回"), List.of()),
                (p, type) -> main.openMain(p));
        holder.open(player);
    }

    /** 状态头：按阶段（idle/signup/running）展示，奖励行恒显 */
    private ItemStack statusHead(UUID playerId) {
        List<Component> lore = new ArrayList<>();
        if (arenas.signupOpen()) {
            lore.add(MartialGuiItems.text(settings, "gui-arena-lore-signup",
                    "<gold>报名中！开打剩余 <seconds> 秒 · 已报 <count> 人")
                    .replaceText(b -> b.matchLiteral("<seconds>").replacement(
                            String.valueOf(arenas.signupRemainSeconds())))
                    .replaceText(b -> b.matchLiteral("<count>").replacement(
                            String.valueOf(arenas.signupCount()))));
        } else if (arenas.running()) {
            String fight = arenas.currentFight();
            lore.add(MartialGuiItems.text(settings, "gui-arena-lore-running",
                    "<gold>比赛进行中：当前对局 <fight>")
                    .replaceText(b -> b.matchLiteral("<fight>").replacement(
                            fight != null ? Component.text(fight) : Component.text("-"))));
        } else {
            lore.add(MartialGuiItems.text(settings, "gui-arena-lore-idle",
                    "<gray>当前未开放报名 · 每日 <times> 开打")
                    .replaceText(b -> b.matchLiteral("<times>").replacement(
                            Component.text(String.join(" / ", settings.arenaTimes)))));
        }
        String extra = settings.championMoney > 0 && settings.integrationVault ? " + 金钱" : "";
        lore.add(MartialGuiItems.text(settings, "gui-arena-lore-reward",
                "<gray>冠军奖励：<souls> 灵魂<extra>")
                .replaceText(b -> b.matchLiteral("<souls>").replacement(
                        String.valueOf(settings.championSouls)))
                .replaceText(b -> b.matchLiteral("<extra>").replacement(
                        Component.text(extra))));
        return MartialGuiItems.head(playerId,
                MartialGuiItems.text(settings, "gui-arena-status-name", "<aqua>每日擂台"),
                lore);
    }
}
