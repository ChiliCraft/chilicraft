package com.chilicraft.api;

import org.bukkit.command.CommandSender;

import java.util.List;

@FunctionalInterface
public interface ModuleTabCompleter {
    List<String> complete(CommandSender sender, String[] args);
}
