package com.chilicraft.demon;

import com.chilicraft.api.ModuleCommandExecutor;
import com.chilicraft.api.ModuleTabCompleter;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /demon 管理命令：menu 或无参（管理菜单）/ status / clear / spawn / reload。
 * 命令级权限 chilicraft.demon.admin 由 plugin.yml 把守。
 */
final class DemonCommand implements CommandExecutor, TabCompleter, ModuleCommandExecutor, ModuleTabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("menu", "status", "clear", "spawn", "reload");
    private static final List<String> TYPES = List.of("imp", "hunter", "mother");

    private final DemonSettings settings;
    private final DemonManager manager;
    private final DemonGui gui;
    private final DemonStatusGui statusGui;
    private final Runnable reloader;

    DemonCommand(DemonSettings settings, DemonManager manager, DemonGui gui, DemonStatusGui statusGui, Runnable reloader) {
        this.settings = settings;
        this.manager = manager;
        this.gui = gui;
        this.statusGui = statusGui;
        this.reloader = reloader;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) statusGui.open(player);
            else sender.sendMessage("cc-demon 状态仅可由玩家查看。");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "menu" -> {
                if (sender instanceof Player player) {
                    if (player.hasPermission("chilicraft.demon.admin")) gui.openMain(player);
                    else statusGui.open(player);
                } else {
                    sender.sendMessage(Texts.parse(settings.feedback("players-only")));
                }
            }
            case "status" -> {
                if (sender instanceof Player player) statusGui.open(player); else sendStatus(sender);
            }
            case "clear" -> {
                if (!admin(sender)) return true;
                sendClear(sender);
            }
            case "spawn" -> {
                if (!admin(sender)) return true;
                handleSpawn(sender, args);
            }
            case "reload" -> {
                if (!admin(sender)) return true;
                reloader.run();
                sender.sendMessage(Texts.parse(settings.feedback("reloaded")));
            }
            case "admin" -> {
                if (sender instanceof Player player && admin(sender)) gui.openMain(player);
                else if (!admin(sender)) sender.sendMessage(Texts.parse(settings.feedback("no-permission")));
            }
            default -> sender.sendMessage(Texts.parse(settings.feedback("usage")));
        }
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("chilicraft.demon.admin")) return true;
        sender.sendMessage(Texts.parse(settings.feedback("no-permission")));
        return false;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Texts.parse(settings.feedback("status-line"),
                Placeholder.unparsed("count", String.valueOf(manager.total())),
                Placeholder.unparsed("imp", String.valueOf(manager.count(DemonType.IMP))),
                Placeholder.unparsed("hunter", String.valueOf(manager.count(DemonType.HUNTER))),
                Placeholder.unparsed("mother", String.valueOf(manager.count(DemonType.MOTHER)))));
    }

    private void sendClear(CommandSender sender) {
        int removed = manager.clearAll();
        sender.sendMessage(Texts.parse(settings.feedback("cleared"),
                Placeholder.unparsed("count", String.valueOf(removed))));
    }

    private void handleSpawn(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Texts.parse(settings.feedback("spawn-usage")));
            return;
        }
        DemonType type = DemonType.fromId(args[1].toLowerCase(Locale.ROOT));
        if (type == null) {
            sender.sendMessage(Texts.parse(settings.feedback("spawn-usage")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Texts.parse(settings.feedback("players-only")));
            return;
        }
        if (manager.total() >= settings.globalLimit) {
            sender.sendMessage(Texts.parse(settings.feedback("world-full")));
            return;
        }
        manager.spawn(type, frontOf(player), null);
        sender.sendMessage(Texts.parse(settings.feedback("spawned"),
                Placeholder.unparsed("type", type.displayName())));
    }

    /** 玩家面前 3 格：水平视线方向；近垂直视角回退 (0,0,1) */
    private Location frontOf(Player player) {
        Vector direction = player.getLocation().getDirection();
        Vector horizontal = new Vector(direction.getX(), 0, direction.getZ());
        if (horizontal.lengthSquared() < 1.0e-4) {
            horizontal = new Vector(0, 0, 1);
        }
        return player.getLocation().add(horizontal.normalize().multiply(3));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return complete(sender, args);
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        List<String> visible = sender.hasPermission("chilicraft.demon.admin")
                ? List.of("menu", "status", "admin", "clear", "spawn", "reload")
                : List.of("menu", "status", "help");
        if (args.length == 1) {
            return filter(visible, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")
                && sender.hasPermission("chilicraft.demon.admin")) {
            return filter(TYPES, args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
