package com.chilicraft.martial;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * /martial 命令：GUI 面板 / 流派 / 技能 / 擂台入口 / 管理子命令。
 *
 * <p>参数解析、权限检查、游戏逻辑（SkillService/RealmService）三层分离；
 * admin 与 reload 子命令在代码内检查 chilicraft.martial.admin
 * （plugin.yml 中命令本体权限 chilicraft.martial.user default true）。
 * 无参 / menu / 未知子命令打开 GUI 主面板；聊天版子命令（school/skills/arena）
 * 保留给偏好命令行的玩家。</p>
 */
final class MartialCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "chilicraft.martial.admin";

    private final JavaPlugin plugin;
    private final MartialSettings settings;
    private final RealmService realms;
    private final SkillService skills;
    private final AffixService affixes;
    private final ArenaService arenas;
    private final MartialGui gui;

    MartialCommand(JavaPlugin plugin, MartialSettings settings, RealmService realms,
                   SkillService skills, AffixService affixes, ArenaService arenas,
                   MartialGui gui) {
        this.plugin = plugin;
        this.settings = settings;
        this.realms = realms;
        this.skills = skills;
        this.affixes = affixes;
        this.arenas = arenas;
        this.gui = gui;
    }

    // ================= 执行 =================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return execute(sender, args);
    }

    boolean execute(CommandSender sender, String[] args) {
        // 管理子命令（控制台可用）
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                send(sender, "no-permission", Map.of());
                return true;
            }
            handleAdmin(sender, args);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission(ADMIN_PERMISSION)) {
                send(sender, "no-permission", Map.of());
                return true;
            }
            plugin.reloadConfig();
            settings.refresh();
            skills.reloadContent();
            affixes.reloadContent();
            send(sender, "reloaded", Map.of());
            return true;
        }

        // 玩家命令
        if (!(sender instanceof Player)) {
            send(sender, "players-only", Map.of());
            return true;
        }
        Player player = (Player) sender;
        if (args.length == 0) {
            gui.openMain(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "help" -> send(sender, "usage", Map.of());
            case "menu" -> gui.openMain(player);
            case "school" -> handleSchool(player, args);
            case "skills" -> handleSkills(player, args);
            case "arena" -> handleArena(player, args);
            default -> send(sender, "usage", Map.of());
        }
        return true;
    }

    // ================= 子命令处理 =================

    private void handleSchool(Player player, String[] args) {
        if (args.length == 1) {
            PlayerSkillData data = skills.data(player.getUniqueId());
            if (data == null || data.school == null) {
                send(player, "school-need-join", Map.of());
            } else {
                SkillContent.SchoolDef def = skills.content().schools.get(data.school);
                send(player, "school-current",
                        Map.of("school", def != null ? def.display : data.school));
            }
            return;
        }
        switch (args[1].toLowerCase()) {
            case "list" -> listSchools(player);
            case "join" -> {
                if (args.length < 3) {
                    listSchools(player);
                    return;
                }
                skills.joinSchool(player, args[2].toLowerCase());
            }
            default -> listSchools(player);
        }
    }

    private void listSchools(Player player) {
        send(player, "school-list-header", Map.of());
        for (SkillContent.SchoolDef def : skills.content().schools.values()) {
            send(player, "school-entry", Map.of("id", def.id, "display", def.display));
        }
    }

    private void handleSkills(Player player, String[] args) {
        if (args.length == 1) {
            sendSkillList(player);
            return;
        }
        switch (args[1].toLowerCase()) {
            case "learn" -> {
                if (args.length >= 3) {
                    skills.learn(player, args[2].toLowerCase());
                } else {
                    sendSkillList(player);
                }
            }
            case "equip" -> {
                if (args.length >= 3) {
                    skills.equip(player, args[2].toLowerCase());
                } else {
                    sendSkillList(player);
                }
            }
            case "unequip" -> {
                if (args.length >= 3) {
                    skills.unequip(player, args[2].toLowerCase());
                } else {
                    sendSkillList(player);
                }
            }
            case "select" -> {
                if (args.length >= 3) {
                    skills.select(player, args[2].toLowerCase());
                } else {
                    sendSkillList(player);
                }
            }
            default -> sendSkillList(player);
        }
    }

    /** /martial arena：无参状态面板，join/quit 报名与取消 */
    private void handleArena(Player player, String[] args) {
        if (args.length >= 2) {
            switch (args[1].toLowerCase()) {
                case "join" -> arenas.join(player);
                case "quit" -> arenas.quitArena(player);
                default -> arenas.sendStatus(player);
            }
            return;
        }
        arenas.sendStatus(player);
    }

    /** 技能列表：状态判定优先级 选中 > 已装备 > 已学 > 可学(境界达标) > 未达 */
    private void sendSkillList(Player player) {
        PlayerSkillData data = skills.data(player.getUniqueId());
        if (data == null || data.school == null) {
            send(player, "school-need-join", Map.of());
            return;
        }
        SkillContent.SchoolDef school = skills.content().schools.get(data.school);
        send(player, "skills-header",
                Map.of("school", school != null ? school.display : data.school));
        int realm = realms.realm(player.getUniqueId());
        Map<String, SkillContent.SkillDef> group =
                skills.content().skillsBySchool.getOrDefault(data.school, Map.of());
        for (SkillContent.SkillDef def : group.values()) {
            SkillProgress progress = data.skills.get(def.key);
            String stateKey;
            if (def.key.equals(data.selected)) {
                stateKey = "skill-state-selected";
            } else if (isEquipped(data, def.key)) {
                stateKey = "skill-state-equipped";
            } else if (progress != null) {
                stateKey = "skill-state-learned";
            } else if (realm >= def.requiredRealm) {
                stateKey = "skill-state-learnable";
            } else {
                stateKey = "skill-state-locked";
            }
            String detail;
            if (def.isPassive()) {
                detail = "Lv." + (progress != null ? progress.level : 1) + " · 被动";
            } else {
                detail = "冷却 " + def.cooldown + "s · 需" + settings.realmDisplay[def.requiredRealm];
            }
            send(player, "skill-entry", Map.of(
                    "state", settings.message(stateKey),
                    "display", def.display,
                    "detail", detail));
        }
    }

    private boolean isEquipped(PlayerSkillData data, String skillKey) {
        for (String slot : data.slots) {
            if (skillKey.equals(slot)) {
                return true;
            }
        }
        return false;
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (args.length < 3) {
            send(sender, "admin-usage", Map.of());
            return;
        }
        switch (args[1].toLowerCase()) {
            case "resetschool" -> {
                Player target = plugin.getServer().getPlayerExact(args[2]);
                if (target == null) {
                    send(sender, "player-not-found", Map.of("player", args[2]));
                    return;
                }
                skills.resetSchool(target);
                send(sender, "school-reset", Map.of("player", target.getName()));
            }
            case "handbook" -> {
                Player target = plugin.getServer().getPlayerExact(args[2]);
                if (target == null) {
                    send(sender, "player-not-found", Map.of("player", args[2]));
                    return;
                }
                skills.giveHandbookTo(target);
                send(sender, "handbook-admin-given", Map.of("player", target.getName()));
            }
            case "affix" -> handleAdminAffix(sender, args);
            default -> send(sender, "admin-usage", Map.of());
        }
    }

    /** admin affix <玩家> [词缀id]：无 id 随机一条；主手武器工具施加 */
    private void handleAdminAffix(CommandSender sender, String[] args) {
        Player target = plugin.getServer().getPlayerExact(args[2]);
        if (target == null) {
            send(sender, "player-not-found", Map.of("player", args[2]));
            return;
        }
        AffixContent.AffixDef def;
        if (args.length >= 4) {
            def = affixes.content().affixes.get(args[3].toLowerCase());
            if (def == null) {
                send(sender, "affix-unknown", Map.of("id", args[3]));
                return;
            }
        } else {
            def = affixes.randomAffix();
            if (def == null) {
                send(sender, "affix-unknown", Map.of("id", "-"));
                return;
            }
        }
        ItemStack item = target.getInventory().getItemInMainHand();
        if (!affixes.applyAffix(item, def)) {
            if (item == null || item.getType().isAir()) {
                send(sender, "affix-none", Map.of());
            } else {
                send(sender, "affix-full",
                        Map.of("max", String.valueOf(settings.affixMaxPerItem)));
            }
            return;
        }
        send(sender, "affix-admin-applied",
                Map.of("player", target.getName(), "affix", def.display));
    }

    // ================= Tab 补全 =================

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return complete(sender, args);
    }

    List<String> complete(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission(ADMIN_PERMISSION);
        if (args.length == 1) {
            List<String> out = new ArrayList<>(List.of("menu", "school", "skills", "arena"));
            if (admin) {
                out.add("admin");
                out.add("reload");
            }
            return filter(out, args[0]);
        }
        String sub = args[0].toLowerCase();
        if (sub.equals("school")) {
            if (args.length == 2) {
                return filter(List.of("list", "join"), args[1]);
            }
            if (args.length == 3 && args[1].equalsIgnoreCase("join")) {
                return filter(new ArrayList<>(skills.content().schools.keySet()), args[2]);
            }
        } else if (sub.equals("arena")) {
            if (args.length == 2) {
                return filter(List.of("join", "quit"), args[1]);
            }
        } else if (sub.equals("skills")) {
            if (args.length == 2) {
                return filter(List.of("learn", "equip", "unequip", "select"), args[1]);
            }
            if (args.length == 3 && sender instanceof Player) {
                Player player = (Player) sender;
                List<String> ids = new ArrayList<>();
                PlayerSkillData data = skills.data(player.getUniqueId());
                if (data != null && data.school != null) {
                    for (String key : skills.content().skillsBySchool
                            .getOrDefault(data.school, Map.of()).keySet()) {
                        ids.add(key.substring(key.indexOf(':') + 1));
                    }
                }
                return filter(ids, args[2]);
            }
        } else if (sub.equals("admin") && admin) {
            if (args.length == 2) {
                return filter(List.of("resetschool", "handbook", "affix"), args[1]);
            }
            if (args.length == 3) {
                List<String> names = new ArrayList<>();
                for (Player online : plugin.getServer().getOnlinePlayers()) {
                    names.add(online.getName());
                }
                return filter(names, args[2]);
            }
            if (args.length == 4 && args[1].equalsIgnoreCase("affix")) {
                return filter(new ArrayList<>(affixes.content().affixes.keySet()), args[3]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String input) {
        String lower = input.toLowerCase();
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                out.add(option);
            }
        }
        return out;
    }

    // ================= 发送工具 =================

    private void send(CommandSender sender, String key, Map<String, String> placeholders) {
        String template = settings.message(key);
        if (template.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            template = template.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        sender.sendMessage(Texts.parse(template));
    }
}
