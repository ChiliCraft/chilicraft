package com.chilicraft.api;

/**
 * ChiliCraft 全局游戏模式（玩家级二选一，存于 cc_players.mode 列）。
 *
 * <p>注意：与 {@code org.bukkit.GameMode}（原版生存/创造等）无关，
 * 两者同时出现时需使用限定名区分。</p>
 */
public enum GameMode {
    /** 冒险模式：全套生存压力、饿魔、地城、武学等内容 */
    ADVENTURE,
    /** 养老模式：数值压力大幅放宽，刷怪与事件按宽松参数运行 */
    COZY;

    /** 配置字符串解析：未知值返回 null */
    public static GameMode fromName(String name) {
        if (name == null) return null;
        for (GameMode mode : values()) {
            if (mode.name().equalsIgnoreCase(name)) return mode;
        }
        return null;
    }
}
