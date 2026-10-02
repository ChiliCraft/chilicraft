package com.chilicraft.martial;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 技能面板：槽 4 状态头 / 19-23 装备槽 / 28-33 流派技能 / 49 返回。
 *
 * <p>状态判定与命令层同口径（选中 &gt; 已装备 &gt; 已学 &gt; 可学 &gt; 未达）；
 * 点击动作依次尝试 learn / equip / select / unequip（服务层自带聊天反馈，
 * 操作后重建刷新）。被动已学仅提示；未达境界展示锁定行。</p>
 */
final class SkillGui {

    /** 装备槽展示位（槽 0-4 → 槽位 19-23） */
    private static final int[] SLOT_SLOTS = {19, 20, 21, 22, 23};
    /** 技能条目展示位（每流派 6 技能 → 槽位 28-33） */
    private static final int[] ENTRY_SLOTS = {28, 29, 30, 31, 32, 33};
    /** 技能状态图标：选中 / 已装备 / 已学 / 可学 / 未达 */
    private static final Material[] STATE_MATERIALS = {
            Material.LIME_DYE, Material.GREEN_DYE, Material.YELLOW_DYE,
            Material.CYAN_DYE, Material.GRAY_DYE};
    /** 状态键（与命令层 skill-state-* 一致）与 GUI 行动键，一一对应 */
    private static final String[] STATE_KEYS = {
            "skill-state-selected", "skill-state-equipped", "skill-state-learned",
            "skill-state-learnable", "skill-state-locked"};
    private static final String[] ACTION_KEYS = {
            "gui-skill-action-unequip", "gui-skill-action-select", "gui-skill-action-passive",
            "gui-skill-action-learn", "gui-skill-action-locked"};

    private final MartialSettings settings;
    private final RealmService realms;
    private final SkillService skills;
    private final MartialGui main;

    SkillGui(MartialSettings settings, RealmService realms, SkillService skills, MartialGui main) {
        this.settings = settings;
        this.realms = realms;
        this.skills = skills;
        this.main = main;
    }

    void open(Player player) {
        GuiHolder holder = new GuiHolder(54,
                MartialGuiItems.text(settings, "gui-title-skills", "<dark_gray>技能"));

        PlayerSkillData data = skills.data(player.getUniqueId());
        if (data == null || data.school == null) {
            // 未拜师：状态头 + 返回（拜师入口在流派面板）
            holder.set(4, MartialGuiItems.button(Material.BARRIER,
                    MartialGuiItems.text(settings, "gui-skills-status-need-join",
                            "<red>尚未拜入流派"), List.of()));
            holder.set(49, MartialGuiItems.button(Material.ARROW,
                            MartialGuiItems.text(settings, "gui-back", "<gray>返回"), List.of()),
                    (p, type) -> main.openMain(p));
            holder.open(player);
            return;
        }

        holder.set(4, statusHead(player, data));

        Map<String, SkillContent.SkillDef> group =
                skills.content().skillsBySchool.getOrDefault(data.school, Map.of());

        // 装备槽区（仅前 slotLimit 格生效，与 SkillService 口径一致）
        int slotLimit = settings.skillSlots[realms.realm(player.getUniqueId())];
        for (int i = 0; i < 5; i++) {
            String key = data.slots[i];
            if (key != null) {
                SkillContent.SkillDef def = skills.content().skills.get(key);
                if (def != null) {
                    setEquippedSlot(holder, SLOT_SLOTS[i], def);
                    continue;
                }
            }
            if (i < slotLimit) {
                holder.set(SLOT_SLOTS[i], MartialGuiItems.button(Material.GRAY_STAINED_GLASS_PANE,
                        MartialGuiItems.text(settings, "gui-skills-slot-empty",
                                "<gray>空槽位 <used>/<slots>")
                                .replaceText(b -> b.matchLiteral("<used>").replacement(
                                        String.valueOf(usedSlots(data, slotLimit))))
                                .replaceText(b -> b.matchLiteral("<slots>").replacement(
                                        String.valueOf(slotLimit))),
                        List.of()));
            } else {
                holder.set(SLOT_SLOTS[i], MartialGuiItems.button(Material.RED_STAINED_GLASS_PANE,
                        MartialGuiItems.text(settings, "gui-skills-slot-locked",
                                "<dark_gray>槽位需更高境界"), List.of()));
            }
        }

        // 技能条目区（每流派固定 6 技能，超出截断防御性处理）
        int shown = 0;
        for (SkillContent.SkillDef def : group.values()) {
            if (shown >= ENTRY_SLOTS.length) {
                break;
            }
            setEntry(holder, ENTRY_SLOTS[shown], player, data, def);
            shown++;
        }

        holder.set(49, MartialGuiItems.button(Material.ARROW,
                        MartialGuiItems.text(settings, "gui-back", "<gray>返回"), List.of()),
                (p, type) -> main.openMain(p));
        holder.open(player);
    }

