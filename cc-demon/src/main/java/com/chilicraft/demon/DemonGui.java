package com.chilicraft.demon;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * /demon 主面板门面（总览快照 / 三类型手动生成 / 全服清除 / 参数入口）。
 *
 * <p>复用 cc-core 的 GuiHolder/GuiListener（instanceof 识别，本模块零监听器）；
 * 快照式菜单——每次打开全新构建，动作完成后重建刷新（cc-adventure / cc-martial /
 * cc-soul 同范式）。生成与清除的点击动作与 /demon spawn / clear 完全同口径
 * （globalLimit 校验 + 相同 feedback 键），两种入口行为一致。</p>
 */
final class DemonGui {

    private final DemonSettings settings;
    private final DemonManager manager;
    private final DemonConfigGui configGui;

    DemonGui(DemonSettings settings, DemonManager manager) {
        this.settings = settings;
        this.manager = manager;
        this.configGui = new DemonConfigGui(settings, this);
    }

    /** 打开主面板（主线程）：槽 4 总览 / 10·12·14 生成 / 16 清除 / 20 参数 / 24 关闭 */
    void openMain(Player player) {
        GuiHolder holder = new GuiHolder(27,
                DemonGuiItems.text(settings, "gui-title-main", "<dark_red>饿魔管理"));

        holder.set(4, statusButton(player));

        holder.set(10, spawnButton(DemonType.IMP), (p, t) -> spawnAt(p, DemonType.IMP));
        holder.set(12, spawnButton(DemonType.HUNTER), (p, t) -> spawnAt(p, DemonType.HUNTER));
        holder.set(14, spawnButton(DemonType.MOTHER), (p, t) -> spawnAt(p, DemonType.MOTHER));

        holder.set(16, clearButton(), (p, t) -> {
            // 反馈与 /demon clear 同口径：相同 feedback 键与占位符
            int removed = manager.clearAll();
            p.sendMessage(Texts.parse(settings.feedback("cleared"),
                    Placeholder.unparsed("count", String.valueOf(removed))));
            openMain(p);
        });

        holder.set(20, DemonGuiItems.button(Material.BOOK,
                        DemonGuiItems.text(settings, "gui-main-config-name", "<gold>参数总览"),
                        List.of(DemonGuiItems.text(settings, "gui-main-config-lore",
                                "<gray>查看刷怪 / 掉落 / MythicMobs 等当前配置</gray>"))),
                (p, t) -> configGui.open(p));

        holder.set(24, DemonGuiItems.button(Material.BARRIER,
                DemonGuiItems.text(settings, "gui-close", "<red>关闭"), List.of()),
                (p, t) -> p.closeInventory());
        holder.open(player);
    }

