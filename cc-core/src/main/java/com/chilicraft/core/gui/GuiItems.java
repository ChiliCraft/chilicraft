package com.chilicraft.core.gui;

import com.chilicraft.core.text.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/** 统一菜单的基础物品构建。 */
public final class GuiItems {
    private GuiItems() {
    }

    public static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    public static ItemStack button(Material material, Messages messages, String nameKey, String nameDef,
                                   String loreKey, String loreDef) {
        return item(material, messages.get(nameKey, nameDef), List.of(messages.get(loreKey, loreDef)));
    }

    public static ItemStack background() {
        return item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
    }
}
