package com.chilicraft.adventure;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 世界 Boss 面板（54 格）：状态头（追踪中 Boss + 夜间连死进度）+ Boss 图鉴。
 *
 * <p>图鉴只列 enabled 定义，纯展示不可点击；图标取对应刷怪蛋，缺失回退凋零骷髅头。</p>
 */
final class BossGui {

    /** 图鉴槽位（5 个 Boss 一排） */
    private static final int[] ENTRY_SLOTS = {19, 20, 21, 22, 23};

    private final AdventureSettings settings;
    private final BossService bosses;
    private final AdventureGui parent;

    BossGui(AdventureSettings settings, BossService bosses, AdventureGui parent) {
        this.settings = settings;
        this.bosses = bosses;
        this.parent = parent;
    }

    void open(Player player) {
        UUID id = player.getUniqueId();
        GuiHolder holder = new GuiHolder(54,
                GuiItems.text(settings, "gui.title-boss", "<dark_gray>冒险 · 世界 Boss"));

        List<Component> statusLore = new ArrayList<>();
        statusLore.add(GuiItems.text(settings, "gui.boss-status-active", "<gray>追踪中：<aqua><boss>",
                Placeholder.unparsed("boss", bosses.status(id))));
        // 夜间连死进度：仅在存在启用中的连死触发型 Boss 时展示（否则分母无意义）
        BossDefinition streakBoss = null;
        for (BossDefinition def : bosses.definitions().values()) {
            if (def.enabled && def.trigger == BossDefinition.TriggerType.NIGHT_DEATH_STREAK) {
                streakBoss = def;
                break;
            }
        }
        if (streakBoss != null) {
            statusLore.add(GuiItems.text(settings, "gui.boss-status-streak",
                    "<gray>夜间连死：<yellow><count>/<need>",
                    Placeholder.unparsed("count", String.valueOf(bosses.nightDeathStreak(id))),
                    Placeholder.unparsed("need", String.valueOf(streakBoss.param))));
        }
        holder.set(4, GuiItems.head(id,
                GuiItems.text(settings, "gui.boss-status-name", "<gold>世界 Boss"), statusLore));

        int slot = 0;
        for (BossDefinition def : bosses.definitions().values()) {
            if (!def.enabled || slot >= ENTRY_SLOTS.length) {
                continue;
            }
            List<Component> lore = new ArrayList<>();
            lore.add(GuiItems.text(settings, "gui.boss-entry-lore-trigger", "<gray>触发：<white><trigger>",
                    Placeholder.unparsed("trigger", triggerDesc(def))));
            lore.add(GuiItems.text(settings, "gui.boss-entry-lore-health", "<gray>生命：<red><health>",
                    Placeholder.unparsed("health", String.valueOf(def.health))));
            lore.add(GuiItems.text(settings, "gui.boss-entry-lore-first", "<gray>首杀灵魂：<yellow><soul>",
                    Placeholder.unparsed("soul", String.valueOf(def.firstKillSoul))));
            // displayName 是 bosses.yml 数据串，按纯文本展示
            holder.set(ENTRY_SLOTS[slot++], GuiItems.button(bossIcon(def), Component.text(def.displayName), lore));
        }

        holder.set(49, GuiItems.button(Material.ARROW,
                        GuiItems.text(settings, "gui.back", "<yellow>返回冒险菜单"), List.of()),
                (p, type) -> parent.openMain(p));
        holder.open(player);
    }

    /** 触发条件中文描述（显示辅助硬编码，含触发参数） */
    private static String triggerDesc(BossDefinition def) {
        return switch (def.trigger) {
            case NIGHT_DEATH_STREAK -> "连续夜间死亡 " + def.param + " 次";
            case RAIN_OCEAN -> "雨天身处深海（" + def.param + "% 概率）";
            case THUNDER -> "雷暴天气（" + def.param + "% 概率）";
            case RANDOM -> "任意时刻低概率（" + def.param + "%）";
            case DEPTH -> "深入地下 y < " + def.param;
        };
    }

    private static Material bossIcon(BossDefinition def) {
        try {
            return Material.valueOf(def.entityType.toUpperCase(Locale.ROOT) + "_SPAWN_EGG");
        } catch (IllegalArgumentException e) {
            return Material.WITHER_SKELETON_SKULL;
        }
    }
}