    /** 总览快照（纯展示）：总量与上限 / 分类型计数 / 刷怪开关 / 当前世界昼夜 */
    private ItemStack statusButton(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-main-status-lore-total",
                "<gray>当前总数：<total> / <limit></gray>",
                "<total>", String.valueOf(manager.total()),
                "<limit>", String.valueOf(settings.globalLimit)));
        lore.add(DemonGuiItems.line(settings, "gui-main-status-lore-imp",
                "<gray>小饿魔：<imp></gray>", "<imp>", String.valueOf(manager.count(DemonType.IMP))));
        lore.add(DemonGuiItems.line(settings, "gui-main-status-lore-hunter",
                "<gray>饿意猎手：<hunter></gray>", "<hunter>", String.valueOf(manager.count(DemonType.HUNTER))));
        lore.add(DemonGuiItems.line(settings, "gui-main-status-lore-mother",
                "<gray>饿魔母体：<mother></gray>", "<mother>", String.valueOf(manager.count(DemonType.MOTHER))));
        lore.add(DemonGuiItems.line(settings, "gui-main-status-lore-spawn",
                "<gray>自动刷怪：<state></gray>", "<state>", stateComponent(settings.spawnEnabled)));
        lore.add(DemonGuiItems.line(settings, "gui-main-status-lore-time",
                "<gray>当前世界：<time></gray>", "<time>", timeComponent(player)));
        return DemonGuiItems.button(Material.SPAWNER,
                DemonGuiItems.text(settings, "gui-main-status-name", "<red>饿魔总览"), lore);
    }

    /** 生成按钮（纯展示）：图标随类型 / 底座与属性 / 生成路径 / 点击提示 */
    private ItemStack spawnButton(DemonType type) {
        Material icon = switch (type) {
            case IMP -> Material.ROTTEN_FLESH;
            case HUNTER -> Material.BOW;
            case MOTHER -> Material.IRON_INGOT;
        };
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-main-spawn-lore-entity",
                "<gray>底座：<entity></gray>", "<entity>", DemonGuiItems.raw(entityName(type.entity()))));
        DemonSettings.Stats stats = settings.stats.getOrDefault(type, new DemonSettings.Stats(20.0, 5.0));
        lore.add(DemonGuiItems.line(settings, "gui-main-spawn-lore-stats",
                "<gray>生命：<health> / 攻击：<damage></gray>",
                "<health>", DemonGuiItems.num(stats.health()),
                "<damage>", DemonGuiItems.num(stats.damage())));
        String mythicId = settings.mythicIds.get(type);
        if (mythicId != null) {
            lore.add(DemonGuiItems.line(settings, "gui-main-spawn-lore-mythic",
                    "<gray>生成路径：MythicMobs（<id>）</gray>", "<id>", DemonGuiItems.raw(mythicId)));
        } else {
            lore.add(DemonGuiItems.text(settings, "gui-main-spawn-lore-vanilla",
                    "<gray>生成路径：原版属性改造</gray>"));
        }
        lore.add(DemonGuiItems.text(settings, "gui-main-spawn-lore-click",
                "<yellow>点击在面前生成</yellow>"));
        return DemonGuiItems.button(icon,
                DemonGuiItems.line(settings, "gui-main-spawn-name",
                        "<red>生成：<type></red>", "<type>", DemonGuiItems.raw(type.displayName())),
                lore);
    }

    /** 清除按钮（纯展示）：当前将清除的数量即时读取 */
    private ItemStack clearButton() {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-main-clear-lore-count",
                "<gray>将清除：<count> 只</gray>", "<count>", String.valueOf(manager.total())));
        lore.add(DemonGuiItems.text(settings, "gui-main-clear-lore-click",
                "<yellow>点击清除全部存活饿魔</yellow>"));
        return DemonGuiItems.button(Material.TNT,
                DemonGuiItems.text(settings, "gui-main-clear-name", "<red>清除全部饿魔"), lore);
    }

    /** 生成动作：与 /demon spawn 同口径（globalLimit 校验 + spawned 反馈），完成后重建刷新 */
    private void spawnAt(Player player, DemonType type) {
        if (manager.total() >= settings.globalLimit) {
            player.sendMessage(Texts.parse(settings.feedback("world-full")));
            return;
        }
        manager.spawn(type, frontOf(player), null);
        player.sendMessage(Texts.parse(settings.feedback("spawned"),
                Placeholder.unparsed("type", type.displayName())));
        openMain(player);
    }

    /** 开关状态文本（参数面板复用）：config 缺失回退代码默认 */
    Component stateComponent(boolean enabled) {
        return enabled
                ? DemonGuiItems.text(settings, "gui-state-on", "<green>开")
                : DemonGuiItems.text(settings, "gui-state-off", "<red>关");
    }

    /** 当前世界昼夜文本（参数面板复用）：与周期任务同口径读 getWorld().getTime() */
    Component timeComponent(Player player) {
        return settings.isNight(player.getWorld().getTime())
                ? DemonGuiItems.text(settings, "gui-night-yes", "<dark_purple>夜晚")
                : DemonGuiItems.text(settings, "gui-night-no", "<yellow>白天");
    }

    /** 底座中文名（口径同 DemonType.displayName 硬兜底） */
    private static String entityName(EntityType type) {
        return switch (type) {
            case ZOMBIE -> "僵尸";
            case SKELETON -> "骷髅";
            case IRON_GOLEM -> "铁傀儡";
            default -> type.name();
        };
    }

    /** 玩家面前 3 格：与 /demon spawn 同口径（水平视线方向；近垂直视角回退 (0,0,1)） */
    private static Location frontOf(Player player) {
        Vector direction = player.getLocation().getDirection();
        Vector horizontal = new Vector(direction.getX(), 0, direction.getZ());
        if (horizontal.lengthSquared() < 1.0e-4) {
            horizontal = new Vector(0, 0, 1);
        }
        return player.getLocation().add(horizontal.normalize().multiply(3));
    }
}
