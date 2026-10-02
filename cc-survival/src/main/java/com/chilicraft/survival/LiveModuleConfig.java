package com.chilicraft.survival;

import com.chilicraft.api.ModuleConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 稳定的模块配置包装：每次读取都穿透到插件当前持有的 FileConfiguration。
 *
 * <p>不能把 getConfig() 的瞬时引用直接交给核心——JavaPlugin#reloadConfig()
 * 会替换内部实例，旧引用在附属重载后将读到过期数据。
 * 本包装始终读活配置，与契约「核心 /cc reload 时自动合并最新文件内容」一致。</p>
 */
final class LiveModuleConfig implements ModuleConfig {

    private final JavaPlugin plugin;

    LiveModuleConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    @Override
    public String moduleId() {
        return "cc-survival";
    }

    @Override
    public int getInt(String path, int def) {
        return cfg().getInt(path, def);
    }

    @Override
    public double getDouble(String path, double def) {
        return cfg().getDouble(path, def);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return cfg().getBoolean(path, def);
    }

    @Override
    public String getString(String path, String def) {
        return cfg().getString(path, def);
    }

    @Override
    public List<String> getStringList(String path) {
        List<String> list = cfg().getStringList(path);
        return list == null ? List.of() : list;
    }
}
