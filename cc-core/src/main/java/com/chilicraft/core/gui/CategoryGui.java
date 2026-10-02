package com.chilicraft.core.gui;

import com.chilicraft.api.MenuCategory;
import com.chilicraft.api.ModuleMenuEntry;
import com.chilicraft.core.api.ChiliApiImpl;
import com.chilicraft.core.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

/** 54 格分类页面，入口点击时重新从注册表读取模块描述。 */
public final class CategoryGui {
    private final ChiliApiImpl api;
    private final Messages messages;
    private final MenuGui menuGui;

    public CategoryGui(ChiliApiImpl api, Messages messages, MenuGui menuGui) {
        this.api = api;
        this.messages = messages;
        this.menuGui = menuGui;
    }

    public void open(Player player, MenuCategory category) {
        GuiHolder holder = new GuiHolder(54, messages.get("category-title", "<dark_gray>ChiliCraft 分类"));
        for (int slot = 0; slot < 54; slot++) holder.set(slot, GuiItems.background());
        holder.set(4, GuiItems.item(Material.BOOK, messages.get("category-name", "<gold><category>",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("category", categoryName(category))),
                List.of(messages.get("category-description", "<gray>选择一个模块查看或打开。"))));
        int slot = 10;
        for (ModuleMenuEntry entry : api.menuEntries()) {
            if (entry.category() != category || !hasAny(player, entry.usePermissions())) continue;
            if (slot > 43) break;
            int current = slot++;
            holder.set(current, moduleItem(entry), (clicker, type) -> clickModule(clicker, type, entry.moduleId(), category));
        }
        holder.set(45, GuiItems.button(Material.ARROW, messages, "menu-back-name", "<yellow>返回主菜单", "menu-back-lore", "<gray>返回分类导航"),
                (clicker, type) -> menuGui.open(clicker));
        holder.set(49, GuiItems.button(Material.BOOK, messages, "menu-help-name", "<aqua>帮助中心", "menu-help-lore", "<gray>查看指令与模块说明"),
                (clicker, type) -> new HelpGui(api, messages, menuGui).open(clicker, null));
        holder.set(53, GuiItems.button(Material.BARRIER, messages, "menu-close", "<red>关闭", "menu-close-lore", "<gray>关闭菜单"),
                (clicker, type) -> clicker.closeInventory());
        holder.open(player);
    }

    private void clickModule(Player player, ClickType type, String moduleId, MenuCategory category) {
        ModuleMenuEntry entry = api.menuEntries().stream().filter(value -> value.moduleId().equals(moduleId)
                && value.category() == category).findFirst().orElse(null);
        if (entry == null) return;
        if (!hasAny(player, entry.usePermissions())) {
            player.sendMessage(messages.get("no-permission", "<red>你没有权限执行此操作。"));
            return;
        }
        if (!entry.available().getAsBoolean()) {
            player.sendMessage(messages.get("module-unavailable", "<yellow>该模块暂不可用。"));
            return;
        }
        try {
            if (type.isLeftClick()) entry.openAction().accept(player);
            else if (type.isRightClick()) entry.helpAction().accept(player);
        } catch (Throwable throwable) {
            player.sendMessage(messages.get("module-action-failed", "<red>模块操作失败，请稍后再试。"));
        }
    }

    private ItemStack moduleItem(ModuleMenuEntry entry) {
        return GuiItems.item(entry.available().getAsBoolean() ? entry.icon() : Material.GRAY_DYE,
                Component.text(entry.displayName()), List.of(Component.text(entry.description())));
    }

    private static boolean hasAny(Player player, Set<String> permissions) {
        return permissions.isEmpty() || permissions.stream().anyMatch(player::hasPermission);
    }

    private String categoryName(MenuCategory category) {
        return switch (category) {
            case SURVIVAL_ADVENTURE -> "生存冒险";
            case PROGRESSION -> "成长体系";
            case WORLD_ECOLOGY -> "世界生态";
            case SOCIAL_LIFE -> "社交生活";
            case ECONOMY_SERVICES -> "经济服务";
            case TOUR_STREET -> "巡演方街";
        };
    }
}
