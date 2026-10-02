package com.chilicraft.demon;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * /demon 参数总览子面板：夜间判定 / 刷怪 / 群组概率 / 饿意追踪 / 母体召唤 /
 * 白天清除 / 掉落 / MythicMobs 各组配置只读展示。
 *
 * <p>全部条目纯展示（仅返回按钮有动作），数值实时读 settings 缓存——所见即当前
 * 生效值，core.reload 后重开面板即见新值。lore 用 DemonGuiItems.line 组装，
 * 数据值（材质名 / MM ID）纯文本防标签注入；MythicMobs 路径为配置视角展示，
 * 不探测 MM 插件是否在场。</p>
 */
final class DemonConfigGui {

    private final DemonSettings settings;
    private final DemonGui main;

    DemonConfigGui(DemonSettings settings, DemonGui main) {
        this.settings = settings;
        this.main = main;
    }

    /** 打开参数总览（主线程）：槽 4 说明 / 10-16 配置组 / 20 MythicMobs / 22 返回 */
    void open(Player player) {
        GuiHolder holder = new GuiHolder(27,
                DemonGuiItems.text(settings, "gui-title-config", "<dark_red>饿魔参数"));

        holder.set(4, DemonGuiItems.button(Material.PAPER,
                DemonGuiItems.text(settings, "gui-config-info-name", "<gold>参数总览"),
                List.of(DemonGuiItems.text(settings, "gui-config-info-lore",
                        "<gray>以下为当前生效配置；/demon reload 后重开面板即见新值</gray>"))));

        holder.set(10, nightButton(player));
        holder.set(11, spawnButton());
        holder.set(12, groupButton());
        holder.set(13, hungerButton());
        holder.set(14, motherButton());
        holder.set(15, daytimeButton());
        holder.set(16, dropsButton());
        holder.set(20, mythicButton());
        holder.set(22, DemonGuiItems.button(Material.ARROW,
                        DemonGuiItems.text(settings, "gui-back", "<yellow>返回"), List.of()),
                (p, t) -> main.openMain(p));
        holder.open(player);
    }

