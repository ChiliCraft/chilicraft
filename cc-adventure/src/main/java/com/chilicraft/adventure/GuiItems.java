package com.chilicraft.adventure;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

/**
 * GUI 物品构建工具：MiniMessage 解析（失败回退纯文本）、按钮与玩家头。
 *
 * <p>所有面板共用；文案统一走 settings.messageOr（config 缺失回退代码默认，按钮名不空白）。</p>
 */
final class GuiItems {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private GuiItems() {
    }

    /** 取模板并解析（messageOr + MiniMessage 合体）；解析失败回退纯文本组件 */
    static Component text(AdventureSettings settings, String key, String def, TagResolver... resolvers) {
        return mm(settings.messageOr(key, def), resolvers);
    }

    /** 解析 MiniMessage；失败回退纯文本组件（与 Msgs 同口径） */
    static Component mm(String template, TagResolver... resolvers) {
        try {
            return MM.deserialize(template, resolvers);
        } catch (Exception e) {
            return Component.text(template);
        }
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

    /** 在线名；离线回退 UUID 前 8 位（与 PartyService.name 同口径） */
    static String playerName(UUID uuid) {
        Player online = Bukkit.getPlayer(uuid);
        return online != null ? online.getName() : uuid.toString().substring(0, 8);
    }
}
