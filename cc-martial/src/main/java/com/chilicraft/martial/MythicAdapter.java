package com.chilicraft.martial;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MythicMobs 适配器：流派武品发放（MM 物品）。
 *
 * <p>类引用隔离原则：{@link #mythicItem} 内的 MM 类引用只在
 * {@link #available} 为真（插件在场 + 开关开启）后才可达；
 * 所有调用 catch Throwable，联动失败静默降级（调用方回退 vanilla-weapon）。</p>
 */
final class MythicAdapter {

    private MythicAdapter() {
    }

    /** MM 在场且 integration.mythicmobs 开启 */
    static boolean available(JavaPlugin plugin, MartialSettings settings) {
        if (!settings.integrationMythicMobs) {
            return false;
        }
        return plugin.getServer().getPluginManager().isPluginEnabled("MythicMobs");
    }

    /**
     * 获取 MM 物品（ItemExecutor.getItemStack 直接返回 Bukkit ItemStack）。
     * 缺失 / 异常返回 null，调用方回退原版武器。
     */
    static ItemStack mythicItem(String itemId) {
        try {
            Object manager = io.lumine.mythic.bukkit.MythicBukkit.inst().getItemManager();
            // ItemExecutor implements ItemManager；强转到实现类调用最简重载
            ItemStack item = ((io.lumine.mythic.core.items.ItemExecutor) manager).getItemStack(itemId);
            return item == null ? null : item.clone();
        } catch (Throwable t) {
            return null;
        }
    }
}
