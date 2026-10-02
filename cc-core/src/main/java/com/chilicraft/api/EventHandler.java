package com.chilicraft.api;

/**
 * 事件总线订阅回调。
 *
 * <p>核心保证回调在主线程同步分发，可安全访问游戏状态；
 * 回调内禁止再次 publish 同名事件造成无限递归。</p>
 */
@FunctionalInterface
public interface EventHandler {
    void handle(String eventName, EventData data);
}
