package com.chilicraft.season;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

final class SeasonGui {
    private final SeasonService service;
    private final SeasonGuide guide;
    private final SeasonClockService clock;
    private final SeasonSettings settings;
    private final java.util.Set<java.util.UUID> hidden;

    SeasonGui(SeasonService service, SeasonSettings settings, java.util.Set<java.util.UUID> hidden) {
        this.service = service;
        this.settings = settings;
        this.hidden = hidden;
        this.guide = new SeasonGuide(settings);
        this.clock = new SeasonClockService();
    }

    void open(Player player) {
        SeasonService.CalendarState state = service.state();
        GuiHolder holder = new GuiHolder(27, Component.text(settings.messages.getOrDefault("menu-title", "季节菜单")));
        holder.set(10, button(Material.SUNFLOWER, "季节状态", List.of(
                "第" + state.year() + "年 · " + state.season().name().toLowerCase(Locale.ROOT),
                "第" + state.day() + "天")));
        holder.set(12, button(Material.GLOWSTONE_DUST, "切换 HUD", List.of("点击切换季节 HUD")),
                (p, type) -> toggleHud(p));
        holder.set(14, button(Material.WRITABLE_BOOK, "季节指南", List.of("获得当前季节指南")),
                (p, type) -> p.getInventory().addItem(guide.create()));
        holder.set(16, button(Material.CLOCK, "季节时钟", List.of("获得当前季节时钟")),
                (p, type) -> p.getInventory().addItem(clock.create(service.state())));
        holder.set(22, button(Material.BARRIER, "关闭", List.of()), (p, type) -> p.closeInventory());
        holder.open(player);
    }

    private void toggleHud(Player player) {
        if (hidden.remove(player.getUniqueId())) {
            player.sendMessage("季节 HUD 已开启。");
        } else {
            hidden.add(player.getUniqueId());
            player.sendMessage("季节 HUD 已关闭。");
        }
    }

    private ItemStack button(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        meta.lore(lore.stream().map(Component::text).toList());
        item.setItemMeta(meta);
        return item;
    }
}
