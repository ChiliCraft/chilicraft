package com.chilicraft.core.module;

import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.ModuleMenuEntry;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 核心统一入口注册表，所有操作均在主线程进行。 */
public final class ModuleRegistry {
    private final Map<String, ModuleMenuEntry> menuEntries = new LinkedHashMap<>();
    private final Map<String, ModuleCommandRoute> commandRoutes = new LinkedHashMap<>();
    private final Map<String, String> aliases = new LinkedHashMap<>();

    public void registerMenuEntry(ModuleMenuEntry entry) {
        requirePrimaryThread("registerMenuEntry");
        if (entry == null) throw new IllegalArgumentException("entry 不能为 null");
        if (menuEntries.containsKey(entry.moduleId())) throw new IllegalStateException("菜单模块重复注册: " + entry.moduleId());
        menuEntries.put(entry.moduleId(), entry);
    }

    public void unregisterMenuEntry(String moduleId) {
        requirePrimaryThread("unregisterMenuEntry");
        if (moduleId != null) menuEntries.remove(moduleId);
    }

    public List<ModuleMenuEntry> menuEntries() {
        requirePrimaryThread("menuEntries");
        return menuEntries.values().stream()
                .sorted(Comparator.comparing(ModuleMenuEntry::category).thenComparingInt(ModuleMenuEntry::sortOrder).thenComparing(ModuleMenuEntry::moduleId))
                .toList();
    }

    public void registerCommandRoute(ModuleCommandRoute route) {
        requirePrimaryThread("registerCommandRoute");
        if (route == null) throw new IllegalArgumentException("route 不能为 null");
        if (commandRoutes.containsKey(route.moduleId()) || aliases.containsKey(route.moduleId())) {
            throw new IllegalStateException("命令模块重复注册: " + route.moduleId());
        }
        List<String> names = new ArrayList<>(route.aliases());
        names.add(route.moduleId());
        for (String name : names) {
            String key = name.toLowerCase(Locale.ROOT);
            if (key.equals(route.moduleId()) && !name.equals(route.moduleId())
                    || aliases.containsKey(key) || commandRoutes.containsKey(key)) {
                throw new IllegalStateException("命令别名冲突: " + name);
            }
        }
        commandRoutes.put(route.moduleId(), route);
        for (String alias : route.aliases()) aliases.put(alias, route.moduleId());
    }

    public void unregisterCommandRoute(String moduleId) {
        requirePrimaryThread("unregisterCommandRoute");
        if (moduleId == null) return;
        String id = moduleId.toLowerCase(Locale.ROOT);
        commandRoutes.remove(id);
        aliases.entrySet().removeIf(entry -> entry.getValue().equals(id));
    }

    public ModuleCommandRoute getCommandRoute(String moduleIdOrAlias) {
        requirePrimaryThread("getCommandRoute");
        if (moduleIdOrAlias == null) return null;
        String key = moduleIdOrAlias.toLowerCase(Locale.ROOT);
        ModuleCommandRoute route = commandRoutes.get(key);
        return route != null ? route : commandRoutes.get(aliases.get(key));
    }

    public List<ModuleCommandRoute> commandRoutes() {
        requirePrimaryThread("commandRoutes");
        return commandRoutes.values().stream().sorted(java.util.Comparator.comparing(ModuleCommandRoute::moduleId)).toList();
    }

    public void clear() {
        requirePrimaryThread("clear");
        menuEntries.clear();
        commandRoutes.clear();
        aliases.clear();
    }

    private static void requirePrimaryThread(String action) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ModuleRegistry." + action + " 只允许主线程调用");
    }
}
