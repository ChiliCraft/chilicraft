package com.chilicraft.demon;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** 饿魔普通入口只读状态页，避免普通玩家触达管理动作。 */
final class DemonStatusGui {
    private final DemonSettings settings;
    private final DemonManager manager;

    DemonStatusGui(DemonSettings settings, DemonManager manager) {
        this.settings = settings;
        this.manager = manager;
    }

    void open(Player player) {
        GuiHolder holder = new GuiHolder(27, DemonGuiItems.text(settings, "gui-title-status", "<dark_red>饿魔状态"));
        holder.set(4, DemonGuiItems.button(Material.SPAWNER,
                DemonGuiItems.text(settings, "gui-status-name", "<red>饿魔状态"), List.of(
                        line("gui-status-total", "<gray>存活：<count> / <limit></gray>", "<count>", manager.total(), "<limit>", settings.globalLimit),
                        line("gui-status-imp", "<gray>小饿魔：<count></gray>", "<count>", manager.count(DemonType.IMP)),
                        line("gui-status-hunter", "<gray>猎手：<count></gray>", "<count>", manager.count(DemonType.HUNTER)),
                        line("gui-status-mother", "<gray>母体：<count></gray>", "<count>", manager.count(DemonType.MOTHER)),
                        line("gui-status-spawn", "<gray>自动刷怪：<state></gray>", "<state>", settings.spawnEnabled ? "开启" : "关闭"),
                        line("gui-status-time", "<gray>当前世界：<time></gray>", "<time>", settings.isNight(player.getWorld().getTime()) ? "夜晚" : "白天"))));
        holder.set(22, DemonGuiItems.button(Material.BARRIER,
                DemonGuiItems.text(settings, "gui-close", "<red>关闭"), List.of()), (p, type) -> p.closeInventory());
        holder.open(player);
    }

    private Component line(String key, String fallback, String placeholder, Object value, Object... more) {
        Component result = DemonGuiItems.text(settings, key, fallback);
        result = result.replaceText(builder -> builder.matchLiteral(placeholder).replacement(DemonGuiItems.raw(String.valueOf(value))));
        for (int i = 0; i + 1 < more.length; i += 2) {
            String tag = String.valueOf(more[i]);
            String replacement = String.valueOf(more[i + 1]);
            result = result.replaceText(builder -> builder.matchLiteral(tag).replacement(DemonGuiItems.raw(replacement)));
        }
        return result;
    }
}
