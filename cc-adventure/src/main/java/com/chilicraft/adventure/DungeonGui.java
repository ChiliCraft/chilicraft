package com.chilicraft.adventure;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 地城面板（54 格）：状态头 + 地城名录（点击进入）+ 离开按钮。
 *
 * <p>名录只列 enabled 定义；点击发起进本（服务层校验队长/排队/槽位），
 * 错误码映射 gui.dungeon-result.* 消息键反馈。</p>
 */
final class DungeonGui {

    /** 地城名录槽位（两排） */
    private static final int[] ENTRY_SLOTS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private final JavaPlugin plugin;
    private final AdventureSettings settings;
    private final DungeonService dungeons;
    private final AdventureGui parent;

    DungeonGui(JavaPlugin plugin, AdventureSettings settings, DungeonService dungeons, AdventureGui parent) {
        this.plugin = plugin;
        this.settings = settings;
        this.dungeons = dungeons;
        this.parent = parent;
    }

    void open(Player player) {
        UUID id = player.getUniqueId();
        GuiHolder holder = new GuiHolder(54,
                GuiItems.text(settings, "gui.title-dungeon", "<dark_gray>冒险 · 地城"));

        DungeonInstance instance = dungeons.instanceOf(id);
        String queuedId = dungeons.queuedDungeonId(id);
        holder.set(4, GuiItems.head(id,
                GuiItems.text(settings, "gui.dungeon-status-name", "<gold>地城状态"),
                statusLore(instance, queuedId)));

        int slot = 0;
        for (DungeonDefinition def : dungeons.definitions().values()) {
            if (!def.enabled || slot >= ENTRY_SLOTS.length) {
                continue;
            }
            holder.set(ENTRY_SLOTS[slot++], entryItem(def), (p, type) -> {
                String result = dungeons.start(p.getUniqueId(), def.id);
                if (result == null) {
                    // 成功即传送进本（或入队），关闭界面避免误点
                    p.closeInventory();
                } else {
                    feedbackStart(p, result);
                    open(p);
                }
            });
        }

        if (instance != null || queuedId != null) {
            holder.set(45, GuiItems.button(Material.RED_WOOL,
                            GuiItems.text(settings, "gui.dungeon-leave-name", "<red>离开地城"),
                            List.of(GuiItems.text(settings, "gui.dungeon-leave-lore",
                                    "<gray>立即脱离当前地城/排队（地城按失败结算）"))),
                    (p, type) -> {
                        dungeons.quit(p.getUniqueId());
                        Msgs.sendOr(plugin, settings, p, "gui.dungeon-result.left",
                                "<gray>[地城] </gray><gray>已离开地城。");
                        open(p);
                    });
        }
        holder.set(49, GuiItems.button(Material.ARROW,
                        GuiItems.text(settings, "gui.back", "<yellow>返回冒险菜单"), List.of()),
                (p, type) -> parent.openMain(p));
        holder.open(player);
    }

    private List<Component> statusLore(DungeonInstance instance, String queuedId) {
        List<Component> lore = new ArrayList<>();
        if (instance != null) {
            lore.add(GuiItems.text(settings, "gui.dungeon-status-running", "<gray>进行中：<aqua><dungeon>",
                    Placeholder.unparsed("dungeon", instance.def.displayName)));
        } else if (queuedId != null) {
            DungeonDefinition queued = dungeons.definitions().get(queuedId);
            lore.add(GuiItems.text(settings, "gui.dungeon-status-queued", "<gray>排队中：<yellow><dungeon>",
                    Placeholder.unparsed("dungeon", queued == null ? queuedId : queued.displayName)));
        } else {
            lore.add(GuiItems.text(settings, "gui.dungeon-status-idle", "<gray>未在地城中"));
        }
        return lore;
    }

    private ItemStack entryItem(DungeonDefinition def) {
        List<Component> lore = new ArrayList<>();
        lore.add(GuiItems.text(settings, "gui.dungeon-entry-lore-mechanic", "<gray>机制：<white><mechanic>",
                Placeholder.unparsed("mechanic", mechanicName(def.mechanic))));
        lore.add(GuiItems.text(settings, "gui.dungeon-entry-lore-time", "<gray>时限：<white><seconds> 秒",
                Placeholder.unparsed("seconds", String.valueOf(def.timeLimitSec))));
        lore.add(GuiItems.text(settings, "gui.dungeon-entry-lore-reward", "<gray>通关灵魂：<yellow><soul>",
                Placeholder.unparsed("soul", String.valueOf(def.rewardSoul))));
        lore.add(GuiItems.text(settings, "gui.dungeon-entry-hint", "<yellow>点击进入"));
        // displayName 是 dungeons.yml 数据串，按纯文本展示（与服务层广播 Placeholder.unparsed 同口径）
        return GuiItems.button(mechanicIcon(def.mechanic), Component.text(def.displayName), lore);
    }

    private void feedbackStart(Player player, String result) {
        switch (result) {
            case "disabled" -> Msgs.sendOr(plugin, settings, player, "gui.result.disabled",
                    "<gray>[冒险] </gray><red>该玩法当前未开启。");
            case "unknown" -> Msgs.sendOr(plugin, settings, player, "gui.dungeon-result.unknown",
                    "<gray>[地城] </gray><red>未知地城。");
            case "in_dungeon" -> Msgs.sendOr(plugin, settings, player, "gui.dungeon-result.in-dungeon",
                    "<gray>[地城] </gray><red>你已在地城中。");
            case "already_queued" -> Msgs.sendOr(plugin, settings, player, "gui.dungeon-result.already-queued",
                    "<gray>[地城] </gray><red>你已在排队中。");
            case "not_leader" -> Msgs.sendOr(plugin, settings, player, "gui.result.not-leader",
                    "<gray>[冒险] </gray><red>只有队长可以操作。");
            case "world_fail" -> Msgs.sendOr(plugin, settings, player, "gui.result.world-fail",
                    "<gray>[冒险] </gray><red>冒险世界暂不可用，请稍后再试。");
            case "queued" -> Msgs.sendOr(plugin, settings, player, "gui.dungeon-result.queued",
                    "<gray>[地城] </gray><yellow>实例已满，已加入排队。");
            default -> Msgs.sendOr(plugin, settings, player, "gui.dungeon-result.failed",
                    "<gray>[地城] </gray><red>进入失败：<reason>",
                    Placeholder.unparsed("reason", result));
        }
    }

    /** 机制中文名（显示辅助硬编码，MenuGui.modeName 同范式） */
    private static String mechanicName(DungeonDefinition.Mechanic mechanic) {
        return switch (mechanic) {
            case GUARD_CAULDRON -> "守锅";
            case SACRIFICE -> "献祭";
            case BEAR_WEIGHT -> "承重";
            case UNDERWATER -> "水下";
            case DUAL_REALM -> "双界";
        };
    }

    private static Material mechanicIcon(DungeonDefinition.Mechanic mechanic) {
        return switch (mechanic) {
            case GUARD_CAULDRON -> Material.CAULDRON;
            case SACRIFICE -> Material.SOUL_LANTERN;
            case BEAR_WEIGHT -> Material.HEAVY_WEIGHTED_PRESSURE_PLATE;
            case UNDERWATER -> Material.WATER_BUCKET;
            case DUAL_REALM -> Material.CRYING_OBSIDIAN;
        };
    }
}
