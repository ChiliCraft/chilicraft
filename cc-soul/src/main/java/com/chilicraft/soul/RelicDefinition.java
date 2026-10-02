package com.chilicraft.soul;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 遗物静态定义（配置驱动，SoulSettings#refresh 时重建，运行期只读）。
 *
 * <p>每条定义对应一份全服唯一实例；{@link ItemStack} 上的 PDC（relic_id 等）
 * 由 RelicService 写入，本 record 只承载静态数据。</p>
 *
 * @param id        遗物 ID（配置键形式，如 soul-lantern；PDC relic_id 同值）
 * @param material  原版物品材质
 * @param display   展示名（MiniMessage）
 * @param lore      描述 lore（MiniMessage，单行）
 * @param effect    效果类型
 * @param value     效果数值（比例 / 格数 / 点数，语义随 effect 而定）
 * @param durability 灵韵上限（触发式效果每次消耗 1 点，归零沉眠）
 */
record RelicDefinition(String id, Material material, String display, String lore,
                       RelicEffect effect, double value, int durability) {
}
