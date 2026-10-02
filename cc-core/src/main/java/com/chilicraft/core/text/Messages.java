package com.chilicraft.core.text;

import com.chilicraft.core.config.CoreConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * 消息服务：config messages 段（MiniMessage 模板）→ Adventure Component。
 *
 * <p>键缺失或模板非法时回退内置默认值，绝不让坏配置吞掉玩家反馈。
 * 项目文本方案统一 MiniMessage，不使用旧版颜色代码。</p>
 */
public final class Messages {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final CoreConfig config;

    public Messages(CoreConfig config) {
        this.config = config;
    }

    /**
     * 取消息模板并解析为 Component；缺失/解析失败回退 def（def 视为纯文本兜底）。
     * 模板内可用 <xxx> 标签占位，由 placeholders 以 Placeholder.unparsed 注入（不二次解析，防注入）。
     */
    public Component get(String key, String def, TagResolver... placeholders) {
        String template = config.message(key);
        if (template == null || template.isEmpty()) {
            template = def;
        }
        try {
            return MINI.deserialize(template, placeholders);
        } catch (Throwable t) {
            return Component.text(def);
        }
    }
}
