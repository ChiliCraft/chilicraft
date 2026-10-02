package com.chilicraft.survival;

import com.chilicraft.api.ModuleCommandExecutor;
import com.chilicraft.api.ModuleTabCompleter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

final class SurvivalCommand implements CommandExecutor, TabCompleter, ModuleCommandExecutor, ModuleTabCompleter {
    private final SurvivalSettings settings;
    private final SurvivalGui gui;

    SurvivalCommand(SurvivalSettings settings, SurvivalGui gui) {
        this.settings = settings;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            if (sender instanceof Player player) gui.open(player);
            else sender.sendMessage(settings.message("players-only"));
            return true;
        }
        if (args[0].equalsIgnoreCase("status")) {
            if (sender instanceof Player player) gui.open(player);
            else sender.sendMessage("cc-survival 状态仅可由玩家查看。");
            return true;
        }
        sender.sendMessage(settings.message("usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return complete(sender, args);
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("menu", "status").stream().filter(s -> s.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
