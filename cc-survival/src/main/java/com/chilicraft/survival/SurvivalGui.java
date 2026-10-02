package com.chilicraft.survival;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;

/** 生存压力只读状态页，不提供修改玩法状态的按钮。 */
final class SurvivalGui {
    private final SurvivalSettings settings;
    private final TemperatureService temperature;
    private final WeightService weight;

    SurvivalGui(SurvivalSettings settings, TemperatureService temperature, WeightService weight) {
        this.settings = settings;
        this.temperature = temperature;
        this.weight = weight;
    }

    void open(Player player) {
        GuiHolder holder = new GuiHolder(27,
                text("gui-title-status", "<gold>生存状态"));
        holder.set(4, item(Material.PLAYER_HEAD, text("gui-status-name", "<gold>当前生存状态"), List.of(
                line("gui-status-hunger", "<gray>饥饿：<value></gray>", "<value>", player.getFoodLevel()),
                line("gui-status-temperature", "<gray>体温：<value></gray>", "<value>", format(temperature.body(player))),
                line("gui-status-weight", "<gray>负重：<value>%</gray>", "<value>", weightPercent(player)),
                line("gui-status-durability", "<gray>耐久损耗：<value></gray>", "<value>", onOff(settings.durabilityEnabled)),
                line("gui-status-street", "<gray>方街减压世界：<value></gray>", "<value>", onOff(settings.inStreet(player.getWorld().getName()))))));
        holder.set(22, item(Material.BARRIER, text("gui-close", "<red>关闭"), List.of()),
                (p, type) -> p.closeInventory());
        holder.open(player);
    }

    private int weightPercent(Player player) {
        double limit = settings.weightLimit;
        double ratio = limit > 0.0 ? weight.ratio(player.getUniqueId(), limit) : 0.0;
        return (int) Math.floor(ratio * 100.0);
    }

    private Component text(String key, String def) {
        return MiniMessage.miniMessage().deserialize(settings.message(key).isEmpty() ? def : settings.message(key));
    }

    private Component line(String key, String def, String placeholder, Object value) {
        return text(key, def).replaceText(builder -> builder.matchLiteral(placeholder)
                .replacement(Component.text(String.valueOf(value))));
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String format(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String onOff(boolean value) {
        return value ? "开启" : "关闭";
    }
}
