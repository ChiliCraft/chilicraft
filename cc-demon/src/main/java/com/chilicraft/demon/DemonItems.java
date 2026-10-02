package com.chilicraft.demon;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import net.kyori.adventure.text.Component;

/**
 * 饿魔掉落物品工厂：饿魔之牙（兑换材料）/ 饱腹之魂（右键恢复饱食度）。
 *
 * <p>物品用 PDC 打标记（值 "fang" / "soul"），右键识别不靠材质，
 * 管理员换材质配置后旧物品依然有效。</p>
 */
final class DemonItems {

    private final DemonSettings settings;
    private final NamespacedKey itemKey;

    DemonItems(Plugin plugin, DemonSettings settings) {
        this.settings = settings;
        this.itemKey = new NamespacedKey(plugin, "demon_item");
    }

    /** 生成饿魔之牙（兑换材料） */
    ItemStack fang(int amount) {
        return build(settings.fangMaterial, amount, "fang", "fang-name", "fang-lore");
    }

    /** 生成饱腹之魂（右键恢复 100% 饱食度） */
    ItemStack soul(int amount) {
        return build(settings.soulMaterial, amount, "soul", "soul-name", "soul-lore");
    }

    /** 是否为饱腹之魂（按 PDC 标记判定，材质无关） */
    boolean isSoul(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        return "soul".equals(pdc.get(itemKey, PersistentDataType.STRING));
    }

    private ItemStack build(Material material, int amount, String mark,
                            String nameKey, String loreKey) {
        ItemStack item = new ItemStack(material, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Texts.parse(settings.message(nameKey)));
            String loreTemplate = settings.message(loreKey);
            if (!loreTemplate.isEmpty()) {
                meta.lore(java.util.List.of(Texts.parse(loreTemplate)));
            }
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, mark);
            item.setItemMeta(meta);
        }
        return item;
    }
}
