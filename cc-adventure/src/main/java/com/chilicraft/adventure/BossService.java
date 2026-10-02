package com.chilicraft.adventure;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/** 世界 Boss 的玩家触发、实体生命周期、技能、首杀与 BossBar 服务。 */
final class BossService {
    private static final String ACHIEVEMENT_PREFIX = "adventure.boss.";
    private static final double BAR_RANGE_SQUARED = 96.0 * 96.0;

    private final JavaPlugin plugin;
    private final AdventureSettings settings;
    private final ChiliCraftAPI api;
    private final BossContent content;
    private final Logger log;
    private final Map<UUID, ActiveBoss> activeByEntity = new HashMap<>();
    private final Map<UUID, UUID> activeByPlayer = new HashMap<>();
    private final Map<UUID, Integer> nightDeathStreaks = new HashMap<>();
    private final Set<String> pendingFirstKills = ConcurrentHashMap.newKeySet();
    private Map<String, BossDefinition> registry = Map.of();
    private long elapsedSeconds;

    BossService(JavaPlugin plugin, AdventureSettings settings, ChiliCraftAPI api,
                BossContent content, Logger log) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.content = content;
        this.log = log;
    }

    void reloadContent() {
        registry = content.reload();
    }

    void tick() {
        elapsedSeconds++;
        updateActiveBosses();
        if (!settings.bossEnabled) {
            return;
        }
        if (elapsedSeconds % settings.bossCheckSeconds == 0) {
            inspectPlayers();
        }
        if (elapsedSeconds % settings.bossSkillSeconds == 0) {
            runSkills();
        }
    }

    void handlePlayerDied(EventData data) {
        UUID playerId = data.playerId();
        if (playerId == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            nightDeathStreaks.remove(playerId);
            return;
        }
        long time = player.getWorld().getTime();
        if (time < 13_000L || time >= 23_000L) {
            nightDeathStreaks.remove(playerId);
            return;
        }
        int streak = nightDeathStreaks.merge(playerId, 1, Integer::sum);
        if (!settings.bossEnabled || activeByPlayer.containsKey(playerId)) {
            return;
        }
        for (BossDefinition definition : registry.values()) {
            if (definition.trigger == BossDefinition.TriggerType.NIGHT_DEATH_STREAK
                    && streak >= definition.param && spawnFor(player, definition)) {
                nightDeathStreaks.remove(playerId);
                return;
            }
        }
    }

    void handleDeath(Entity entity, UUID killer) {
        String bossId = entity.getPersistentDataContainer().get(Keys.BOSS_ID, PersistentDataType.STRING);
        if (bossId == null) {
            return;
        }
        ActiveBoss active = activeByEntity.remove(entity.getUniqueId());
        if (active != null) {
            activeByPlayer.remove(active.ownerId, entity.getUniqueId());
            active.bar.removeAll();
        }
        if (killer == null) {
            return;
        }
        BossDefinition definition = registry.get(bossId);
        int reward = definition == null ? 0 : definition.firstKillSoul;
        String achievementId = ACHIEVEMENT_PREFIX + bossId;
        String pendingKey = killer + ":" + achievementId;
        if (!pendingFirstKills.add(pendingKey)) {
            publishKill(killer, bossId, false);
            return;
        }
        api.rowStore().select("cc_achievements", "player_id = ? AND achievement_id = ?",
                killer, achievementId).whenComplete((rows, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    try {
                        if (!plugin.isEnabled()) {
                            return;
                        }
                        if (error != null) {
                            log.log(Level.WARNING, "查询 Boss 首杀记录失败 " + killer + "/" + bossId, error);
                            publishKill(killer, bossId, false);
                            return;
                        }
                        if (!rows.isEmpty()) {
                            publishKill(killer, bossId, false);
                            return;
                        }
                        if (reward > 0) {
                            api.addSoul(killer, reward, "adventure_boss_first_kill");
                        }
                        api.rowStore().insert("cc_achievements", Map.of(
                                "player_id", killer,
                                "achievement_id", achievementId,
                                "unlocked_at", System.currentTimeMillis()
                        )).exceptionally(insertError -> {
                            log.log(Level.WARNING, "写入 Boss 首杀记录失败 " + killer + "/" + bossId, insertError);
                            return null;
                        });
                        publishKill(killer, bossId, true);
                    } finally {
                        pendingFirstKills.remove(pendingKey);
                    }
                }));
    }

    void handleQuit(UUID playerId) {
        nightDeathStreaks.remove(playerId);
        UUID entityId = activeByPlayer.remove(playerId);
        if (entityId != null) {
            removeActive(entityId, true);
        }
    }

    void shutdown() {
        for (ActiveBoss active : new ArrayList<>(activeByEntity.values())) {
            active.bar.removeAll();
            if (active.entity.isValid()) {
                active.entity.remove();
            }
        }
        activeByEntity.clear();
        activeByPlayer.clear();
        nightDeathStreaks.clear();
        pendingFirstKills.clear();
        registry = Map.of();
    }

    int bossCount() {
        return registry.size();
    }

    String status(UUID playerId) {
        UUID entityId = activeByPlayer.get(playerId);
        ActiveBoss active = entityId == null ? null : activeByEntity.get(entityId);
        return active == null ? "无" : active.definition.displayName;
    }

    /** Boss 定义只读视图（GUI 图鉴展示用；不重载期间引用稳定） */
    Map<String, BossDefinition> definitions() {
        return registry;
    }

    /** 玩家当前夜间连死计数（GUI 饿魔母体触发进度展示用） */
    int nightDeathStreak(UUID playerId) {
        return nightDeathStreaks.getOrDefault(playerId, 0);
    }

    private void inspectPlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                if (activeByPlayer.containsKey(player.getUniqueId())) {
                    continue;
                }
                for (BossDefinition definition : registry.values()) {
                    if (definition.trigger != BossDefinition.TriggerType.NIGHT_DEATH_STREAK
                            && matches(player, definition) && spawnFor(player, definition)) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "巡检玩家世界 Boss 触发条件失败：" + player.getName(), e);
            }
        }
    }

    private boolean matches(Player player, BossDefinition definition) {
        World world = player.getWorld();
        return switch (definition.trigger) {
            case RAIN_OCEAN -> world.hasStorm() && !world.isThundering()
                    && player.getLocation().getBlock().getBiome().getKey().getKey().contains("deep_ocean")
                    && chance(definition.param);
            case THUNDER -> world.isThundering() && chance(definition.param);
            case RANDOM -> chance(definition.param);
            case DEPTH -> player.getLocation().getY() < definition.param;
            case NIGHT_DEATH_STREAK -> false;
        };
    }

    private boolean chance(int percent) {
        return percent >= 100 || percent > 0 && ThreadLocalRandom.current().nextInt(100) < percent;
    }

    private boolean spawnFor(Player player, BossDefinition definition) {
        if (activeByPlayer.containsKey(player.getUniqueId())) {
            return false;
        }
        Location location = spawnLocation(player);
        Mob mob = null;
        boolean mythic = MythicAdapter.available(plugin, settings) && !definition.mythicId.isBlank();
        if (mythic) {
            mob = MythicAdapter.spawnMob(definition.mythicId, location);
        }
        if (mob == null) {
            mythic = false;
            mob = spawnVanilla(definition, location);
        }
        if (mob == null) {
            return false;
        }
        mob.getPersistentDataContainer().set(Keys.BOSS_ID, PersistentDataType.STRING, definition.id);
        mob.customName(Component.text(definition.displayName));
        mob.setCustomNameVisible(true);
        BossBar bar = Bukkit.createBossBar(definition.displayName, BarColor.RED, BarStyle.SEGMENTED_10);
        ActiveBoss active = new ActiveBoss(definition, mob, player.getUniqueId(), bar,
                System.currentTimeMillis(), mythic);
        activeByEntity.put(mob.getUniqueId(), active);
        activeByPlayer.put(player.getUniqueId(), mob.getUniqueId());
        updateBar(active);
        return true;
    }

    private Mob spawnVanilla(BossDefinition definition, Location location) {
        EntityType type;
        try {
            type = EntityType.valueOf(definition.entityType.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warning(() -> "Boss " + definition.id + " 原版实体类型非法：" + definition.entityType);
            return null;
        }
        Entity spawned;
        try {
            spawned = location.getWorld().spawnEntity(location, type);
        } catch (IllegalArgumentException e) {
            log.warning(() -> "Boss " + definition.id + " 无法按实体类型生成：" + definition.entityType);
            return null;
        }
        if (!(spawned instanceof Mob mob)) {
            spawned.remove();
            log.warning(() -> "Boss " + definition.id + " 的实体类型不是 Mob：" + definition.entityType);
            return null;
        }
        AttributeInstance maxHealth = mob.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(definition.health);
            mob.setHealth(definition.health);
        }
        AttributeInstance attack = mob.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE);
        if (attack != null) {
            attack.setBaseValue(attack.getBaseValue() + definition.attack);
        }
        return mob;
    }

    private Location spawnLocation(Player player) {
        Location origin = player.getLocation();
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
        double distance = ThreadLocalRandom.current().nextDouble(8.0, 16.0);
        Location result = origin.clone().add(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
        if (result.getBlock().isLiquid()) {
            return result;
        }
        int highest = result.getWorld().getHighestBlockYAt(result.getBlockX(), result.getBlockZ());
        if (Math.abs(highest - origin.getBlockY()) <= 16) {
            result.setY(highest + 1.0);
        }
        return result;
    }

    private void updateActiveBosses() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, ActiveBoss>> iterator = activeByEntity.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ActiveBoss> entry = iterator.next();
            ActiveBoss active = entry.getValue();
            boolean expired = settings.bossDespawnMinutes > 0
                    && now - active.spawnedAt >= settings.bossDespawnMinutes * 60_000L;
            if (!active.entity.isValid() || active.entity.isDead() || expired) {
                active.bar.removeAll();
                if (expired && active.entity.isValid()) {
                    active.entity.remove();
                }
                activeByPlayer.remove(active.ownerId, entry.getKey());
                iterator.remove();
                continue;
            }
            updateBar(active);
        }
    }

    private void updateBar(ActiveBoss active) {
        AttributeInstance max = active.entity.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double maximum = max == null ? Math.max(1.0, active.definition.health) : Math.max(1.0, max.getValue());
        active.bar.setProgress(Math.max(0.0, Math.min(1.0, active.entity.getHealth() / maximum)));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            boolean nearby = player.getWorld().equals(active.entity.getWorld())
                    && player.getLocation().distanceSquared(active.entity.getLocation()) <= BAR_RANGE_SQUARED;
            if (nearby && !active.bar.getPlayers().contains(player)) {
                active.bar.addPlayer(player);
            } else if (!nearby && active.bar.getPlayers().contains(player)) {
                active.bar.removePlayer(player);
            }
        }
    }

    private void runSkills() {
        for (ActiveBoss active : new ArrayList<>(activeByEntity.values())) {
            if (!active.mythic && active.entity.isValid()) {
                for (BossDefinition.Skill skill : active.definition.skills) {
                    runSkill(active, skill);
                }
            }
        }
    }

    private void runSkill(ActiveBoss active, BossDefinition.Skill skill) {
        switch (skill.type()) {
            case SUMMON -> summon(active, skill);
            case EFFECT -> effect(active, skill);
            case LIGHTNING -> lightning(active, skill.radius());
        }
    }

    private void summon(ActiveBoss active, BossDefinition.Skill skill) {
        EntityType type;
        try {
            type = EntityType.valueOf(skill.summon().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return;
        }
        for (int i = 0; i < skill.count(); i++) {
            try {
                Entity minion = active.entity.getWorld().spawnEntity(
                        active.entity.getLocation().clone().add(
                                ThreadLocalRandom.current().nextDouble(-3.0, 3.0), 0.0,
                                ThreadLocalRandom.current().nextDouble(-3.0, 3.0)), type);
                minion.getPersistentDataContainer().set(Keys.MINION, PersistentDataType.BYTE, (byte) 1);
            } catch (IllegalArgumentException ignored) {
                return;
            }
        }
    }

    private void effect(ActiveBoss active, BossDefinition.Skill skill) {
        PotionEffectType type = Registry.EFFECT.get(
                NamespacedKey.minecraft(skill.effect().toLowerCase(Locale.ROOT)));
        if (type == null) {
            return;
        }
        for (Player player : nearbyPlayers(active.entity.getLocation(), skill.radius())) {
            player.addPotionEffect(new PotionEffect(type, settings.bossSkillSeconds * 20 + 20,
                    skill.amplifier(), true, true, true));
        }
    }

    private void lightning(ActiveBoss active, int radius) {
        List<Player> nearby = nearbyPlayers(active.entity.getLocation(), radius);
        if (nearby.isEmpty()) {
            return;
        }
        Player target = nearby.get(ThreadLocalRandom.current().nextInt(nearby.size()));
        target.getWorld().strikeLightning(target.getLocation());
    }

    private List<Player> nearbyPlayers(Location location, int radius) {
        List<Player> result = new ArrayList<>();
        double radiusSquared = (double) radius * radius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                result.add(player);
            }
        }
        return result;
    }

    private void removeActive(UUID entityId, boolean removeEntity) {
        ActiveBoss active = activeByEntity.remove(entityId);
        if (active == null) {
            return;
        }
        activeByPlayer.remove(active.ownerId, entityId);
        active.bar.removeAll();
        if (removeEntity && active.entity.isValid()) {
            active.entity.remove();
        }
    }

    private void publishKill(UUID killer, String bossId, boolean firstKill) {
        api.publish("adventure.boss_killed", new EventData(killer, bossId, 1)
                .put("firstKill", firstKill));
    }

    private static final class ActiveBoss {
        final BossDefinition definition;
        final Mob entity;
        final UUID ownerId;
        final BossBar bar;
        final long spawnedAt;
        final boolean mythic;

        ActiveBoss(BossDefinition definition, Mob entity, UUID ownerId, BossBar bar,
                   long spawnedAt, boolean mythic) {
            this.definition = definition;
            this.entity = entity;
            this.ownerId = ownerId;
            this.bar = bar;
            this.spawnedAt = spawnedAt;
            this.mythic = mythic;
        }
    }
}
