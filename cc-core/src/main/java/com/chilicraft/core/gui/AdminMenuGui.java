package com.chilicraft.core.gui;

import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.core.api.ChiliApiImpl;
import com.chilicraft.core.text.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** 低风险管理中心，仅展示状态并提供模块帮助入口。 */
public final class AdminMenuGui {
    private final ChiliApiImpl api;
    private final Messages messages;

    public AdminMenuGui(ChiliApiImpl api, Messages messages) {
        this.api = api;
        this.messages = messages;
    }

    public boolean canOpen(Player player) {
        return player.hasPermission("chilicraft.admin") || api.commandRoutes().stream()
                .anyMatch(route -> route.adminPermissions().stream().anyMatch(player::hasPermission));
    }

    public void open(Player player) {
        GuiHolder holder = new GuiHolder(27, messages.get("admin-title", "<dark_gray>ChiliCraft 管理中心"));
        for (int slot = 0; slot < 27; slot++) holder.set(slot, GuiItems.background());
        holder.set(4, GuiItems.item(Material.COMPARATOR, messages.get("admin-name", "<red>管理中心"),
                List.of(messages.get("admin-description", "<gray>仅提供低风险状态与帮助入口。"))));
        holder.set(13, GuiItems.button(Material.BOOK, messages, "admin-help-name", "<yellow>模块帮助", "admin-help-lore", "<gray>查看已注册模块说明"),
                (clicker, type) -> new HelpGui(api, messages, null).open(clicker, null));
        holder.set(18, GuiItems.button(Material.ARROW, messages, "menu-back-name", "<yellow>返回主菜单", "menu-back-lore", "<gray>返回分类导航"),
                (clicker, type) -> clicker.closeInventory());
        holder.set(26, GuiItems.button(Material.BARRIER, messages, "menu-close", "<red>关闭", "menu-close-lore", "<gray>关闭菜单"),
                (clicker, type) -> clicker.closeInventory());
        holder.open(player);
    }
}