    /** 夜间判定：原版时间刻区间 + 当前世界昼夜（与周期任务同口径） */
    private ItemStack nightButton(Player player) {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-config-night-lore-range",
                "<gray>夜间区间：<start> ~ <end>（时间刻）</gray>",
                "<start>", String.valueOf(settings.nightStart),
                "<end>", String.valueOf(settings.nightEnd)));
        lore.add(DemonGuiItems.line(settings, "gui-config-night-lore-now",
                "<gray>当前世界：<time></gray>", "<time>", main.timeComponent(player)));
        return DemonGuiItems.button(Material.CLOCK,
                DemonGuiItems.text(settings, "gui-config-night-name", "<gold>夜间判定"), lore);
    }

    /** 刷怪参数：间隔 / 搜索半径 / 双层上限 */
    private ItemStack spawnButton() {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-config-spawn-lore-interval",
                "<gray>刷新间隔：<interval> 秒</gray>",
                "<interval>", String.valueOf(settings.spawnIntervalSec)));
        lore.add(DemonGuiItems.line(settings, "gui-config-spawn-lore-radius",
                "<gray>搜索半径：<min> ~ <max> 格</gray>",
                "<min>", String.valueOf(settings.radiusMin),
                "<max>", String.valueOf(settings.radiusMax)));
        lore.add(DemonGuiItems.line(settings, "gui-config-spawn-lore-limit",
                "<gray>上限：单玩家 <player> / 全服 <global></gray>",
                "<player>", String.valueOf(settings.maxPerPlayer),
                "<global>", String.valueOf(settings.globalLimit)));
        return DemonGuiItems.button(Material.SPAWNER,
                DemonGuiItems.text(settings, "gui-config-spawn-name", "<gold>刷怪参数"), lore);
    }

    /** 群组与概率：小饿魔群规模 / 猎手与母体出现概率（百分数） */
    private ItemStack groupButton() {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-config-group-lore-imp",
                "<gray>小饿魔群：<min> ~ <max> 只</gray>",
                "<min>", String.valueOf(settings.impGroupMin),
                "<max>", String.valueOf(settings.impGroupMax)));
        lore.add(DemonGuiItems.line(settings, "gui-config-group-lore-chance",
                "<gray>概率：猎手 <hunter>% / 母体 <mother>%</gray>",
                "<hunter>", DemonGuiItems.num(settings.hunterChance * 100),
                "<mother>", DemonGuiItems.num(settings.motherChance * 100)));
        return DemonGuiItems.button(Material.ROTTEN_FLESH,
                DemonGuiItems.text(settings, "gui-config-group-name", "<gold>群组与概率"), lore);
    }

    /** 饿意追踪：开关 / 触发阈值与半径 / 追击加速 */
    private ItemStack hungerButton() {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-config-hunger-lore-state",
                "<gray>饿意追踪：<state></gray>",
                "<state>", main.stateComponent(settings.hungerEnabled)));
        lore.add(DemonGuiItems.line(settings, "gui-config-hunger-lore-threshold",
                "<gray>触发：饥饿 <threshold> / 半径 <radius> 格</gray>",
                "<threshold>", String.valueOf(settings.hungerThreshold),
                "<radius>", String.valueOf(settings.hungerRadius)));
        lore.add(DemonGuiItems.line(settings, "gui-config-hunger-lore-boost",
                "<gray>追击加速：+<boost>%</gray>",
                "<boost>", DemonGuiItems.num(settings.hungerSpeedBoost * 100)));
        return DemonGuiItems.button(Material.BREAD,
                DemonGuiItems.text(settings, "gui-config-hunger-name", "<gold>饿意追踪"), lore);
    }

    /** 母体召唤：周期与规模 / 单体上限 */
    private ItemStack motherButton() {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-config-mother-lore-interval",
                "<gray>每 <interval> 秒召唤 <min> ~ <max> 只</gray>",
                "<interval>", String.valueOf(settings.motherSummonIntervalSec),
                "<min>", String.valueOf(settings.summonMin),
                "<max>", String.valueOf(settings.summonMax)));
        lore.add(DemonGuiItems.line(settings, "gui-config-mother-lore-limit",
                "<gray>单只母体召唤上限：<limit></gray>",
                "<limit>", String.valueOf(settings.maxSummons)));
        return DemonGuiItems.button(Material.IRON_BLOCK,
                DemonGuiItems.text(settings, "gui-config-mother-name", "<gold>母体召唤"), lore);
    }

    /** 白天清除：开关状态 */
    private ItemStack daytimeButton() {
        return DemonGuiItems.button(Material.SUNFLOWER,
                DemonGuiItems.text(settings, "gui-config-daytime-name", "<gold>白天清除"),
                List.of(DemonGuiItems.line(settings, "gui-config-daytime-lore",
                        "<gray>天亮自动清除存活饿魔：<state></gray>",
                        "<state>", main.stateComponent(settings.daytimeClear))));
    }

    /** 掉落：爪牙掉落与灵魂掉落（材质名直接展示配置值，所见即所得） */
    private ItemStack dropsButton() {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-config-drops-lore-fang",
                "<gray>爪牙掉落：<material> × <min> ~ <max></gray>",
                "<material>", DemonGuiItems.raw(settings.fangMaterial.name()),
                "<min>", String.valueOf(settings.fangMin),
                "<max>", String.valueOf(settings.fangMax)));
        lore.add(DemonGuiItems.line(settings, "gui-config-drops-lore-soul",
                "<gray>灵魂掉落：<material>（<chance>% 概率）</gray>",
                "<material>", DemonGuiItems.raw(settings.soulMaterial.name()),
                "<chance>", DemonGuiItems.num(settings.soulChance * 100)));
        return DemonGuiItems.button(settings.fangMaterial,
                DemonGuiItems.text(settings, "gui-config-drops-name", "<gold>击杀掉落"), lore);
    }

    /** MythicMobs 接管：开关 + 三类型生成路径（配置了 MM ID 显示 ID，否则原版路径） */
    private ItemStack mythicButton() {
        List<Component> lore = new ArrayList<>();
        lore.add(DemonGuiItems.line(settings, "gui-config-mythic-lore-state",
                "<gray>MythicMobs 接管：<state></gray>",
                "<state>", main.stateComponent(settings.integrationMythicMobs)));
        lore.add(mythicPathLine(DemonType.IMP, "gui-config-mythic-lore-imp",
                "<gray>小饿魔：<path></gray>"));
        lore.add(mythicPathLine(DemonType.HUNTER, "gui-config-mythic-lore-hunter",
                "<gray>饿意猎手：<path></gray>"));
        lore.add(mythicPathLine(DemonType.MOTHER, "gui-config-mythic-lore-mother",
                "<gray>饿魔母体：<path></gray>"));
        return DemonGuiItems.button(Material.NETHER_STAR,
                DemonGuiItems.text(settings, "gui-config-mythic-name", "<gold>MythicMobs"), lore);
    }

    /** 单类型生成路径行：mythicIds 配置视角展示（GUI 不探测 MM 插件是否在场） */
    private Component mythicPathLine(DemonType type, String key, String def) {
        String mythicId = settings.mythicIds.get(type);
        Component path = mythicId != null
                ? DemonGuiItems.line(settings, "gui-mythic-path-mm",
                        "<yellow>MythicMobs：<id></yellow>", "<id>", DemonGuiItems.raw(mythicId))
                : DemonGuiItems.text(settings, "gui-mythic-path-vanilla",
                        "<gray>原版属性改造</gray>");
        return DemonGuiItems.line(settings, key, def, "<path>", path);
    }
}
