package com.chilicraft.api;

import org.bukkit.command.CommandSender;

@FunctionalInterface
public interface ModuleCommandExecutor {
    boolean execute(CommandSender sender, String[] args);
}
