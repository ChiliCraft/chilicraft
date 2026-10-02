package com.chilicraft.core.command;

import com.chilicraft.core.gui.MenuGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /menu 与 /cc menu 共用主菜单入口。 */
public final class MenuCommand implements CommandExecutor {
    private final MenuGui menuGui;

    public MenuCommand(MenuGui menuGui) {
        this.menuGui = menuGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该指令仅玩家可用。");
            return true;
        }
        if (!player.hasPermission("chilicraft.menu") && !player.hasPermission("chilicraft.use")) {
            player.sendMessage("你没有权限执行此操作。");
            return true;
        }
        menuGui.open(player);
        return true;
    }
}
