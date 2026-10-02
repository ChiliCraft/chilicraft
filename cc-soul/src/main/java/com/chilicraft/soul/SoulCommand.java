package com.chilicraft.soul;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /soul 玩家命令：menu 或无参（灵魂菜单）/ fragments（碎片导航）/ deaths（最近死亡）/
 * funeral（开始葬礼）/ relic give（管理员发放）/ reload（重载配置）。
 *
 * <p>参数解析、权限检查与游戏逻辑三层分离；玩家专属子命令统一先做类型校验；
 * 命令级权限 chilicraft.soul.user 由 plugin.yml 把守，relic give 追加
 * chilicraft.soul.admin 代码级校验。</p>
 */
final class SoulCommand implements CommandExecutor, TabCompleter {

    private final SoulSettings settings;
    private final RelicService relics;
    private final FragmentService fragments;
    private final DeathService deaths;
    private final FuneralService funeral;
    private final SoulGui gui;
    private final Runnable reloader;

    SoulCommand(SoulSettings settings, RelicService relics, FragmentService fragments,
                DeathService deaths, FuneralService funeral, SoulGui gui, Runnable reloader) {
        this.settings = settings;
        this.relics = relics;
        this.fragments = fragments;
        this.deaths = deaths;
        this.funeral = funeral;
        this.gui = gui;
        this.reloader = reloader;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                gui.openMain(player);
            } else {
                playersOnly(sender);
            }
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> sender.sendMessage(Texts.parse(settings.feedback("usage")));
            case "menu" -> {
                if (sender instanceof Player player) {
                    gui.openMain(player);
                } else {
                    playersOnly(sender);
                }
            }
            case "fragments" -> {
                if (sender instanceof Player player) {
                    fragments.sendNav(player);
                } else {
                    playersOnly(sender);
                }
            }
            case "deaths" -> {
                if (sender instanceof Player player) {
                    handleDeaths(player);
                } else {
                    playersOnly(sender);
                }
            }
            case "relics" -> sendRelics(sender);
            case "funeral" -> {
                if (sender instanceof Player player) {
                    funeral.start(player);
                } else {
                    playersOnly(sender);
                }
            }
            case "relic" -> handleRelic(sender, args);
            case "reload" -> {
                if (!sender.hasPermission("chilicraft.soul.admin")) {
                    sender.sendMessage(Texts.parse(settings.feedback("no-permission")));
                    return true;
                }
                reloader.run();
                sender.sendMessage(Texts.parse(settings.feedback("reloaded")));
            }
            default -> {
                sender.sendMessage(Texts.parse(settings.feedback("usage")));
            }
        }
        return true;
    }

    // ---------------- 子命令实现 ----------------

    /** 最近一次死亡记录（含中文死因与格式化时间） */
    private void handleDeaths(Player player) {
        DeathService.DeathRecord rec = deaths.latestDeath(player.getUniqueId());
        if (rec == null) {
            player.sendMessage(Texts.parse(settings.feedback("deaths-none")));
            return;
        }
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(rec.diedAt()));
        player.sendMessage(Texts.parse(settings.feedback("deaths-info"),
                Placeholder.unparsed("cause", DeathService.causeText(rec.cause())),
                Placeholder.unparsed("time", time),
                Placeholder.unparsed("world", rec.world()),
                Placeholder.unparsed("x", String.valueOf((int) Math.floor(rec.x()))),
                Placeholder.unparsed("y", String.valueOf((int) Math.floor(rec.y()))),
                Placeholder.unparsed("z", String.valueOf((int) Math.floor(rec.z())))));
    }

    /** 遗物图鉴：在场数量 / 定义总数 + 逐条列表 */
    private void sendRelics(CommandSender sender) {
        Map<String, RelicService.CachedRelic> snapshot = relics.snapshot();
        sender.sendMessage(Texts.parse(settings.feedback("relics-header"),
                Placeholder.unparsed("count", String.valueOf(snapshot.size())),
                Placeholder.unparsed("total", String.valueOf(settings.relicDefinitions.size()))));
        for (String relicId : snapshot.keySet()) {
            RelicDefinition def = settings.relic(relicId);
            TagResolver display = def != null
                    ? Placeholder.component("display", Texts.parse(def.display()))
                    : Placeholder.unparsed("display", relicId);
            sender.sendMessage(Texts.parse(settings.feedback("relics-line"),
                    display, Placeholder.unparsed("id", relicId)));
        }
    }

    /** 管理员发放遗物：权限 → 参数 → 目标在线 → 定义存在 → 全服唯一 */
    private void handleRelic(CommandSender sender, String[] args) {
        if (!sender.hasPermission("chilicraft.soul.admin")) {
            sender.sendMessage(Texts.parse(settings.feedback("no-permission")));
            return;
        }
        if (args.length < 4 || !args[1].equalsIgnoreCase("give")) {
            sender.sendMessage(Texts.parse(settings.feedback("relic-give-usage")));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            sender.sendMessage(Texts.parse(settings.feedback("player-not-found"),
                    Placeholder.unparsed("player", args[2])));
            return;
        }
        String relicId = args[3].toLowerCase(Locale.ROOT);
        RelicDefinition def = settings.relic(relicId);
        if (def == null) {
            sender.sendMessage(Texts.parse(settings.feedback("relic-not-found"),
                    Placeholder.unparsed("id", relicId)));
            return;
        }
        if (!relics.give(relicId, target)) {
            sender.sendMessage(Texts.parse(settings.feedback("relic-already-active"),
                    Placeholder.component("display", Texts.parse(def.display()))));
            return;
        }
        sender.sendMessage(Texts.parse(settings.feedback("relic-given"),
                Placeholder.component("display", Texts.parse(def.display())),
                Placeholder.unparsed("player", target.getName())));
    }

    private void playersOnly(CommandSender sender) {
        sender.sendMessage(Texts.parse(settings.feedback("players-only")));
    }

    // ---------------- Tab 补全 ----------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return complete(sender, args);
    }

    List<String> complete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            List<String> visible = new ArrayList<>(List.of("menu", "fragments", "deaths", "relics", "funeral"));
            if (sender.hasPermission("chilicraft.soul.admin")) {
                visible.add("relic");
                visible.add("reload");
            }
            return filter(visible, args[0]);
        }
        if (args[0].equalsIgnoreCase("relic") && sender.hasPermission("chilicraft.soul.admin")) {
            if (args.length == 2) {
                return filter(List.of("give"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("give")) {
                List<String> names = new ArrayList<>();
                for (Player online : Bukkit.getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return filter(names, args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("give")) {
                return filter(List.copyOf(settings.relicDefinitions.keySet()), args[3]);
            }
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
