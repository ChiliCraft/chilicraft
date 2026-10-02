package com.chilicraft.demon;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * GUI 物品构建工具：MiniMessage 解析（失败回退纯文本，复用 {@link Texts}）、
 * 按钮与 lore 行组装。
 *
 * <p>所有面板共用；文案统一走 settings.messageOr（config 缺失回退代码默认，
 * 按钮名不空白）；数据驱动文本（类型名 / 材质名等）用 Component.text 纯文本，
 * 防 MiniMessage 标签注入。</p>
 */
final class DemonGuiItems {

    private DemonGuiItems() {
    }

    /** 取模板并解析（messageOr + Texts.parse 合体） */
    static Component text(DemonSettings settings, String key, String def) {
        return Texts.parse(settings.messageOr(key, def));
    }

    /** 数据驱动文本（类型名 / 材质名等）：纯文本，不解析标签 */
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

    /**
     * 组装一行 lore：解析模板后按「标签 → 值」对依次 replaceText 替换
     * （值为 Component 时原样替换，字符串按纯文本替换）。
     */
    static Component line(DemonSettings settings, String key, String def, Object... pairs) {
        Component result = text(settings, key, def);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String tag = (String) pairs[i];
            Component replacement = pairs[i + 1] instanceof Component component
                    ? component
                    : raw(String.valueOf(pairs[i + 1]));
            result = result.replaceText(b -> b.matchLiteral(tag).replacement(replacement));
        }
        return result;
    }

    /** 数值展示：整数去小数点（12.0 → 12）；百分比先乘 100 再传入 */
    static String num(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
