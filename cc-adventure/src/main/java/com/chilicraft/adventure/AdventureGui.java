package com.chilicraft.adventure;

import com.chilicraft.core.gui.GuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * /adventure 主面板与四大子面板门面。
 *
 * <p>复用 cc-core 的 GuiHolder/GuiListener（instanceof 识别，本模块零监听器）；
 * 快照式菜单——每次打开全新构建，动作完成后重建刷新（MenuGui 同范式）。</p>
 */
final class AdventureGui {

    private final JavaPlugin plugin;
    private final AdventureSettings settings;
    private final PartyGui partyGui;
    private final DungeonGui dungeonGui;
    private final ExpeditionGui expeditionGui;
    private final BossGui bossGui;

    AdventureGui(JavaPlugin plugin, AdventureSettings settings,
                 PartyService parties, DungeonService dungeons,
                 ExpeditionService expeditions, BossService bosses) {
        this.plugin = plugin;
        this.settings = settings;
        this.partyGui = new PartyGui(plugin, settings, parties, this);
        this.dungeonGui = new DungeonGui(plugin, settings, dungeons, this);
        this.expeditionGui = new ExpeditionGui(plugin, settings, expeditions, this);
        this.bossGui = new BossGui(settings, bosses, this);
    }

    /** 打开主面板（主线程）：槽 10 队伍 / 12 地城 / 14 远征 / 16 Boss / 22 关闭 */
    void openMain(Player player) {
        GuiHolder holder = new GuiHolder(27,
                GuiItems.text(settings, "gui.title-main", "<dark_gray>冒险菜单"));

        holder.set(10, GuiItems.button(Material.PLAYER_HEAD,
                        GuiItems.text(settings, "gui.main-party-name", "<gold>组队"),
                        lore("gui.main-party-lore", "<gray>创建/管理队伍，查看邀请")),
                (p, type) -> openParty(p));
        holder.set(12, GuiItems.button(Material.IRON_SWORD,
                        GuiItems.text(settings, "gui.main-dungeon-name", "<gold>地城"),
                        lore("gui.main-dungeon-lore", "<gray>选择地城，组队或单人挑战")),
                (p, type) -> openDungeon(p));
        holder.set(14, GuiItems.button(Material.COMPASS,
                        GuiItems.text(settings, "gui.main-expedition-name", "<gold>远征"),
                        lore("gui.main-expedition-lore", "<gray>随机房间逐层深入，祝福与诅咒同行")),
                (p, type) -> openExpedition(p));
        holder.set(16, GuiItems.button(Material.WITHER_SKELETON_SKULL,
                        GuiItems.text(settings, "gui.main-boss-name", "<gold>世界 Boss"),
                        lore("gui.main-boss-lore", "<gray>图鉴、触发条件与你的进度")),
                (p, type) -> openBoss(p));

        holder.set(22, GuiItems.button(Material.BARRIER,
                        GuiItems.text(settings, "gui.close", "<red>关闭"), List.of()),
                (p, type) -> p.closeInventory());
        holder.open(player);
    }

    void openParty(Player player) {
        partyGui.open(player);
    }

    void openDungeon(Player player) {
        dungeonGui.open(player);
    }

    void openExpedition(Player player) {
        expeditionGui.open(player);
    }

    void openBoss(Player player) {
        bossGui.open(player);
    }

    private List<Component> lore(String key, String def) {
        return List.of(GuiItems.text(settings, key, def));
    }
}
