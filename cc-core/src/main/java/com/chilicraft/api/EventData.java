package com.chilicraft.api;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 事件总线数据载体：核心三字段 + 扩展键值。
 *
 * <p>约定：发布方在构造后、{@code publish} 前填好全部字段；
 * 订阅方在回调中只读。事件总线为单线程同步分发，
 * 因此本类无需做并发保护。</p>
 *
 * <p>事件名遵循「模块名.事件名」规范（如 demon.mob_killed），
 * 首期事件清单见规格文档第五节。</p>
 */
public final class EventData {
    private final UUID playerId;   // 可空：世界级事件无关联玩家
    private final String target;   // 可空：目标标识（实体类型、地城 ID、章节 ID 等）
    private final int amount;      // 默认 0，可为负
    private final Map<String, Object> extra = new HashMap<>();

    public EventData() {
        this(null, null, 0);
    }

    public EventData(UUID playerId) {
        this(playerId, null, 0);
    }

    public EventData(UUID playerId, String target, int amount) {
        this.playerId = playerId;
        this.target = target;
        this.amount = amount;
    }

    /** 事件关联玩家；世界级事件为 null */
    public UUID playerId() {
        return playerId;
    }

    /** 目标标识；可能为 null */
    public String target() {
        return target;
    }

    /** 数量值；默认 0 */
    public int amount() {
        return amount;
    }

    /** 填充扩展字段（仅发布前调用），返回 this 便于链式 */
    public EventData put(String key, Object value) {
        extra.put(key, value);
        return this;
    }

    /** 读取扩展字段，不存在返回 null */
    public Object get(String key) {
        return extra.get(key);
    }

    /** 扩展字段视图 */
    public Map<String, Object> extra() {
        return extra;
    }
}
