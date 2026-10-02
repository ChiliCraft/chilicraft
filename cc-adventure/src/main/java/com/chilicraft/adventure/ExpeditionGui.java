package com.chilicraft.adventure;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 远征面板（27 格）：状态头（层数/已获灵魂）+ 开始/说明/离开。
 *
 * <p>开始仅未在远征时显示，离开仅在远征中显示；错误码映射 gui.expedition-result.* 消息键。</p>
 */
final class ExpeditionGui {

    private final JavaPlugin plugin;
    private final AdventureSettings settings;
    private final ExpeditionService expeditions;
    private final AdventureGui parent;

    ExpeditionGui(JavaPlugin plugin, AdventureSettings settings,
                  ExpeditionService expeditions, AdventureGui parent) {
        this.plugin = plugin;
        this.settings = settings;
        this.expeditions = expeditions;
        this.parent = parent;
    }

    void open(Player player) {
        UUID id = player.getUniqueId();
        GuiHolder holder = new GuiHolder(27,
                GuiItems.text(settings, "gui.title-expedition", "<dark_gray>冒险 · 远征"));
        boolean running = expeditions.inExpedition(id);

        List<Component> statusLore = new ArrayList<>();
        if (running) {
            statusLore.add(GuiItems.text(settings, "gui.expedition-status-running",
                    "<gray>第 <layer>/<total> 层 · 已获灵魂 <soul>",
                    Placeholder.unparsed("layer", String.valueOf(expeditions.currentLayer(id))),
                    Placeholder.unparsed("total", String.valueOf(settings.expeditionLayers)),
                    Placeholder.unparsed("soul", String.valueOf(expeditions.soulEarned(id)))));
        } else {
            statusLore.add(GuiItems.text(settings, "gui.expedition-status-idle", "<gray>未在远征中"));
        }
        holder.set(4, GuiItems.head(id,
                GuiItems.text(settings, "gui.expedition-status-name", "<gold>远征状态"), statusLore));

        if (!running) {
            holder.set(11, GuiItems.button(Material.COMPASS,
                            GuiItems.text(settings, "gui.expedition-start-name", "<green>开始远征"),
                            List.of(GuiItems.text(settings, "gui.expedition-start-lore",
                                    "<gray>穿越 <layers> 层随机房间；队长发起，全队同行",
                                    Placeholder.unparsed("layers", String.valueOf(settings.expeditionLayers))))),
                    (p, type) -> {
                        String result = expeditions.start(p.getUniqueId());
                        if (result == null) {
                            p.closeInventory();
                        } else {
                            feedbackStart(p, result);
                            open(p);
                        }
                    });
        }

        List<Component> infoLore = new ArrayList<>();
        infoLore.add(GuiItems.text(settings, "gui.expedition-info-lore-layers", "<gray>层数：<white><layers>",
                Placeholder.unparsed("layers", String.valueOf(settings.expeditionLayers))));
        infoLore.add(GuiItems.text(settings, "gui.expedition-info-lore-soul", "<gray>每层灵魂：<yellow><soul>",
                Placeholder.unparsed("soul", String.valueOf(settings.expeditionSoulPerLayer))));
        infoLore.add(GuiItems.text(settings, "gui.expedition-info-lore-bonus", "<gray>通关追加：<yellow><bonus>",
                Placeholder.unparsed("bonus", String.valueOf(settings.expeditionClearBonus))));
        holder.set(13, GuiItems.button(Material.BOOK,
                GuiItems.text(settings, "gui.expedition-info-name", "<aqua>远征说明"), infoLore));

        if (running) {
            holder.set(15, GuiItems.button(Material.RED_WOOL,
                            GuiItems.text(settings, "gui.expedition-leave-name", "<red>离开远征"),
                            List.of(GuiItems.text(settings, "gui.expedition-leave-lore",
                                    "<gray>按当前进度结算灵魂"))),
                    (p, type) -> {
                        expeditions.quit(p.getUniqueId());
                        open(p);
                    });
        }

        holder.set(22, GuiItems.button(Material.ARROW,
                        GuiItems.text(settings, "gui.back", "<yellow>返回冒险菜单"), List.of()),
                (p, type) -> parent.openMain(p));
        holder.open(player);
    }

    private void feedbackStart(Player player, String result) {
        switch (result) {
            case "disabled" -> Msgs.sendOr(plugin, settings, player, "gui.result.disabled",
                    "<gray>[冒险] </gray><red>该玩法当前未开启。");
            case "in_expedition" -> Msgs.sendOr(plugin, settings, player, "gui.expedition-result.in-expedition",
                    "<gray>[远征] </gray><red>你已在远征中。");
            case "not_leader" -> Msgs.sendOr(plugin, settings, player, "gui.result.not-leader",
                    "<gray>[冒险] </gray><red>只有队长可以操作。");
            case "world_fail" -> Msgs.sendOr(plugin, settings, player, "gui.result.world-fail",
                    "<gray>[冒险] </gray><red>冒险世界暂不可用，请稍后再试。");
            case "busy" -> Msgs.sendOr(plugin, settings, player, "gui.expedition-result.busy",
                    "<gray>[远征] </gray><red>远征航道已满，请稍后再试。");
            default -> Msgs.sendOr(plugin, settings, player, "gui.expedition-result.failed",
                    "<gray>[远征] </gray><red>开始失败：<reason>",
                    Placeholder.unparsed("reason", result));
        }
    }
}
