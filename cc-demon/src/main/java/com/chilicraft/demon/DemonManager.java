package com.chilicraft.demon;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 饿魔实体管理：原版生物改属性 + 改名（不重写 AI）；可选 MythicMobs 接管（规格 v1.1）。
 *
 * <p>生成路径二选一：配置了 MM 内部 ID 且 MM 在场时走 MythicAdapter（技能与行为由 MM
 * 配置负责）；否则原版底座 spawn 后本地改造。两条路径统一 PDC 标记与追踪表，
 * 击杀掉落、事件发布、白天清除与安全区联动对路径无感知。</p>
 *
 * <p>生命周期：spawn 入表 → 死亡 / 出世界（MONITOR 兜底）出表；
 * persistent(false) 保证区块卸载即消失不落盘。
 * 全部操作仅在主线程执行（事件与周期任务都在主线程）。</p>
 */
final class DemonManager {

    /** 追踪条目：实体强引用 + 类型（任务周期内反复访问，避免重复 PDC 读取） */
    record Tracked(Mob entity, DemonType type) {
    }

    private final JavaPlugin plugin;
    private final DemonSettings settings;
    private final NamespacedKey typeKey;
    private final NamespacedKey parentKey;
    /** 追加移速修饰符的固定键：幂等增删的锚点 */
    private final NamespacedKey speedModifierKey;

    /** 实体 UUID → 追踪条目（主线程 HashMap） */
    private final Map<UUID, Tracked> tracked = new HashMap<>();

    DemonManager(JavaPlugin plugin, DemonSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.typeKey = new NamespacedKey(plugin, "demon_type");
        this.parentKey = new NamespacedKey(plugin, "demon_parent");
        this.speedModifierKey = new NamespacedKey(plugin, "demon_hunger_boost");
    }

    // ---------------- 生成 ----------------

