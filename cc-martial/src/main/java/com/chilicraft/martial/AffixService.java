package com.chilicraft.martial;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 词缀服务：PDC 标记读写 / 改名附加 / 掉落 roll / 装备扫描 / PASSIVE 心跳。
 *
 * <p>词缀标记用 PDC STRING（逗号分隔 id，{@code martial_affix} 键），
 * 不手拼 NBT；显示用物品名后追加 " [词缀名]"（按品阶着色，1 绿 / 2 蓝 / 3 金）。</p>
 *
 * <p>线程契约：全部方法仅主线程调用。</p>
 */
final class AffixService {

    private final JavaPlugin plugin;
    private final MartialSettings settings;
    private final Logger logger;
    private final AffixContent content;

    /** 词缀标记 PDC 键（STRING：逗号分隔词缀 id） */
    private final NamespacedKey affixKey;

    private BukkitTask heartbeatTask;
    /** PASSIVE 心跳计数（absorption every 参数用） */
    private int passiveTick;

    AffixService(JavaPlugin plugin, MartialSettings settings, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.logger = logger;
        this.content = new AffixContent(plugin, logger);
        this.affixKey = new NamespacedKey(plugin, "martial_affix");
    }

    // ================= 内容 =================

    /** 重建内容注册表（onEnable 与 core.reload 调用） */
    void reloadContent() {
        content.load();
    }

    AffixContent content() {
        return content;
    }

    // ================= 词缀读写 =================

    /** 物品当前词缀（按 PDC 顺序；标记失效 id 自动忽略） */
    List<AffixContent.AffixDef> affixesOf(ItemStack item) {
        String raw = affixMark(item);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<AffixContent.AffixDef> result = new ArrayList<>();
        for (String id : raw.split(",")) {
            AffixContent.AffixDef def = content.affixes.get(id);
            if (def != null) {
                result.add(def);
            }
        }
        return result;
    }

    /**
     * 为物品施加词缀（重名 / 超上限 / 非武器工具拒绝）。
     *
     * @return true = 成功（PDC 与改名已更新）
     */
    boolean applyAffix(ItemStack item, AffixContent.AffixDef def) {
        if (def == null || !isWeaponOrTool(item)) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        String raw = meta.getPersistentDataContainer().get(affixKey, PersistentDataType.STRING);
        List<String> ids = raw == null || raw.isEmpty()
                ? new ArrayList<>()
                : new ArrayList<>(List.of(raw.split(",")));
        if (ids.contains(def.id)) {
            return false; // 重名拒绝
        }
        if (ids.size() >= Math.max(1, settings.affixMaxPerItem)) {
            return false; // 超上限拒绝
        }
        ids.add(def.id);
        meta.getPersistentDataContainer().set(affixKey, PersistentDataType.STRING, String.join(",", ids));

        // 改名附加：" [display]"（tier 着色；后缀取消斜体，保留原名样式）
        Component name = meta.hasDisplayName()
                ? meta.displayName()
                : Component.translatable(item.getType().translationKey());
        Component suffix = Component.text(" [" + def.display + "]", tierColor(def.tier))
                .decoration(TextDecoration.ITALIC, false);
        meta.displayName(name.append(suffix));
        item.setItemMeta(meta);
        return true;
    }

    /** 随机一条词缀（注册表为空返回 null） */
    AffixContent.AffixDef randomAffix() {
        int size = content.affixes.size();
        if (size == 0) {
            return null;
        }
        return new ArrayList<>(content.affixes.values())
                .get(ThreadLocalRandom.current().nextInt(size));
    }

    /**
     * 掉落 roll：对掉落物中的武器工具逐个 roll（单物品单次，
     * 已有词缀标记的跳过），命中即随机施加并向 killer 提示。
     */
    void rollDropLoot(Player killer, List<ItemStack> drops) {
        double chance = settings.affixDropRollChance;
        if (chance <= 0 || content.affixes.isEmpty()) {
            return;
        }
        for (ItemStack item : drops) {
            if (item == null || affixMark(item) != null || !isWeaponOrTool(item)) {
                continue;
            }
            if (ThreadLocalRandom.current().nextDouble(100) < chance) {
                AffixContent.AffixDef def = randomAffix();
                if (def != null && applyAffix(item, def)) {
                    send(killer, "affix-applied", Map.of("affix", def.display));
                }
            }
        }
    }

