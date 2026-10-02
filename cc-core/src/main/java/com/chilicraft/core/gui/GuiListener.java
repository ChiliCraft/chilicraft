package com.chilicraft.core.gui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

/**
 * GUI 安全监听器：
 * <ul>
 *   <li>以 InventoryHolder instanceof 判定菜单（禁止 getTitle 比对）。</li>
 *   <li>菜单打开期间统一取消点击与拖拽，防 shift 塞入与拖拽越界
 *       （规格安全要点：拦截 InventoryDragEvent）。</li>
 *   <li>仅对菜单区槽位分发动作；点击自身背包也取消（防 shift-click 塞进菜单）。</li>
 * </ul>
 */
public final class GuiListener implements Listener {

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder holder)) {
            return; // 非本框架菜单，不干预
        }
        // 菜单为只读按钮型：一律取消，杜绝一切移动/复制路径
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getInventory().getSize()) {
            return; // 点的是玩家自身背包区：已取消，不分发动作
        }
        holder.click(rawSlot, player, event.getClick());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof GuiHolder)) {
            return;
        }
        event.setCancelled(true);
    }
}
