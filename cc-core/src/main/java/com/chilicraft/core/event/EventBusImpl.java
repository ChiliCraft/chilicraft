package com.chilicraft.core.event;

import com.chilicraft.api.EventData;
import com.chilicraft.api.EventHandler;
import org.bukkit.Bukkit;
import org.slf4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 事件总线实现：字符串事件名 + 主线程同步分发。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>publish 强制主线程（回调内允许直接访问游戏状态，这是性能红线）。</li>
 *   <li>单个订阅者抛错只记日志不中断，避免一个坏订阅方炸断整条分发链。</li>
 *   <li>subscribe 幂等：同一 handler 重复订阅同一事件名只生效一次。</li>
 *   <li>无订阅者的事件直接短路返回（附属未装时核心照常 publish）。</li>
 * </ul>
 */
public final class EventBusImpl {

    private final Map<String, List<EventHandler>> handlers = new ConcurrentHashMap<>();
    private final Logger logger;

    public EventBusImpl(Logger logger) {
        this.logger = logger;
    }

    public void subscribe(String eventName, EventHandler handler) {
        if (eventName == null || eventName.isEmpty() || handler == null) {
            return; // 非法订阅静默忽略，不让附属因手误崩掉 onEnable
        }
        List<EventHandler> list = handlers.computeIfAbsent(eventName, k -> new CopyOnWriteArrayList<>());
        if (!list.contains(handler)) {
            list.add(handler);
        }
    }

    public void unsubscribe(String eventName, EventHandler handler) {
        if (eventName == null || handler == null) {
            return;
        }
        List<EventHandler> list = handlers.get(eventName);
        if (list != null) {
            list.remove(handler);
        }
    }

    public void publish(String eventName, EventData data) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("事件总线 publish 只允许主线程调用: " + eventName);
        }
        List<EventHandler> list = handlers.get(eventName);
        if (list == null || list.isEmpty()) {
            return; // 无订阅者是常态（附属未安装），静默跳过
        }
        for (EventHandler handler : list) {
            try {
                handler.handle(eventName, data);
            } catch (Throwable t) {
                logger.error("事件 {} 的订阅者 {} 处理异常", eventName, handler.getClass().getName(), t);
            }
        }
    }
}
