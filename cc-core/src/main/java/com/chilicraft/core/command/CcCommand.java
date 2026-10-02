package com.chilicraft.core.command;

import com.chilicraft.api.GameMode;
import com.chilicraft.api.ModuleCommandRoute;
import com.chilicraft.api.PlayerProfile;
import com.chilicraft.core.api.ChiliApiImpl;
import com.chilicraft.core.config.CoreConfig;
import com.chilicraft.core.gui.MenuGui;
import com.chilicraft.core.profile.CraftPlayerProfile;
import com.chilicraft.core.profile.ProfileManager;
import com.chilicraft.core.text.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /cc 核心子命令与附属模块统一路由。 */
public final class CcCommand implements CommandExecutor, TabCompleter {
    private static final List<String> CORE = List.of("menu", "help", "info", "home", "sethome", "balance", "soul", "mode");
    private static final List<String> PLANNED = List.of("quest", "cozy", "events", "economy", "street");
    private static final List<String> MODES = List.of("adventure", "cozy");
    private final ProfileManager profiles;
    private final ChiliApiImpl api;
    private final CoreConfig config;
    private final Messages messages;
    private final MenuGui menuGui;
    private final Runnable reloadAction;

    public CcCommand(ProfileManager profiles, ChiliApiImpl api, CoreConfig config, Messages messages,
                     MenuGui menuGui, Runnable reloadAction) {
        this.profiles = profiles;
        this.api = api;
        this.config = config;
        this.messages = messages;
        this.menuGui = menuGui;
        this.reloadAction = reloadAction;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (sender instanceof Player player && (player.hasPermission("chilicraft.menu") || player.hasPermission("chilicraft.use"))) menuGui.open(player);
            else if (sender instanceof Player) sender.sendMessage(messages.get("no-permission", "<red>你没有权限执行此操作。"));
            else sendHelp(sender);
            return true;
        }
        String first = args[0].toLowerCase(Locale.ROOT);
        switch (first) {
            case "help" -> help(sender, args);
            case "reload" -> reload(sender);
            case "mode" -> mode(sender, args);
            case "info" -> info(sender);
            case "balance" -> soul(sender);
            case "soul" -> { if (args.length == 1) soul(sender); else route(sender, "soul", args); }
            case "sethome" -> setHome(sender);
            case "home" -> home(sender);
            default -> {
                if (PLANNED.contains(first)) sender.sendMessage(messages.get("module-planned", "<yellow>该模块正在开发中。"));
                else route(sender, first, args);
            }
        }
        return true;
    }

    private void route(CommandSender sender, String id, String[] args) {
        ModuleCommandRoute route = api.getCommandRoute(id);
        if (route == null) { sender.sendMessage(usage("/cc help")); return; }
        if (!hasAny(sender, route.usePermissions())) { sender.sendMessage(messages.get("no-permission", "<red>你没有权限执行此操作。")); return; }
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        try { route.executor().execute(sender, rest); }
        catch (Throwable throwable) {
            sender.sendMessage(messages.get("module-command-failed", "<red>模块指令执行失败，请稍后再试。"));
        }
    }

    private void help(CommandSender sender, String[] args) {
        if (args.length > 1) {
            ModuleCommandRoute route = api.getCommandRoute(args[1]);
            if (route != null) { route.executor().execute(sender, new String[]{"help"}); return; }
            if (PLANNED.contains(args[1].toLowerCase(Locale.ROOT))) { sender.sendMessage(messages.get("module-planned", "<yellow>该模块正在开发中。")); return; }
        }
        sendHelp(sender);
    }

    private void reload(CommandSender sender) {
        if (!requirePerm(sender, "chilicraft.admin")) return;
        reloadAction.run();
        sender.sendMessage(messages.get("reload-done", "<green>ChiliCraft 配置已重载。"));
    }

    private void mode(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender); if (player == null || !requirePerm(player, "chilicraft.mode")) return;
        if (args.length < 2) { player.sendMessage(usage("/cc mode 「adventure|cozy」")); return; }
        GameMode target = GameMode.fromName(args[1]);
        if (target == null) { player.sendMessage(messages.get("mode-invalid", "<red>未知模式「<name>」。", Placeholder.unparsed("name", args[1]))); return; }
        PlayerProfile profile = api.getProfile(player.getUniqueId());
        if (profile == null) { player.sendMessage(profileMissing()); return; }
        if (profile.mode() == target) { player.sendMessage(messages.get("mode-already", "<yellow>你已处于 <mode> 模式。", Placeholder.unparsed("mode", MenuGui.modeName(target)))); return; }
        try { api.setMode(player.getUniqueId(), target); player.sendMessage(messages.get("mode-switched", "<green>已切换到 <mode> 模式。", Placeholder.unparsed("mode", MenuGui.modeName(target)))); }
        catch (IllegalStateException e) { player.sendMessage(messages.get("mode-cooldown", "<red>模式切换冷却中，剩余约 <minutes> 分钟。", Placeholder.unparsed("minutes", String.valueOf(config.remainingCooldownMinutes(profile.modeSwitchAt()))))); }
    }

    private void info(CommandSender sender) {
        Player player = requirePlayer(sender); if (player == null) return;
        PlayerProfile profile = api.getProfile(player.getUniqueId()); if (profile == null) { player.sendMessage(profileMissing()); return; }
        player.sendMessage(messages.get("info-mode", "<gray>当前模式：<white><mode></white>", Placeholder.unparsed("mode", MenuGui.modeName(profile.mode()))));
        player.sendMessage(messages.get("info-soul", "<gray><name>余额：<yellow><amount></yellow>", Placeholder.unparsed("name", config.soulName()), Placeholder.unparsed("amount", String.valueOf(profile.soul()))));
        player.sendMessage(messages.get("info-realm", "<gray>武学境界：<aqua><realm></aqua>", Placeholder.unparsed("realm", MenuGui.realmName(profile.martialRealm()))));
        player.sendMessage(messages.get("info-profession", "<gray>职业：<white><profession></white>", Placeholder.unparsed("profession", MenuGui.professionName(profile.profession()))));
        player.sendMessage(messages.get("info-home", "<gray>家园区：<white><home></white>", Placeholder.unparsed("home", profile.isHomeSet() ? "已设置" : "未设置")));
    }

    private void soul(CommandSender sender) { Player player = requirePlayer(sender); if (player == null) return; player.sendMessage(messages.get("soul-balance", "<gray>当前 <name> 余额：<yellow><amount></yellow>", Placeholder.unparsed("name", config.soulName()), Placeholder.unparsed("amount", String.valueOf(api.getSoul(player.getUniqueId()))))); }
    private void setHome(CommandSender sender) { Player player = requirePlayer(sender); if (player == null || !requirePerm(player, "chilicraft.home")) return; CraftPlayerProfile profile = profiles.getProfile(player.getUniqueId()); if (profile == null) { player.sendMessage(profileMissing()); return; } Location loc = player.getLocation(); profile.setHome(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ()); player.sendMessage(messages.get("home-set", "<green>家园区锚点已设置。")); }
    private void home(CommandSender sender) { Player player = requirePlayer(sender); if (player == null || !requirePerm(player, "chilicraft.home")) return; CraftPlayerProfile profile = profiles.getProfile(player.getUniqueId()); if (profile == null) { player.sendMessage(profileMissing()); return; } if (!profile.isHomeSet()) { player.sendMessage(messages.get("home-not-set", "<red>你还未设置家园区锚点，请先使用 /cc sethome。")); return; } World world = Bukkit.getWorld(profile.homeWorld()); if (world == null) { player.sendMessage(messages.get("home-world-missing", "<red>家园区所在世界已不存在。")); return; } player.teleport(new Location(world, profile.homeX(), profile.homeY(), profile.homeZ())); player.sendMessage(messages.get("home-teleported", "<green>已传送回家园区锚点。")); }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) { List<String> result = new ArrayList<>(CORE); result.addAll(api.commandRoutes().stream().filter(route -> hasAny(sender, route.usePermissions())).map(ModuleCommandRoute::moduleId).toList()); result.addAll(PLANNED); if (sender.hasPermission("chilicraft.admin")) result.add("reload"); return filter(result, args[0]); }
        if (args.length == 2 && args[0].equalsIgnoreCase("mode")) return filter(MODES, args[1]);
        if (args.length > 1) { ModuleCommandRoute route = api.getCommandRoute(args[0]); if (route != null) return filter(route.completer().complete(sender, java.util.Arrays.copyOfRange(args, 1, args.length)), args[args.length - 1]); }
        return List.of();
    }
    private static boolean hasAny(CommandSender sender, java.util.Set<String> permissions) { return permissions.isEmpty() || permissions.stream().anyMatch(sender::hasPermission); }
    private static List<String> filter(List<String> values, String prefix) { String lower = prefix.toLowerCase(Locale.ROOT); return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).distinct().toList(); }
    private void sendHelp(CommandSender sender) { sender.sendMessage(messages.get("help", "<gold>===== ChiliCraft 指令 =====<newline><yellow>/menu <gray>—— 打开主菜单<newline><yellow>/cc balance <gray>—— 查询灵魂余额<newline><yellow>/cc help [模块] <gray>—— 查看帮助<newline><yellow>/cc reload <gray>—— 重载配置（管理员）")); }
    private Player requirePlayer(CommandSender sender) { if (sender instanceof Player player) return player; sender.sendMessage(messages.get("player-only", "<red>该指令仅玩家可用。")); return null; }
    private boolean requirePerm(CommandSender sender, String permission) { if (sender.hasPermission(permission)) return true; sender.sendMessage(messages.get("no-permission", "<red>你没有权限执行此操作。")); return false; }
    private Component profileMissing() { return messages.get("profile-missing", "<red>档案尚未加载完成，请稍后再试。"); }
    private Component usage(String text) { return messages.get("usage", "<red>用法：<yellow><usage></yellow>", Placeholder.unparsed("usage", text)); }
}
