package com.chilicraft.soul;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 遗物图鉴面板（54 格）：12 遗物定义逐件展示，状态取自 RelicService 在场缓存。
 *
 * <p>纯展示面板（无编辑动作）；图标名与描述为受信定义模板（config 调服维护，
 * 与 RelicService.applyIdentity 同口径直接 parse）。消息键平铺 kebab-case
 * （SoulSettings 消息为浅键加载，禁止嵌套小节）。</p>
 */
final class RelicGui {

    /** 12 遗物槽位（第 2-3 行居中排布） */
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23};

    private final SoulSettings settings;
    private final RelicService relics;
    private final SoulGui main;

    RelicGui(SoulSettings settings, RelicService relics, SoulGui main) {
        this.settings = settings;
        this.relics = relics;
        this.main = main;
    }

    /** 打开图鉴（主线程）：槽 4 说明 / 12 遗物 / 49 返回 */
    void open(Player player) {
        Map<String, RelicService.CachedRelic> snapshot = relics.snapshot();
        GuiHolder holder = new GuiHolder(54,
                SoulGuiItems.text(settings, "gui-title-relics", "<dark_gray>遗物图鉴"));

        holder.set(4, SoulGuiItems.button(Material.PAPER,
                SoulGuiItems.text(settings, "gui-relics-info-name", "<gold>图鉴说明"),
                List.of(SoulGuiItems.text(settings, "gui-relics-info-lore",
                        "<gray>在场 <count> / 共 <total> 件</gray>")
                        .replaceText(b -> b.matchLiteral("<count>").replacement(
                                String.valueOf(snapshot.size())))
                        .replaceText(b -> b.matchLiteral("<total>").replacement(
                                String.valueOf(settings.relicDefinitions.size()))))));

        // 定义按 ID 排序展示（relicDefinitions 经 Map.copyOf 丢序，保证图鉴顺序稳定）
        List<String> ids = new ArrayList<>(settings.relicDefinitions.keySet());
        ids.sort(String.CASE_INSENSITIVE_ORDER);
        int slotIndex = 0;
        for (String id : ids) {
            if (slotIndex >= SLOTS.length) {
                break;
            }
            holder.set(SLOTS[slotIndex++], relicIcon(settings.relicDefinitions.get(id), snapshot.get(id)));
        }

        holder.set(49, SoulGuiItems.button(Material.ARROW,
                SoulGuiItems.text(settings, "gui-back", "<yellow>返回"), List.of()),
                (p, type) -> main.openMain(p));
        holder.open(player);
    }

    /** 单件遗物图标：定义模板直接 parse + 效果行 + 状态行（在场缓存为准） */
    private ItemStack relicIcon(RelicDefinition def, RelicService.CachedRelic cached) {
        List<Component> lore = new ArrayList<>();
        if (!def.lore().isEmpty()) {
            lore.add(Texts.parse(def.lore()));
        }
        final Component effectRaw = SoulGuiItems.raw(def.effect().id());
        lore.add(SoulGuiItems.text(settings, "gui-relics-effect",
                "<gray>效果：<effect> · 数值 <value> · 灵韵上限 <durability></gray>")
                .replaceText(b -> b.matchLiteral("<effect>").replacement(effectRaw))
                .replaceText(b -> b.matchLiteral("<value>").replacement(trimNum(def.value())))
                .replaceText(b -> b.matchLiteral("<durability>").replacement(
                        String.valueOf(def.durability()))));
        if (cached == null) {
            lore.add(SoulGuiItems.text(settings, "gui-relics-state-absent",
                    "<dark_gray>未在场</dark_gray>"));
        } else if (cached.state() == RelicService.RelicState.ACTIVE) {
            lore.add(SoulGuiItems.text(settings, "gui-relics-state-active",
                    "<aqua>在场 · 灵韵 <durability>/<max></aqua>")
                    .replaceText(b -> b.matchLiteral("<durability>").replacement(
                            String.valueOf(Math.max(0, cached.durability()))))
                    .replaceText(b -> b.matchLiteral("<max>").replacement(
                            String.valueOf(def.durability()))));
        } else {
            // EXHAUSTED（BURIED 已移出缓存，不会出现在快照里）
            lore.add(SoulGuiItems.text(settings, "gui-relics-state-exhausted",
                    "<dark_red>在场 · 灵韵耗尽，待葬礼送别</dark_red>"));
        }
        return SoulGuiItems.button(def.material(), Texts.parse(def.display()), lore);
    }

    private static String trimNum(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