    /** 技能条目：状态判定（选中 > 已装备 > 已学 > 可学 > 未达）+ 点击动作链 */
    private void setEntry(GuiHolder holder, int slot, Player player,
                          PlayerSkillData data, SkillContent.SkillDef def) {
        SkillProgress progress = data.skills.get(def.key);
        int realm = realms.realm(player.getUniqueId());
        int state = stateOf(data, def, progress, realm);
        List<Component> lore = new ArrayList<>();
        // 状态行：复用命令层 skill-state-* 键
        lore.add(Texts.parse(settings.messageOr(STATE_KEYS[state], "◇")));
        lore.add(def.isPassive()
                ? MartialGuiItems.text(settings, "gui-skill-lore-type-passive",
                        "<gray>被动 · 习得后自动生效")
                : MartialGuiItems.text(settings, "gui-skill-lore-type-active",
                        "<gray>主动 · 冷却 <cooldown> 秒")
                        .replaceText(b -> b.matchLiteral("<cooldown>").replacement(
                                String.valueOf(def.cooldown))));
        if (progress != null) {
            lore.add(MartialGuiItems.text(settings, "gui-skill-lore-level",
                    "<gray>Lv.<level> · 经验 <xp></gray>")
                    .replaceText(b -> b.matchLiteral("<level>").replacement(
                            String.valueOf(progress.level)))
                    .replaceText(b -> b.matchLiteral("<xp>").replacement(
                            String.valueOf(progress.xp))));
        }
        if (!def.isPassive() && progress == null && realm < def.requiredRealm) {
            lore.add(MartialGuiItems.text(settings, "gui-skill-lore-realm",
                    "<gray>需境界：<yellow><realm></yellow>")
                    .replaceText(b -> b.matchLiteral("<realm>").replacement(
                            MartialGuiItems.raw(settings.realmDisplay[def.requiredRealm]))));
        }
        lore.add(MartialGuiItems.text(settings, ACTION_KEYS[state], ""));
        final int stateFinal = state;
        holder.set(slot, MartialGuiItems.button(STATE_MATERIALS[state],
                        MartialGuiItems.raw(def.display), lore),
                (p, type) -> {
                    clickSkill(p, data, def, stateFinal);
                    main.openSkills(p); // 操作后重建刷新
                });
    }

    /** 装备槽物品：点击即卸下该技能 */
    private void setEquippedSlot(GuiHolder holder, int slot, SkillContent.SkillDef def) {
        List<Component> lore = new ArrayList<>();
        lore.add(MartialGuiItems.text(settings, "gui-skills-slot-filled-lore",
                "<gray>点击卸下"));
        holder.set(slot, MartialGuiItems.button(
                        def.isPassive() ? Material.PAPER : Material.IRON_SWORD,
                        MartialGuiItems.raw(def.display), lore),
                (p, type) -> {
                    skills.unequip(p, def.key);
                    main.openSkills(p);
                });
    }

    /** 状态判定：选中 > 已装备 > 已学 > 可学 > 未达（与 MartialCommand.sendSkillList 同口径） */
    private int stateOf(PlayerSkillData data, SkillContent.SkillDef def,
                        SkillProgress progress, int realm) {
        if (def.key.equals(data.selected)) {
            return 0;
        }
        for (String slot : data.slots) {
            if (def.key.equals(slot)) {
                return 1;
            }
        }
        if (progress != null) {
            return 2;
        }
        return realm >= def.requiredRealm ? 3 : 4;
    }

    /** 点击动作：按状态依次尝试（服务层自带反馈，非法操作静默刷新） */
    private void clickSkill(Player player, PlayerSkillData data,
                            SkillContent.SkillDef def, int state) {
        switch (state) {
            case 3 -> skills.learn(player, def.key);           // 可学 → 领悟
            case 2 -> {                                         // 已学 → 被动提示 / 装备
                if (def.isPassive()) {
                    return;
                }
                skills.equip(player, def.key);
            }
            case 1 -> {                                         // 已装备 → 选定（被动不会到这）
                if (!def.isPassive()) {
                    skills.select(player, def.key);
                }
            }
            case 0 -> skills.unequip(player, def.key);          // 选中 → 卸下
            default -> { /* 未达境界：无操作 */ }
        }
    }

    private ItemStack statusHead(Player player, PlayerSkillData data) {
        int slotLimit = settings.skillSlots[realms.realm(player.getUniqueId())];
        SkillContent.SchoolDef school = skills.content().schools.get(data.school);
        String schoolDisplay = school != null ? school.display : data.school;
        String selectedDisplay = "-";
        if (data.selected != null) {
            SkillContent.SkillDef def = skills.content().skills.get(data.selected);
            selectedDisplay = def != null ? def.display : data.selected;
        }
        final Component selectedRaw = MartialGuiItems.raw(selectedDisplay);
        List<Component> lore = new ArrayList<>();
        lore.add(MartialGuiItems.text(settings, "gui-skills-status-lore",
                "<gray>流派：<school> · 槽位 <used>/<slots></gray>")
                .replaceText(b -> b.matchLiteral("<school>").replacement(
                        MartialGuiItems.raw(schoolDisplay)))
                .replaceText(b -> b.matchLiteral("<used>").replacement(
                        String.valueOf(usedSlots(data, slotLimit))))
                .replaceText(b -> b.matchLiteral("<slots>").replacement(
                        String.valueOf(slotLimit))));
        lore.add(MartialGuiItems.text(settings, "gui-skills-status-selected",
                "<gray>当前施展：<selected></gray>")
                .replaceText(b -> b.matchLiteral("<selected>").replacement(selectedRaw)));
        return MartialGuiItems.head(player.getUniqueId(),
                MartialGuiItems.text(settings, "gui-skills-status-name", "<aqua>技能"),
                lore);
    }

    private static int usedSlots(PlayerSkillData data, int slotLimit) {
        int used = 0;
        for (int i = 0; i < slotLimit; i++) {
            if (data.slots[i] != null) {
                used++;
            }
        }
        return used;
    }
}