    /**
     * 在指定位置生成一只饿魔并纳入追踪。
     *
     * @param parentId 母体 UUID；非空时写入 PDC，召唤物归母体计数（母体死后转普通小饿魔）
     * @return 已配置完毕的实体；调用方自行处理 globalLimit 检查
     */
    Mob spawn(DemonType type, Location location, UUID parentId) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("生成位置无世界");
        }
        // MythicMobs 接管（规格 v1.1）：配置了 MM ID 且插件在场时技能与行为交 MM；
        // 不可用 / 未配置 / 生成失败一律回退原版生物改造路径
        Mob entity = spawnWithMythic(type, location);
        if (entity == null) {
            Class<? extends Mob> clazz = switch (type.entity()) {
                case ZOMBIE -> Zombie.class;
                case SKELETON -> Skeleton.class;
                case IRON_GOLEM -> IronGolem.class;
                default -> throw new IllegalArgumentException("不支持的饿魔底座类型: " + type.entity());
            };
            entity = location.getWorld().spawn(location, clazz,
                    CreatureSpawnEvent.SpawnReason.CUSTOM, false, e -> configure(e, type));
        }
        if (parentId != null) {
            entity.getPersistentDataContainer().set(parentKey, PersistentDataType.STRING, parentId.toString());
        }
        tracked.put(entity.getUniqueId(), new Tracked(entity, type));
        return entity;
    }

    /**
     * MM 生成路径：技能 / 行为 / 展示名全部由 MM 侧 mob 配置负责（建议原版模型），
     * 这里只做口径统一（不落盘 + PDC 类型标记）。返回 null 表示不走此路径。
     */
    private Mob spawnWithMythic(DemonType type, Location location) {
        String mythicId = settings.mythicIds.get(type);
        if (mythicId == null || !MythicAdapter.available(plugin, settings)) {
            return null;
        }
        Mob entity = MythicAdapter.spawnMob(mythicId, location);
        if (entity == null) {
            return null;
        }
        entity.setPersistent(false);
        entity.setRemoveWhenFarAway(false);
        entity.setCanPickupItems(false);
        entity.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
        return entity;
    }

    /** 底座改造：PDC 标记 + 属性覆写 + 命名 + 持久化关闭 */
    private void configure(Mob entity, DemonType type) {
        // 区块卸载即消失，绝不落盘残留
        entity.setPersistent(false);
        entity.setRemoveWhenFarAway(false);
        entity.setCanPickupItems(false);

        DemonSettings.Stats stats = settings.stats.getOrDefault(type, new DemonSettings.Stats(20.0, 5.0));
        AttributeInstance maxHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(stats.health());
            entity.setHealth(Math.min(stats.health(), maxHealth.getValue()));
        }
        AttributeInstance attack = entity.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(stats.damage());
        }

        // Bukkit 生成的骷髅可能不带弓：显式补装，保证猎手有远程手段
        if (entity instanceof Skeleton skeleton) {
            skeleton.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
        }

        // 铁傀儡对玩家默认敌对（被玩家召唤系）；玩家召唤的不算，此处显式关中立避免攻击创造者
        if (entity instanceof IronGolem golem) {
            golem.setPlayerCreated(false);
        }

        String nameTemplate = settings.message(type.id() + "-name");
        if (!nameTemplate.isEmpty()) {
            entity.customName(Texts.parse(nameTemplate));
            entity.setCustomNameVisible(true);
        }

        entity.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
    }

    // ---------------- 移速修饰（幂等） ----------------

    /** 给饿魔追加饿意追踪移速加成（addTransientModifier：不写入 NBT，随实体消亡） */
    void applySpeedBoost(Mob entity) {
        AttributeInstance speed = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        if (speed.getModifier(speedModifierKey) != null) {
            return; // 幂等：已有修饰符不重复叠加
        }
        speed.addTransientModifier(new AttributeModifier(speedModifierKey,
                settings.hungerSpeedBoost, AttributeModifier.Operation.MULTIPLY_SCALAR_1));
    }

    /** 移除饿意追踪移速加成（按固定键，无修饰符时静默） */
    void removeSpeedBoost(Mob entity) {
        AttributeInstance speed = entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED);
        if (speed != null) {
            speed.removeModifier(speedModifierKey);
        }
    }

    // ---------------- 识别与计数 ----------------

    /** 实体是否为已追踪饿魔 */
    boolean isDemon(UUID entityId) {
        return tracked.containsKey(entityId);
    }

    /** 从 PDC 解析饿魔类型（死亡事件用）；非饿魔返回 null */
    DemonType typeOf(Entity entity) {
        Tracked entry = tracked.get(entity.getUniqueId());
        return entry != null ? entry.type() : null;
    }

    /** 追踪表快照（遍历安全：调用方遍历时可能触发出表） */
    List<Tracked> snapshot() {
        return List.copyOf(tracked.values());
    }

    /** 当前追踪总数 */
    int total() {
        return tracked.size();
    }

    /** 指定类型存活数 */
    int count(DemonType type) {
        int n = 0;
        for (Tracked entry : tracked.values()) {
            if (entry.type() == type) {
                n++;
            }
        }
        return n;
    }

    /** 统计母体存活召唤物（母体附近 16×8×16 内 PDC 归属匹配；自愈式，防止死计数卡上限） */
    int countSummons(Mob mother) {
        String parentId = mother.getUniqueId().toString();
        int n = 0;
        for (Entity nearby : mother.getNearbyEntities(16, 8, 16)) {
            if (nearby instanceof Mob mob && nearby.isValid()) {
                PersistentDataContainer pdc = mob.getPersistentDataContainer();
                if (parentId.equals(pdc.get(parentKey, PersistentDataType.STRING))) {
                    n++;
                }
            }
        }
        return n;
    }

    // ---------------- 出表与清理 ----------------

    /**
     * 出表（幂等）。死亡事件与 Paper 出世界事件（MONITOR）双路触发。
     * @param entity 出表实体
     */
    void untrack(Entity entity) {
        Tracked entry = tracked.remove(entity.getUniqueId());
        if (entry != null && entry.entity().isValid()) {
            removeSpeedBoost(entry.entity());
        }
    }

    /** Paper 出世界事件兜底：区块卸载 / 传送 / 清除都会触发，防 Map 泄漏 */
    void onRemoveFromWorld(EntityRemoveFromWorldEvent event) {
        untrack(event.getEntity());
    }

    /** 移除全部存活的追踪饿魔；返回实际移除数（onDisable 与 /demon clear 用） */
    int clearAll() {
        List<Tracked> entries = snapshot();
        int removed = 0;
        for (Tracked entry : entries) {
            if (entry.entity().isValid()) {
                entry.entity().remove();
                removed++;
            }
            tracked.remove(entry.entity().getUniqueId());
        }
        return removed;
    }

    /** 清除玩家周围 radius 格内同世界的饿魔；返回清除数（方街安全区联动：入街清怪） */
    int clearNear(Player player, double radius) {
        double rSq = radius * radius;
        int removed = 0;
        for (Tracked entry : snapshot()) {
            Mob entity = entry.entity();
            if (entity.isValid() && entity.getWorld().equals(player.getWorld())
                    && entity.getLocation().distanceSquared(player.getLocation()) <= rSq) {
                entity.remove();
                untrack(entity);
                removed++;
            }
        }
        return removed;
    }
}
