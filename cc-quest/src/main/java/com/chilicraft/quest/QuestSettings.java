package com.chilicraft.quest;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
final class QuestSettings {
    private final JavaPlugin plugin; private Map<String,String> messages=Map.of(); String guideTitle="<gold>旅途手册", detailTitle="<gold>任务详情";
    QuestSettings(JavaPlugin plugin){this.plugin=plugin;} void refresh(){FileConfiguration c=plugin.getConfig(); guideTitle=c.getString("gui.title",guideTitle); detailTitle=c.getString("gui.detail-title",detailTitle); Map<String,String> m=new HashMap<>(); var s=c.getConfigurationSection("messages"); if(s!=null) for(String k:s.getKeys(false)){String v=s.getString(k);if(v!=null)m.put(k,v);} messages=Map.copyOf(m);}
    String message(String k,String d){return messages.getOrDefault(k,d);}
}
