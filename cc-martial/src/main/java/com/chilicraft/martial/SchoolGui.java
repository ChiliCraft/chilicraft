package com.chilicraft.martial;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 流派面板：槽 4 状态头 / 11-15 五流派 / 22 返回。
 *
 * <p>点击未拜师流派 → joinSchool（服务层自带反馈）；已拜师点击他派 →
 * school-switch-denied 反馈（同样由服务层发出，此处仅刷新）。
 * 流派名为数据驱动文本，用 raw() 纯文本防标签注入。</p>
 */
final class SchoolGui {

    private final MartialSettings settings;
    private final SkillService skills;
    private final MartialGui main;

    SchoolGui(MartialSettings settings, SkillService skills, MartialGui main) {
        this.settings = settings;
        this.skills = skills;
        this.main = main;
    }

    void open(Player player) {
        GuiHolder holder = new GuiHolder(27,
                MartialGuiItems.text(settings, "gui-title-school", "<dark_gray>流派"));

        PlayerSkillData data = skills.data(player.getUniqueId());
        String currentId = data != null ? data.school : null;
        holder.set(4, statusHead(player.getUniqueId(), currentId));

        // 流派槽位（11-15）：最多展示 5 个流派
        List<SkillContent.SchoolDef> schools = new ArrayList<>(skills.content().schools.values());
        for (int i = 0; i < 5 && i < schools.size(); i++) {
            SkillContent.SchoolDef def = schools.get(i);
            boolean current = def.id.equals(currentId);
            List<Component> lore = new ArrayList<>();
            lore.addAll(skillPreview(def));
            if (current) {
                lore.add(MartialGuiItems.text(settings, "gui-school-lore-current",
                        "<green>◆当前流派"));
            } else if (currentId != null) {
                lore.add(MartialGuiItems.text(settings, "gui-school-lore-denied",
                        "<gray>已拜师，转派需管理员重置"));
            } else {
                lore.add(MartialGuiItems.text(settings, "gui-school-lore-join",
                        "<yellow>点击拜入此流派"));
            }
            final String schoolId = def.id;
            holder.set(11 + i, MartialGuiItems.button(def.vanillaWeapon,
                            MartialGuiItems.raw(def.display), lore),
                    (p, type) -> {
                        if (!current) {
                            skills.joinSchool(p, schoolId);
                        }
                        main.openSchool(p); // 点击后重建刷新（拜师/拒绝反馈见聊天栏）
                    });
        }

        holder.set(22, MartialGuiItems.button(Material.ARROW,
                        MartialGuiItems.text(settings, "gui-back", "<gray>返回"), List.of()),
                (p, type) -> main.openMain(p));
        holder.open(player);
    }

    private ItemStack statusHead(UUID playerId, String currentId) {
        SkillContent.SchoolDef def = currentId != null
                ? skills.content().schools.get(currentId) : null;
        List<Component> lore = new ArrayList<>();
        if (def != null) {
            lore.add(MartialGuiItems.text(settings, "gui-school-status-current",
                    "<gray>当前流派：<white><school></gray>")
                    .replaceText(b -> b.matchLiteral("<school>").replacement(
                            MartialGuiItems.raw(def.display))));
        } else {
            lore.add(MartialGuiItems.text(settings, "gui-school-status-none",
                    "<gray>尚未拜师"));
        }
        return MartialGuiItems.head(playerId,
                MartialGuiItems.text(settings, "gui-school-status-name", "<aqua>流派"),
                lore);
    }

    /** 流派技能预览（最多 6 行）：显示名 + 境界要求 */
    private List<Component> skillPreview(SkillContent.SchoolDef def) {
        List<Component> lore = new ArrayList<>();
        Map<String, SkillContent.SkillDef> group =
                skills.content().skillsBySchool.getOrDefault(def.id, Map.of());
        int shown = 0;
        for (SkillContent.SkillDef skill : group.values()) {
            if (shown >= 6) {
                break;
            }
            lore.add(MartialGuiItems.text(settings, "gui-school-lore-skills",
                    "<dark_gray>· <skills> <gray>需<realm></gray>")
                    .replaceText(b -> b.matchLiteral("<skills>").replacement(
                            MartialGuiItems.raw(skill.display)))
                    .replaceText(b -> b.matchLiteral("<realm>").replacement(
                            MartialGuiItems.raw(settings.realmDisplay[skill.requiredRealm]))));
            shown++;
        }
        return lore;
    }
}
