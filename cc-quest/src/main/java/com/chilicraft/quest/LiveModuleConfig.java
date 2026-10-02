package com.chilicraft.quest;
import com.chilicraft.api.ModuleConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;
final class LiveModuleConfig implements ModuleConfig {
    private final JavaPlugin plugin;
    LiveModuleConfig(JavaPlugin plugin) { this.plugin = plugin; }
    private FileConfiguration c() { return plugin.getConfig(); }
    public String moduleId() { return "cc-quest"; }
    public int getInt(String p,int d){return c().getInt(p,d);} public double getDouble(String p,double d){return c().getDouble(p,d);}
    public boolean getBoolean(String p,boolean d){return c().getBoolean(p,d);} public String getString(String p,String d){return c().getString(p,d);}
    public List<String> getStringList(String p){return c().getStringList(p);}
}
