package com.chilicraft.season;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;

final class SeasonGuide {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private final SeasonSettings settings;

    SeasonGuide(SeasonSettings settings) {
        this.settings = settings;
    }

    ItemStack create() {
        ItemStack item = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) item.getItemMeta();
        meta.title(parse(settings.messages.getOrDefault("guide-title", "季节指南")));
        meta.author(Component.text("ChiliCraft"));
        List<Component> pages = new ArrayList<>();
        for (String line : settings.guideLines) {
            pages.add(parse(line));
        }
        if (!pages.isEmpty()) {
            meta.pages(pages);
        }
        item.setItemMeta(meta);
        return item;
    }

    private Component parse(String template) {
        try {
            return MINI.deserialize(template);
        } catch (RuntimeException ignored) {
            return Component.text(template);
        }
    }
}
