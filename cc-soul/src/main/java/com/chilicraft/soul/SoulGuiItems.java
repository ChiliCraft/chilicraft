package com.chilicraft.soul;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

/**
 * GUI 物品构建工具：MiniMessage 解析（失败回退纯文本，复用 {@link Texts}）、
 * 按钮与玩家头。
 *
 * <p>所有面板共用；文案统一走 settings.messageOr（config 缺失回退代码默认，
 * 按钮名不空白）；数据串（死因 / 世界名等数据驱动文本）用 Component.text
 * 纯文本，防 MiniMessage 标签注入。</p>
 */
final class SoulGuiItems {

    private SoulGuiItems() {
    }

    /** 取模板并解析（messageOr + Texts.parse 合体） */
    static Component text(SoulSettings settings, String key, String def) {
        return Texts.parse(settings.messageOr(key, def));
    }

    /** 数据驱动文本（死因 / 世界名等）：纯文本，不解析标签 */
    static Component raw(String value) {
        return Component.text(value);
    }

    /** 普通按钮物品 */
    static ItemStack button(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** 玩家头：仅在线玩家设置皮肤（离线档案拉取会卡主线程） */
    static ItemStack head(UUID uuid, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            meta.setOwningPlayer(online);
        }
        meta.displayName(name);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        item.setItemMeta(meta);
        return item;
    }
}
