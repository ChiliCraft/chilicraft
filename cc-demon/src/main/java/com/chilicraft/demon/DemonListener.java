package com.chilicraft.demon;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Random;
import java.util.UUID;

/**
 * 饿魔事件监听：击杀掉落 / 出世界兜底出表 / 饱腹之魂右键。
 *
 * <p>全部主线程同步事件；处理前先判类型尽早返回。</p>
 */
final class DemonListener implements Listener {

    private final DemonSettings settings;
    private final DemonItems items;
    private final DemonManager manager;
    private final ChiliCraftAPI api;
    private final Random random = new Random();

    DemonListener(DemonSettings settings, DemonItems items, DemonManager manager, ChiliCraftAPI api) {
        this.settings = settings;
        this.items = items;
        this.manager = manager;
        this.api = api;
    }

    // ---------------- 击杀掉落 ----------------

    /** LOW 优先级：先于其他插件处理掉落，保证饿魔战利品表不被污染 */
    @EventHandler(priority = EventPriority.LOW)
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        DemonType type = manager.typeOf(entity);
        if (type == null) {
            return; // 非追踪饿魔，尽早返回
        }
        // 饿魔走专属掉落表：清空原版掉落与经验
        event.getDrops().clear();
        event.setDroppedExp(0);

        // 饿魔之牙：稳定掉落 1-2
        int fangCount = settings.fangMin + random.nextInt(settings.fangMax - settings.fangMin + 1);
        if (fangCount > 0) {
            event.getDrops().add(items.fang(fangCount));
        }
        // 饱腹之魂：概率掉落
        if (random.nextDouble() < settings.soulChance) {
            event.getDrops().add(items.soul(1));
        }

        // 发布 demon.mob_killed（载荷 = 击杀者 / 怪物类型 / 数量）
        Player killer = entity.getKiller();
        UUID killerId = killer != null ? killer.getUniqueId() : null;
        api.publish("demon.mob_killed", new EventData(killerId, type.id(), 1));

        // 死亡路径出表（EntityRemoveFromWorldEvent MONITOR 也会触发，幂等）
        manager.untrack(entity);
    }

    // ---------------- 出世界兜底（Paper 专属事件） ----------------

    /** MONITOR：区块卸载 / 传送 / clear 都触发，防追踪表泄漏 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        Entity entity = event.getEntity();
        if (manager.isDemon(entity.getUniqueId())) {
            manager.untrack(entity);
        }
    }

    // ---------------- 饱腹之魂右键 ----------------

    /**
     * 右键恢复 100% 饱食度。主手过滤防双手双触发；
     * 创造模式不消耗。soul 材质非食物（GHAST_TEAR），不会与长按进食冲突。
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // 副手不处理，防双触发
        }
        ItemStack item = event.getItem();
        if (!items.isSoul(item)) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        player.setFoodLevel(20);
        player.setSaturation(20f);
        if (player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
        player.sendMessage(Texts.parse(settings.message("soul-restored")));
    }
}
