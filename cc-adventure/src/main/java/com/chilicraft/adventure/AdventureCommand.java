package com.chilicraft.adventure;

import com.chilicraft.api.ModuleCommandExecutor;
import com.chilicraft.api.ModuleTabCompleter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /adventure 组队、地城、远征与 Boss 状态命令；无参数打开箱子 GUI 主面板。 */
final class AdventureCommand implements CommandExecutor, TabCompleter, ModuleCommandExecutor, ModuleTabCompleter {
    static final String USE_PERMISSION = "chilicraft.adventure.use";
    static final String LEGACY_PERMISSION = "chilicraft.adventure.user";
    static final String ADMIN_PERMISSION = "chilicraft.adventure.admin";
    private final PartyService parties;
    private final DungeonService dungeons;
    private final ExpeditionService expeditions;
    private final BossService bosses;
    private final AdventureGui gui;

    AdventureCommand(PartyService parties, DungeonService dungeons,
                     ExpeditionService expeditions, BossService bosses, AdventureGui gui) {
        this.parties = parties;
        this.dungeons = dungeons;
        this.expeditions = expeditions;
        this.bosses = bosses;
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(USE_PERMISSION) && !sender.hasPermission(LEGACY_PERMISSION)) {
            sender.sendMessage("你没有使用冒险线命令的权限。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("该命令只能由玩家执行。");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            gui.openMain(player);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "party" -> party(player, args);
            case "dungeon" -> dungeon(player, args);
            case "expedition" -> expedition(player, args);
            case "boss" -> {
                if (args.length == 2 && args[1].equalsIgnoreCase("status")) {
                    player.sendMessage("已加载世界 Boss：" + bosses.bossCount()
                            + "；你的活跃 Boss：" + bosses.status(player.getUniqueId()));
                } else {
                    usage(player);
                }
            }
            case "status" -> status(player);
            default -> usage(player);
        }
        return true;
    }

    private void party(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("用法：/adventure party <create|invite|accept|leave|kick>");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "create" -> player.sendMessage(parties.create(player.getUniqueId()) ? "队伍已创建。" : "你已经在队伍中。" );
            case "leave" -> {
                parties.leave(player.getUniqueId(), false);
                player.sendMessage("你已离开队伍。");
            }
            case "invite", "accept", "kick" -> {
                if (args.length < 3) {
                    player.sendMessage("请提供玩家名。");
                    return;
                }
                Player target = player.getServer().getPlayerExact(args[2]);
                if (target == null) {
                    player.sendMessage("目标玩家不在线：" + args[2]);
                    return;
                }
                if (args[1].equalsIgnoreCase("invite")) {
                    player.sendMessage("邀请结果：" + parties.invite(player.getUniqueId(), target.getUniqueId()).name());
                } else if (args[1].equalsIgnoreCase("accept")) {
                    player.sendMessage("接受结果：" + parties.accept(player.getUniqueId(), target.getUniqueId()).name());
                } else {
                    player.sendMessage("踢出结果：" + parties.kick(player.getUniqueId(), target.getUniqueId()).name());
                }
            }
            default -> player.sendMessage("未知队伍操作。");
        }
    }

    private void dungeon(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("用法：/adventure dungeon <start|leave|list> [id]");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> player.sendMessage("可用地城：" + String.join(", ", dungeons.listIds()));
            case "leave" -> {
                dungeons.quit(player.getUniqueId());
                player.sendMessage("已离开地城。");
            }
            case "start" -> {
                if (args.length < 3) {
                    player.sendMessage("请提供地城 ID。");
                    return;
                }
                String result = dungeons.start(player.getUniqueId(), args[2]);
                player.sendMessage(result == null ? "地城已开始。" : "地城启动结果：" + result);
            }
            default -> player.sendMessage("未知地城操作。");
        }
    }

    private void expedition(Player player, String[] args) {
        if (args.length < 2 || (!args[1].equalsIgnoreCase("start") && !args[1].equalsIgnoreCase("leave"))) {
            player.sendMessage("用法：/adventure expedition <start|leave>");
            return;
        }
        if (args[1].equalsIgnoreCase("leave")) {
            expeditions.quit(player.getUniqueId());
            player.sendMessage("已离开远征。");
        } else {
            String result = expeditions.start(player.getUniqueId());
            player.sendMessage(result == null ? "远征已开始。" : "远征启动结果：" + result);
        }
    }

    private void status(Player player) {
        player.sendMessage("地城：" + (dungeons.instanceOf(player.getUniqueId()) != null ? "进行中" : "无")
                + "；远征：" + (expeditions.inExpedition(player.getUniqueId()) ? "进行中" : "无")
                + "；Boss 定义：" + bosses.bossCount());
    }

    private void usage(CommandSender sender) {
        sender.sendMessage("用法：/adventure [menu|party|dungeon|expedition|boss status|status]");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return complete(sender, args);
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(USE_PERMISSION) && !sender.hasPermission(LEGACY_PERMISSION)) return List.of();
        if (args.length == 1) return filter(List.of("menu", "party", "dungeon", "expedition", "boss", "status"), args[0]);
        if (args[0].equalsIgnoreCase("party") && args.length == 2) return filter(List.of("create", "invite", "accept", "leave", "kick"), args[1]);
        if (args[0].equalsIgnoreCase("dungeon") && args.length == 2) return filter(List.of("start", "leave", "list"), args[1]);
        if (args[0].equalsIgnoreCase("dungeon") && args.length == 3 && args[1].equalsIgnoreCase("start")) return filter(dungeons.listIds(), args[2]);
        if (args[0].equalsIgnoreCase("expedition") && args.length == 2) return filter(List.of("start", "leave"), args[1]);
        if (args[0].equalsIgnoreCase("boss") && args.length == 2) return filter(List.of("status"), args[1]);
        if (args.length == 3 && args[0].equalsIgnoreCase("party")) {
            List<String> names = new ArrayList<>();
            for (Player p : sender.getServer().getOnlinePlayers()) names.add(p.getName());
            return filter(names, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String input) {
        List<String> result = new ArrayList<>();
        for (String value : values) if (value.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT))) result.add(value);
        return result;
    }
}
