package com.chilicraft.season;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.Locale;

final class SeasonClockService {

    ItemStack create(SeasonService.CalendarState state) {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("季节时钟"));
        meta.lore(List.of(Component.text("第" + state.year() + "年 · "
                + state.season().name().toLowerCase(Locale.ROOT) + " · 第" + state.day() + "天")));
        item.setItemMeta(meta);
        return item;
    }
}
