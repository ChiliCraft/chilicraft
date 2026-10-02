package com.chilicraft.adventure;

import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PDC 键常量：实体标记（防重复计数 / 识别归属）。
 *
 * <p>命名空间取插件名（cc-adventure），模块卸载后残留标记随实体消失，无迁移负担。</p>
 */
final class Keys {

    /** 实体所属地城定义 ID（地城怪） */
    static NamespacedKey DUNGEON_ID;
    /** 世界 Boss 定义 ID（Boss 本体） */
    static NamespacedKey BOSS_ID;
    /** Boss 召唤物标记（护卫） */
    static NamespacedKey MINION;
    /** 远征层内房间怪标记 */
    static NamespacedKey EXPEDITION_ID;

    private Keys() {
    }

    static void init(JavaPlugin plugin) {
        DUNGEON_ID = new NamespacedKey(plugin, "dungeon_id");
        BOSS_ID = new NamespacedKey(plugin, "boss_id");
        MINION = new NamespacedKey(plugin, "minion");
        EXPEDITION_ID = new NamespacedKey(plugin, "expedition_id");
    }
}
