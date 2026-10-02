package com.chilicraft.core.gui;

import com.chilicraft.api.ModuleMenuEntry;
import com.chilicraft.core.api.ChiliApiImpl;
import com.chilicraft.core.text.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** 统一帮助页面。 */
public final class HelpGui {
    private final ChiliApiImpl api;
    private final Messages messages;
    private final MenuGui menuGui;

    public HelpGui(ChiliApiImpl api, Messages messages, MenuGui menuGui) {
        this.api = api;
        this.messages = messages;
        this.menuGui = menuGui;
    }

    public void open(Player player, String moduleId) {
        GuiHolder holder = new GuiHolder(54, messages.get("help-title", "<dark_gray>帮助中心"));
        for (int slot = 0; slot < 54; slot++) holder.set(slot, GuiItems.background());
        holder.set(4, GuiItems.item(Material.BOOK, messages.get("help-name", "<gold>ChiliCraft 帮助"), List.of(
                messages.get("help-description", "<gray>/menu、/cc menu 打开主菜单；/cc help 查看指令。"))));
        int slot = 10;
        for (ModuleMenuEntry entry : api.menuEntries()) {
            if (slot > 43 || !entry.available().getAsBoolean() || entry.usePermissions().stream().noneMatch(player::hasPermission)) continue;
            holder.set(slot++, GuiItems.item(entry.icon(), net.kyori.adventure.text.Component.text(entry.displayName()),
                    List.of(net.kyori.adventure.text.Component.text(entry.description()))));
        }
        holder.set(45, GuiItems.button(Material.ARROW, messages, "menu-back-name", "<yellow>返回主菜单", "menu-back-lore", "<gray>返回分类导航"),
                (clicker, type) -> { if (menuGui != null) menuGui.open(clicker); else clicker.closeInventory(); });
        holder.set(53, GuiItems.button(Material.BARRIER, messages, "menu-close", "<red>关闭", "menu-close-lore", "<gray>关闭菜单"),
                (clicker, type) -> clicker.closeInventory());
        holder.open(player);
    }
}
