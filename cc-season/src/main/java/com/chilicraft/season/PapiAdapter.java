package com.chilicraft.season;

import org.bukkit.plugin.Plugin;

/** PlaceholderAPI 可选集成的类加载隔离边界。 */
final class PapiAdapter {

    private final ChiliSeasonPlugin plugin;
    private boolean registered;

    PapiAdapter(ChiliSeasonPlugin plugin, SeasonService service, SeasonSettings settings) {
        this.plugin = plugin;
        Plugin papi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (papi == null || !papi.isEnabled()) {
            return;
        }
        try {
            registered = SeasonExpansion.install(plugin, service, settings);
            if (!registered) {
                plugin.getLogger().warning("PlaceholderAPI 季节占位符注册失败");
            }
        } catch (Throwable throwable) {
            plugin.getLogger().warning("PlaceholderAPI 集成已降级：" + throwable.getMessage());
        }
    }

    void close() {
        if (!registered) {
            return;
        }
        try {
            SeasonExpansion.remove();
        } catch (Throwable throwable) {
            plugin.getLogger().warning("PlaceholderAPI 季节占位符注销失败：" + throwable.getMessage());
        } finally {
            registered = false;
        }
    }
}
