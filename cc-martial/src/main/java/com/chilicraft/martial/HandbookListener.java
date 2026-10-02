package com.chilicraft.martial;

import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 武学手册交互监听：右键施展当前选中技能，潜行+右键在已装备技能间循环切换。
 *
 * <p>手册由 {@link SkillService#createHandbook()} 以 PDC 标记（非手拼 NBT）；
 * 命中即取消事件，避免右键方块的原生行为（开箱/放置等）。
 * 擂台规则关闭技能时（arena.allow-skills=false 且对局中）施展被拦截。</p>
 */
final class HandbookListener implements Listener {

    private final NamespacedKey handbookKey;
    private final SkillService skills;
    private final ArenaService arenas;

    HandbookListener(JavaPlugin plugin, SkillService skills, ArenaService arenas) {
        this.handbookKey = new NamespacedKey(plugin, "martial_handbook");
        this.skills = skills;
        this.arenas = arenas;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        // 仅主手判定一次，防双手双触发
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        Byte flag = meta.getPersistentDataContainer().get(handbookKey, PersistentDataType.BYTE);
        if (flag == null || flag != 1) {
            return;
        }
        event.setCancelled(true);
        if (event.getPlayer().isSneaking()) {
            skills.cycleSelected(event.getPlayer());
            return;
        }
        // 擂台规则关闭技能：对局中拦截施展（切换选中不受限）
        if (arenas.skillBlocked(event.getPlayer())) {
            arenas.notifySkillBlocked(event.getPlayer());
            return;
        }
        skills.cast(event.getPlayer());
    }
}
