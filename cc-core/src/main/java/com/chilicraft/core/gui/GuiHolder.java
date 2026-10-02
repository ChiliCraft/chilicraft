package com.chilicraft.core.gui;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 原版箱子 GUI 框架：以 InventoryHolder 绑定菜单身份，
 * 监听器用 instanceof 判定（性能红线：禁止 getTitle() 字符串比对）。
 *
 * <p>菜单为只读按钮型：监听器统一取消点击与拖拽（防拖拽越界，
 * 规格安全要点），点击经槽位动作表分发。</p>
 */
public final class GuiHolder implements InventoryHolder {

    /** 槽位点击动作：参数为点击者与点击类型 */
    public interface ClickAction extends BiConsumer<Player, ClickType> {
    }

    private final Inventory inventory;
    /** 槽位动作表：slot -> 动作；主线程读写（菜单构建后运行期不再改） */
    private final Map<Integer, ClickAction> actions = new HashMap<>();

    public GuiHolder(int size, Component title) {
        // 行数向下取整到合法尺寸（9 的倍数，1-6 行）
        int rows = Math.max(1, Math.min(6, (size + 8) / 9));
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /** 放置按钮物品并绑定点击动作（action 可为 null，纯展示物品） */
    public void set(int slot, ItemStack item, @Nullable ClickAction action) {
        inventory.setItem(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    /** 只放展示物品 */
    public void set(int slot, ItemStack item) {
        set(slot, item, null);
    }

    /** 由监听器分发点击；未绑定动作的槽位静默忽略 */
    void click(int slot, Player player, ClickType type) {
        ClickAction action = actions.get(slot);
        if (action != null) {
            action.accept(player, type);
        }
    }

    /** 打开菜单（主线程） */
    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
