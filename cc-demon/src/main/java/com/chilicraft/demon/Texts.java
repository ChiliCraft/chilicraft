package com.chilicraft.demon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * 共享文本解析：MiniMessage 模板 → Adventure 组件。
 *
 * <p>空模板返回空组件（展示层静默）；解析失败回退纯文本，保证配置错误不炸消息。</p>
 */
final class Texts {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Texts() {
    }

    static Component parse(String template, TagResolver... resolvers) {
        if (template == null || template.isEmpty()) {
            return Component.empty();
        }
        try {
            return MINI.deserialize(template, resolvers);
        } catch (Throwable t) {
            // 模板语法错误（如未闭合标签）：回退纯文本，不让消息系统崩溃
            return Component.text(template);
        }
    }
}
