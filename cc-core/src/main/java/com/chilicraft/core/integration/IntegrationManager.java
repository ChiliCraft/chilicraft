package com.chilicraft.core.integration;

import com.chilicraft.core.config.CoreConfig;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部插件联动检测：全部软依赖，缺失自动降级，绝不阻塞加载。
 *
 * <p>启动与 /cc reload 时扫描一次；核心与附属经 {@link #isEnabled}
 * 查询联动能力（插件在场 且 config: integrations 段未关闭）。</p>
 */
public final class IntegrationManager {

    /** 联动键 -> Bukkit 插件名（键即 config: integrations 段的配置键） */
    private record Integration(String key, String pluginName) {
    }

    /** 已知联动清单：与规格第 4 节推荐清单一致 */
    private static final List<Integration> KNOWN = List.of(
            new Integration("placeholders", "PlaceholderAPI"),
            new Integration("vault", "Vault"),
            new Integration("mythicmobs", "MythicMobs"),
            new Integration("citizens", "Citizens"),
            new Integration("essentials", "Essentials"),
            new Integration("worldguard", "WorldGuard"),
            new Integration("luckperms", "LuckPerms"));

    private final Server server;
    private final CoreConfig config;
    private final Logger logger;

    /** 已启用的联动：key -> 插件版本（主线程读写，reload 时整体替换） */
    private final Map<String, String> active = new LinkedHashMap<>();

    public IntegrationManager(Server server, CoreConfig config, Logger logger) {
        this.server = server;
        this.config = config;
        this.logger = logger;
    }

    /** 扫描联动（主线程，启动与 reload 时调用） */
    public void detect() {
        active.clear();
        for (Integration integration : KNOWN) {
            if (!config.integrationEnabled(integration.key())) {
                logger.info("联动 {} 已被配置关闭（integrations.{}=false）",
                        integration.pluginName(), integration.key());
                continue;
            }
            Plugin plugin = server.getPluginManager().getPlugin(integration.pluginName());
            if (plugin != null) {
                String version = plugin.getPluginMeta().getVersion();
                active.put(integration.key(), version);
                logger.info("检测到 {}（v{}），启用联动: {}", integration.pluginName(),
                        version, integration.key());
            }
        }
        if (active.isEmpty()) {
            logger.info("未检测到任何外部联动插件，全部能力走内置降级方案");
        }
    }

    /** 指定联动是否已启用（插件在场且配置开启） */
    public boolean isEnabled(String key) {
        return active.containsKey(key);
    }

    /** 当前已启用联动键集合（不可变快照） */
    public Collection<String> enabledKeys() {
        return Collections.unmodifiableSet(active.keySet());
    }
}
