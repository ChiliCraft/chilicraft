package com.chilicraft.core.gui;

import com.chilicraft.api.GameMode;
import com.chilicraft.api.MenuCategory;
import com.chilicraft.api.PlayerProfile;
import com.chilicraft.core.api.ChiliApiImpl;
import com.chilicraft.core.config.CoreConfig;
import com.chilicraft.core.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 27 格分类导航主菜单。 */
public final class MenuGui {
    private final ChiliApiImpl api;
    private final CoreConfig config;
    private final Messages messages;

    public MenuGui(ChiliApiImpl api, CoreConfig config, Messages messages) {
        this.api = api;
        this.config = config;
        this.messages = messages;
    }

    public void open(Player player) {
        PlayerProfile profile = api.getProfile(player.getUniqueId());
        if (profile == null) {
            player.sendMessage(messages.get("profile-missing", "<red>档案尚未加载完成，请稍后再试。"));
            return;
        }
        GuiHolder holder = new GuiHolder(27, messages.get("menu-title", "<dark_gray>ChiliCraft 主菜单"));
        for (int slot = 0; slot < 27; slot++) holder.set(slot, GuiItems.background());
        holder.set(4, infoHead(player, profile));
        setCategory(holder, 10, Material.IRON_SWORD, "生存冒险", MenuCategory.SURVIVAL_ADVENTURE);
        setCategory(holder, 11, Material.EXPERIENCE_BOTTLE, "成长体系", MenuCategory.PROGRESSION);
        setCategory(holder, 12, Material.GRASS_BLOCK, "世界生态", MenuCategory.WORLD_ECOLOGY);
        setCategory(holder, 14, Material.NAME_TAG, "社交生活", MenuCategory.SOCIAL_LIFE);
        setCategory(holder, 15, Material.EMERALD, "经济服务", MenuCategory.ECONOMY_SERVICES);
        setCategory(holder, 16, Material.FIREWORK_ROCKET, "巡演方街", MenuCategory.TOUR_STREET);
        if (player.hasPermission("chilicraft.admin") || api.commandRoutes().stream().anyMatch(route -> route.adminPermissions().stream().anyMatch(player::hasPermission))) {
            holder.set(18, GuiItems.button(Material.COMPARATOR, messages, "admin-name", "<red>管理中心", "admin-description", "<gray>仅提供低风险状态与帮助入口。"),
                    (clicker, type) -> new AdminMenuGui(api, messages).open(clicker));
        }
        holder.set(13, modeButton(Material.COMPASS, "menu-mode-name", "<green>模式切换", profile.mode()),
                (clicker, type) -> modeClick(clicker, type));
        holder.set(21, GuiItems.button(Material.LECTERN, messages, "menu-home-name", "<aqua>家园", "menu-home-lore", "<gray>传送到家园区锚点"),
                (clicker, type) -> clicker.performCommand("cc home"));
        holder.set(22, GuiItems.button(Material.BOOK, messages, "menu-help-name", "<aqua>帮助中心", "menu-help-lore", "<gray>查看指令与模块说明"),
                (clicker, type) -> new HelpGui(api, messages, this).open(clicker, null));
        holder.set(23, GuiItems.button(Material.GOLD_NUGGET, messages, "menu-balance-name", "<yellow>灵魂余额", "menu-balance-lore", "<gray>查看当前灵魂余额"),
                (clicker, type) -> clicker.performCommand("cc balance"));
        holder.set(26, GuiItems.button(Material.BARRIER, messages, "menu-close", "<red>关闭", "menu-close-lore", "<gray>关闭菜单"),
                (clicker, type) -> clicker.closeInventory());
        holder.open(player);
    }

    private void setCategory(GuiHolder holder, int slot, Material icon, String name, MenuCategory category) {
        holder.set(slot, GuiItems.item(icon, Component.text(name), List.of(messages.get("category-description", "<gray>浏览该分类模块。"))),
                (player, type) -> new CategoryGui(api, messages, this).open(player, category));
    }

    private void modeClick(Player player, org.bukkit.event.inventory.ClickType type) {
        if (type.isLeftClick()) {
            player.performCommand("cc mode " + (api.getMode(player.getUniqueId()) == GameMode.ADVENTURE ? "cozy" : "adventure"));
        }
    }

    private ItemStack infoHead(Player player, PlayerProfile profile) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(player);
        meta.displayName(messages.get("menu-info-name", "<gold>我的档案"));
        meta.lore(List.of(messages.get("info-mode", "<gray>当前模式：<white><mode></white>", Placeholder.unparsed("mode", modeName(profile.mode()))),
                messages.get("info-soul", "<gray><name>余额：<yellow><amount></yellow>", Placeholder.unparsed("name", config.soulName()), Placeholder.unparsed("amount", String.valueOf(profile.soul()))),
                messages.get("info-realm", "<gray>武学境界：<aqua><realm></aqua>", Placeholder.unparsed("realm", realmName(profile.martialRealm()))),
                messages.get("info-profession", "<gray>职业：<white><profession></white>", Placeholder.unparsed("profession", professionName(profile.profession()))),
                messages.get("info-home", "<gray>家园区：<white><home></white>", Placeholder.unparsed("home", profile.isHomeSet() ? "已设置" : "未设置"))));
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack modeButton(Material material, String nameKey, String nameDef, GameMode current) {
        return GuiItems.item(material, messages.get(nameKey, nameDef), List.of(messages.get("menu-mode-current", "<gray>当前：<mode>", Placeholder.unparsed("mode", modeName(current)))));
    }

    public static String modeName(GameMode mode) { return mode == GameMode.ADVENTURE ? "冒险" : "养老"; }
    public static String realmName(int realm) { return realm <= 0 ? "未入门" : "第 " + realm + " 境"; }
    public static String professionName(String profession) { return profession == null || profession.isEmpty() ? "无" : profession; }
}
