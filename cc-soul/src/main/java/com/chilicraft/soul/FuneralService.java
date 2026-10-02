package com.chilicraft.soul;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.type.Campfire;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 葬礼仪式：统一的送别与召回。
 *
 * <p>流程（/soul funeral 发起）：扣费 10,000 灵魂（中断不退）→ 可选供品
 * （主手持旧眼镜上供，站桩时长按倍率缩短）→ 站桩 60 秒
 * （离开仪式点 / 死亡 / 退出即中断）→ 结算。结算内容：</p>
 * <ul>
 *   <li>遗物葬礼：发起时手持沉眠（灵韵耗尽）遗物，完成时送别入土
 *       （状态 BURIED，全服唯一解除，同 ID 可重新出现）并发布
 *       {@code soul.relic_funeral}；中途换下则放弃送别，召回照常</li>
 *   <li>碎片召回：吸收死亡点周围碎片 × 召回率
 *       （基础 50% + 篝火 10% + 月亮井 25% + 死亡点 50%，封顶 100%）</li>
 *   <li>墓碑：享受死亡点加成时立于死亡点（仅空气方块，marker 实体仅作标记）</li>
 * </ul>
 *
 * <p>站桩判定由 SoulTickTask 权威循环每秒调用 {@link #tick()}；
 * 玩家死亡时由监听器主动 {@link #interrupt}（费用不退）。</p>
 */
final class FuneralService {

    /** 一场进行中的葬礼；relicId 非 null = 遗物葬礼（完成时送别该遗物） */
    private record FuneralSession(UUID player, String relicId, Location ritualPoint, long endAt) {
    }

    private final JavaPlugin plugin;
    private final SoulSettings settings;
    private final ChiliCraftAPI api;
    private final RelicService relics;
    private final FragmentService fragments;
    private final DeathService deaths;
    private final Logger logger;

    /** 墓碑标记键（marker 盔甲架 PDC，防同点重复立碑） */
    private final NamespacedKey tombstoneKey;

    /** 进行中的葬礼：玩家 -> 会话 */
    private final Map<UUID, FuneralSession> active = new LinkedHashMap<>();

    FuneralService(JavaPlugin plugin, SoulSettings settings, ChiliCraftAPI api,
                   RelicService relics, FragmentService fragments, DeathService deaths, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.relics = relics;
        this.fragments = fragments;
        this.deaths = deaths;
        this.logger = logger;
        this.tombstoneKey = new NamespacedKey(plugin, "tombstone");
    }

    // ---------------- 查询 ----------------

    /** 进行中葬礼的玩家集合（碎片自动拾取在葬礼期间跳过，避免双份结算） */
    Set<UUID> activePlayers() {
        return Set.copyOf(active.keySet());
    }

    // ---------------- 发起 ----------------

    /** /soul funeral：校验 → 扣费（不退）→ 建会话 */
    void start(Player player) {
        UUID uuid = player.getUniqueId();
        if (active.containsKey(uuid)) {
            player.sendMessage(Texts.parse(settings.feedback("funeral-in-progress")));
            return;
        }
        if (deaths.latestDeath(uuid) == null) {
            player.sendMessage(Texts.parse(settings.feedback("funeral-no-death")));
            return;
        }
        // 手持沉眠遗物 → 遗物葬礼（完成时送别入土）；否则纯碎片召回
        String relicId = heldExhaustedRelic(player);
        if (!api.spendSoul(uuid, settings.funeralCost, "cc-soul:funeral")) {
            player.sendMessage(Texts.parse(settings.feedback("funeral-no-soul"),
                    Placeholder.unparsed("cost", String.valueOf(settings.funeralCost))));
            return;
        }
        // 供品（巡演联动）：主手持旧眼镜上供 → 站桩时长按倍率缩短
        long duration = settings.funeralDurationSeconds;
        if (consumeOffering(player)) {
            duration = Math.round(duration * settings.offeringGlassesDurationMultiplier);
            player.sendMessage(Texts.parse(settings.feedback("funeral-offering"),
                    Placeholder.unparsed("seconds", String.valueOf(duration))));
        }
        long endAt = System.currentTimeMillis() + duration * 1000L;
        active.put(uuid, new FuneralSession(uuid, relicId, player.getLocation(), endAt));
        player.sendMessage(Texts.parse(settings.feedback("funeral-start"),
                Placeholder.unparsed("seconds", String.valueOf(duration)),
                Placeholder.unparsed("cost", String.valueOf(settings.funeralCost))));
    }

    /** 供品判定：开启且主手为供品材质时消耗一件，返回是否上供成功 */
    private boolean consumeOffering(Player player) {
        if (!settings.offeringGlassesEnabled) {
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != settings.offeringGlassesMaterial) {
            return false;
        }
        hand.setAmount(hand.getAmount() - 1);
        return true;
    }

    /** 主手是否为沉眠遗物；是则返回遗物 ID */
    private String heldExhaustedRelic(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        String id = relics.relicIdOf(hand);
        if (id == null) {
            return null;
        }
        RelicService.CachedRelic cached = relics.cached(id);
        return cached != null && cached.state() == RelicService.RelicState.EXHAUSTED ? id : null;
    }

    // ---------------- 站桩循环 ----------------

    /** 每秒调用（SoulTickTask）：离线 / 离开 / 到期结算；进度按整十秒提示 */
    void tick() {
        if (active.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, FuneralSession>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            FuneralSession session = it.next().getValue();
            Player player = Bukkit.getPlayer(session.player());
            if (player == null || !player.isOnline()) {
                it.remove();
                continue; // 离线：静默中断（费用不退）
            }
            double standRadiusSq = settings.funeralStandRadius * settings.funeralStandRadius;
            if (player.getLocation().distanceSquared(session.ritualPoint()) > standRadiusSq) {
                it.remove();
                player.sendMessage(Texts.parse(settings.feedback("funeral-interrupted")));
                continue;
            }
            long remainMs = session.endAt() - now;
            if (remainMs <= 0) {
                it.remove();
                complete(player, session);
                continue;
            }
            long remainSec = (remainMs + 999) / 1000;
            if (remainSec % 10 == 0) {
                player.sendMessage(Texts.parse(settings.feedback("funeral-progress"),
                        Placeholder.unparsed("seconds", String.valueOf(remainSec))));
            }
        }
    }

    /** 玩家死亡时主动中断葬礼（费用不退；监听器在 PlayerDeathEvent 调用） */
    void interrupt(UUID playerId) {
        FuneralSession session = active.remove(playerId);
        if (session == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && player.isOnline()) {
            player.sendMessage(Texts.parse(settings.feedback("funeral-interrupted")));
        }
    }

    // ---------------- 结算 ----------------

    private void complete(Player player, FuneralSession session) {
        UUID uuid = player.getUniqueId();
        DeathService.DeathRecord death = deaths.latestDeath(uuid);
        if (death == null) {
            // 不可能路径（发起时已校验）：防御性完成，不召回
            logger.warn("葬礼结算时死亡记录缺失（player={}），仅完成仪式", uuid);
            player.sendMessage(Texts.parse(settings.feedback("funeral-complete"),
                    Placeholder.unparsed("amount", "0"),
                    Placeholder.unparsed("pct", "0")));
            return;
        }
        Location point = session.ritualPoint();
        World world = point.getWorld();

        // 1. 地点加成：篝火 / 月亮井一次立方体扫描；死亡点按距离判定
        boolean bonfire = false;
        boolean moonwell = false;
        if (world != null) {
            int radius = (int) Math.ceil(Math.max(settings.bonfireRadius, settings.moonwellRadius));
            Block center = point.getBlock();
            outer:
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        Block block = center.getRelative(dx, dy, dz);
                        if (!bonfire && isBonfire(block)) {
                            bonfire = true;
                        }
                        if (!moonwell && isMoonwell(block)) {
                            moonwell = true;
                        }
                        if (bonfire && moonwell) {
                            break outer;
                        }
                    }
                }
            }
        }
        double bonusPct = (bonfire ? settings.bonfireBonusPct : 0)
                + (moonwell ? settings.moonwellBonusPct : 0);
        boolean deathPointBonus = false;
        World deathWorld = Bukkit.getWorld(death.world());
        Location deathPoint = deathWorld != null
                ? new Location(deathWorld, death.x(), death.y(), death.z()) : null;
        if (world != null && deathPoint != null && world.equals(deathWorld)
                && point.distanceSquared(deathPoint)
                        <= settings.deathPointRadius * settings.deathPointRadius) {
            bonusPct += settings.deathPointBonusPct;
            deathPointBonus = true;
        }
        int pct = (int) Math.min(100, settings.funeralRecoveryBasePct + bonusPct);

        // 2. 遗物葬礼：完成时仍手持该沉眠遗物 → 送别入土（全服唯一解除）
        if (session.relicId() != null) {
            String held = relics.relicIdOf(player.getInventory().getItemInMainHand());
            if (session.relicId().equals(held)) {
                player.getInventory().setItemInMainHand(null);
                relics.bury(session.relicId());
                api.publish("soul.relic_funeral", new EventData(uuid, session.relicId(), 1));
            }
            // 中途换下：放弃送别，仪式与召回照常完成
        }

        // 3. 碎片召回：吸收死亡点周围碎片总额 × 召回率
        int absorbed = deathPoint != null
                ? fragments.absorb(uuid, deathPoint, settings.funeralAbsorbRadius) : 0;
        int recovered = (int) Math.round(absorbed * pct / 100.0);
        if (recovered > 0) {
            api.addSoul(uuid, recovered, "cc-soul:funeral-recall");
        }

        // 4. 墓碑：享受死亡点加成时立于死亡点
        if (deathPointBonus && settings.tombstoneEnabled && deathWorld != null) {
            placeTombstone(deathWorld, death);
        }

        player.sendMessage(Texts.parse(settings.feedback("funeral-complete"),
                Placeholder.unparsed("amount", String.valueOf(recovered)),
                Placeholder.unparsed("pct", String.valueOf(pct))));
    }

    /** 篝火判定：材质命中；require-lit 时检查 Campfire 的 Lit 属性（灵魂篝火同型） */
    private boolean isBonfire(Block block) {
        if (!settings.bonfireMaterials.contains(block.getType())) {
            return false;
        }
        if (settings.bonfireRequireLit && block.getBlockData() instanceof Campfire campfire) {
            return campfire.isLit();
        }
        return true;
    }

    /** 月亮井判定：材质命中；require-water 时要求炼药锅含水（Levelled > 0） */
    private boolean isMoonwell(Block block) {
        Material type = block.getType();
        // 配置 CAULDRON + 要求盛水时，WATER_CAULDRON（独立材质）视为同一设施
        if (settings.moonwellRequireWater
                && settings.moonwellMaterials.contains(Material.CAULDRON)
                && type == Material.WATER_CAULDRON) {
            return true;
        }
        if (!settings.moonwellMaterials.contains(type)) {
            return false;
        }
        if (settings.moonwellRequireWater) {
            return block.getBlockData() instanceof Levelled levelled && levelled.getLevel() > 0;
        }
        return true;
    }

    /** 死亡点立墓碑：仅替换空气方块，顶部放不可见 marker 盔甲架携带墓碑名（悬停不显示，仅作标记） */
    private void placeTombstone(World world, DeathService.DeathRecord death) {
        Block block = world.getBlockAt((int) Math.floor(death.x()),
                (int) Math.floor(death.y()), (int) Math.floor(death.z()));
        if (!block.getType().isAir()) {
            return;
        }
        block.setBlockData(settings.tombstoneMaterial.createBlockData());
        Location standLoc = block.getLocation().add(0.5, 1.0, 0.5);
        // 去重：同点已有墓碑标记则跳过（重复在同一死亡点办葬礼的场景）
        for (Entity entity : world.getNearbyEntities(standLoc, 0.6, 0.6, 0.6)) {
            if (entity instanceof ArmorStand
                    && entity.getPersistentDataContainer().has(tombstoneKey, PersistentDataType.STRING)) {
                return;
            }
        }
        world.spawn(standLoc, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setMarker(true);
            stand.setGravity(false);
            stand.setPersistent(true);
            stand.setCollidable(false);
            stand.customName(Texts.parse(settings.message("tombstone-name")));
            stand.setCustomNameVisible(false);
            stand.getPersistentDataContainer().set(tombstoneKey, PersistentDataType.STRING, "1");
        });
    }

    // ---------------- 清理 ----------------

    /** 关服清理：中断全部进行中葬礼（费用不退，会话不跨重启） */
    void clearAll() {
        active.clear();
    }
}