    /** 玩家身上生效指定钩子的词缀（ATTACK/KILL 查主手，其余查主手 + 四盔甲） */
    List<AffixContent.AffixDef> equippedAffixes(Player player, AffixHook hook) {
        List<AffixContent.AffixDef> result = new ArrayList<>();
        for (ItemStack item : scannedItems(player, hook)) {
            for (AffixContent.AffixDef def : affixesOf(item)) {
                if (def.hook == hook) {
                    result.add(def);
                }
            }
        }
        return result;
    }

    // ================= 生命周期 =================

    void startTasks() {
        heartbeatTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::heartbeat, 40L, 40L);
    }

    void stopTasks() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel();
            heartbeatTask = null;
        }
    }

    // ================= 内部 =================

    /** PASSIVE 心跳：常驻药水刷新 + 回血 + 周期吸收（主线程，仅在线玩家） */
    private void heartbeat() {
        passiveTick++;
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            for (AffixContent.AffixDef def : equippedAffixes(player, AffixHook.PASSIVE)) {
                String potionName = def.tag("potion", "");
                if (!potionName.isEmpty()) {
                    PotionEffectType type = Registry.EFFECT.get(NamespacedKey.minecraft(potionName.toLowerCase(Locale.ROOT)));
                    if (type != null) {
                        // 时长覆盖下次心跳（80t > 40t），无粒子闪烁
                        player.addPotionEffect(new PotionEffect(
                                type, 80, (int) def.param("amplifier", 0), true, false, true));
                    }
                }
                double heal = def.param("heal", 0);
                if (heal > 0) {
                    healPlayer(player, heal);
                }
                double absorbEvery = def.param("every", 0);
                double absorb = def.param("absorption", 0);
                if (absorb > 0 && absorbEvery > 0 && passiveTick % (int) absorbEvery == 0) {
                    // 黄心封顶 4 颗，避免无限叠加
                    player.setAbsorptionAmount(Math.min(4.0, player.getAbsorptionAmount() + absorb));
                }
            }
        }
    }

    private void healPlayer(Player player, double amount) {
        if (amount <= 0 || player.isDead()) {
            return;
        }
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = attr != null ? attr.getValue() : 20.0;
        if (player.getHealth() > 0 && player.getHealth() < max) {
            player.setHealth(Math.min(max, player.getHealth() + amount));
        }
    }

    /** 按 hook 决定扫描槽位（约定见 {@link AffixHook}） */
    private List<ItemStack> scannedItems(Player player, AffixHook hook) {
        if (hook == AffixHook.ATTACK || hook == AffixHook.KILL) {
            return List.of(player.getInventory().getItemInMainHand());
        }
        return List.of(player.getInventory().getItemInMainHand(),
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots());
    }

    /** 物品 PDC 词缀标记原文（无标记返回 null） */
    private String affixMark(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(affixKey, PersistentDataType.STRING);
    }

    /** 武器 / 工具判定（材质名子串匹配，含下界合金与附魔剑等全部变体） */
    private static boolean isWeaponOrTool(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.contains("SWORD") || name.contains("AXE") || name.contains("PICKAXE")
                || name.contains("SHOVEL") || name.contains("HOE") || name.contains("BOW")
                || name.contains("TRIDENT") || name.contains("SHEARS");
    }

    /** 品阶颜色：1 绿 / 2 蓝 / 3 金 */
    private static NamedTextColor tierColor(int tier) {
        return switch (tier) {
            case 3 -> NamedTextColor.GOLD;
            case 2 -> NamedTextColor.AQUA;
            default -> NamedTextColor.GREEN;
        };
    }

    private void send(Player player, String key, Map<String, String> placeholders) {
        String template = settings.message(key);
        if (template.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            template = template.replace("<" + entry.getKey() + ">", entry.getValue());
        }
        player.sendMessage(Texts.parse(template));
    }
}
