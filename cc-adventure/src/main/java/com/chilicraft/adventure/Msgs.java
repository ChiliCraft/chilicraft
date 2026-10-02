package com.chilicraft.adventure;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 消息发送工具：MiniMessage 模板 + 解析失败回退纯文本。
 *
 * <p>模板缺失（settings.message 返回空串）时静默跳过——配置裁剪不应刷屏报错。</p>
 */
final class Msgs {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private Msgs() {
    }

    /** 按模板键发送；模板为空串时静默 */
    static void send(JavaPlugin plugin, AdventureSettings settings, Player player,
                     String key, TagResolver... resolvers) {
        String template = settings.message(key);
        if (template == null || template.isBlank()) {
            return;
        }
        try {
            player.sendMessage(MM.deserialize(template, resolvers));
        } catch (Exception e) {
            // 解析失败回退纯文本（范式：宁可显示原文也不吞消息）
            plugin.getLogger().warning(() -> "消息模板 " + key + " 解析失败，回退纯文本：" + e.getMessage());
            player.sendMessage(template);
        }
    }

    /** 按模板键发送（带兜底）：结果反馈等不可静默场景用，模板缺失回退 def */
    static void sendOr(JavaPlugin plugin, AdventureSettings settings, Player player,
                       String key, String def, TagResolver... resolvers) {
        String template = settings.messageOr(key, def);
        if (template.isBlank()) {
            return;
        }
        try {
            player.sendMessage(MM.deserialize(template, resolvers));
        } catch (Exception e) {
            plugin.getLogger().warning(() -> "消息模板 " + key + " 解析失败，回退纯文本：" + e.getMessage());
            player.sendMessage(template);
        }
    }
}
