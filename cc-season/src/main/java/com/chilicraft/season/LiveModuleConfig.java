package com.chilicraft.season;

import com.chilicraft.api.ModuleConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;

final class LiveModuleConfig implements ModuleConfig {
    private final JavaPlugin plugin;
    LiveModuleConfig(JavaPlugin plugin) { this.plugin = plugin; }
    private FileConfiguration cfg() { return plugin.getConfig(); }
    @Override public String moduleId() { return "cc-season"; }
    @Override public int getInt(String path, int def) { return cfg().getInt(path, def); }
    @Override public double getDouble(String path, double def) { return cfg().getDouble(path, def); }
    @Override public boolean getBoolean(String path, boolean def) { return cfg().getBoolean(path, def); }
    @Override public String getString(String path, String def) { return cfg().getString(path, def); }
    @Override public List<String> getStringList(String path) { return cfg().getStringList(path); }
}
