package com.chilicraft.soul;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.EventData;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 遗物服务：12 遗物的全服唯一登记 / 物品构建 / 灵韵消耗 / 效果缓存 / 流转史。
 *
 * <p>遗物是原版物品 + PDC 标记（relic_id / durability / owner / history），
 * 权威状态在内存缓存（{@link CachedRelic}），触发式灵韵消耗先改缓存并标脏，
 * 由周期任务按配置间隔批量落库；状态迁移（沉眠 / 入土）即时落库。
 * DB 更新一律按 {@code relic_id = ?} 定位（全服唯一键），避免依赖
 * AUTOINCREMENT 行 id 的 insert 回填时序。</p>
 *
 * <p>线程边界：全部公开方法仅主线程调用（load 的 select 回调显式切回主线程）。
 * 效果缓存按玩家重建：2 秒周期扫背包 41 格（含盔甲与副手），
 * 持续型效果按「期望 - 已上」差异增删药水，事件钩子型效果由监听器读
 * {@link #effectBonus} 即时生效。</p>
 */
final class RelicService {

    /** 附属表名（cc-core 白名单内） */
    private static final String TABLE = "cc_relics";
    /** 流转史存储分隔符（玩家名不含分号，可安全拼接） */
    private static final String HISTORY_SEP = ";";
    /** 流转史上限（超出丢弃最早的记录） */
    private static final int HISTORY_CAP = 10;

    /** cc-demon 标记键：识别饿魔实体（DEMON_WARD 减免判定用；未装 cc-demon 时该加成自然为 0） */
    private static final NamespacedKey DEMON_TYPE_KEY = NamespacedKey.fromString("cc-demon:demon_type");

    /** 遗物状态：在场生效 / 灵韵耗尽沉眠 / 已入土（不在场，不可重新发放前处于此态） */
    enum RelicState {
        ACTIVE, EXHAUSTED, BURIED;

        /** 解析 DB 状态列；未知值回退 ACTIVE（宁可多给灵韵也不误吞） */
        static RelicState parse(String raw) {
            if (raw == null || raw.isEmpty()) {
                return ACTIVE;
            }
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ACTIVE;
            }
        }
    }

    /** 遗物内存缓存行（不含 owner——展示层从物品 PDC 读取，DB 权威可查） */
    record CachedRelic(String relicId, int durability, RelicState state) {
    }

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final SoulSettings settings;
    private final ChiliCraftAPI api;
    private final Logger logger;

    // ---- PDC 四键（物品身份标记） ----
    private final NamespacedKey relicIdKey;
    private final NamespacedKey durabilityKey;
    private final NamespacedKey ownerKey;
    private final NamespacedKey historyKey;

    /** 全服唯一登记：relicId -> 缓存行 */
    private final Map<String, CachedRelic> relics = new LinkedHashMap<>();
    /** 效果缓存：玩家 -> 效果 -> 触发该效果的遗物 ID（2 秒周期重建） */
    private final Map<UUID, Map<RelicEffect, String>> effects = new LinkedHashMap<>();
    /** 已施加的持续型药水（差异增删基准；INFINITE_DURATION 一次施加不再续期） */
    private final Map<UUID, Set<PotionEffectType>> applied = new LinkedHashMap<>();
    /** 待落库的灵韵脏集（批量落库间隔由配置 relics.db-flush-interval 控制） */
    private final Set<String> dirty = new HashSet<>();

    RelicService(org.bukkit.plugin.java.JavaPlugin plugin, SoulSettings settings,
                 ChiliCraftAPI api, Logger logger) {
        this.plugin = plugin;
        this.settings = settings;
        this.api = api;
        this.logger = logger;
        this.relicIdKey = new NamespacedKey(plugin, "relic_id");
        this.durabilityKey = new NamespacedKey(plugin, "relic_durability");
        this.ownerKey = new NamespacedKey(plugin, "relic_owner");
        this.historyKey = new NamespacedKey(plugin, "relic_history");
    }

    // ---------------- 生命周期 ----------------

    /** 启动加载：读全部未入土遗物行，回主线程填充缓存（EXHAUSTED 行同样在场，等待葬礼） */
    void load() {
        api.rowStore().select(TABLE, "state != ?", "BURIED").thenAccept(rows ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Map<String, Object> row : rows) {
                        cacheRow(row);
                    }
                    logger.info("遗物缓存已加载（在场 {} 件）", relics.size());
                })).exceptionally(ex -> {
            logger.error("遗物加载失败：{}", ex.getMessage());
            return null;
        });
    }

    /** 单行转缓存；未知遗物 ID（配置已删）跳过 */
    private void cacheRow(Map<String, Object> row) {
        String id = row.get("relic_id") instanceof String s ? s : null;
        if (id == null || settings.relic(id) == null) {
            return;
        }
        int durability = row.get("durability") instanceof Number n ? n.intValue() : 0;
        RelicState state = RelicState.parse(row.get("state") instanceof String s ? s : null);
        relics.put(id, new CachedRelic(id, durability, state));
    }

    /** 关服兜底：脏数据落库 + 移除全部持续型药水 + 清空缓存 */
    void clearAll() {
        flushNow();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Set<PotionEffectType> current = applied.remove(player.getUniqueId());
            if (current != null) {
                for (PotionEffectType type : current) {
                    player.removePotionEffect(type);
                }
            }
            effects.remove(player.getUniqueId());
        }
        relics.clear();
        dirty.clear();
    }

    // ---------------- 发放与识别 ----------------

    /**
     * 发放遗物（全服唯一校验 + 建物品 + 落库 + 发布事件）。
     *
     * @return false = 遗物未定义或已在场
     */
    boolean give(String relicId, Player player) {
        RelicDefinition def = settings.relic(relicId);
        if (def == null || relics.containsKey(relicId)) {
            return false;
        }
        List<String> history = List.of(player.getName());
        relics.put(relicId, new CachedRelic(relicId, def.durability(), RelicState.ACTIVE));
        ItemStack item = buildItem(relicId, player.getUniqueId(), history, def.durability());
        if (item != null) {
            // 背包放不下时原地掉落，不吞物品
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
            for (ItemStack rest : overflow.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), rest);
            }
        }
        // insert 不回填行 id：后续更新一律按 relic_id 定位，无时序依赖
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("relic_id", relicId);
        row.put("owner_id", player.getUniqueId());
        row.put("durability", def.durability());
        row.put("history_json", historyToJson(history));
        row.put("state", RelicState.ACTIVE.name());
        api.rowStore().insert(TABLE, row).exceptionally(ex -> {
            logger.error("遗物 {} 落库失败：{}", relicId, ex.getMessage());
            return null;
        });
        api.publish("soul.relic_gained", new EventData(player.getUniqueId(), relicId, 1));
        return true;
    }

    /** 构建遗物物品（PDC 身份 + 展示名 + 三段 lore）；定义缺失返回 null */
    ItemStack buildItem(String relicId, UUID holder, List<String> history, int durability) {
        RelicDefinition def = settings.relic(relicId);
        if (def == null) {
            return null;
        }
        ItemStack item = new ItemStack(def.material());
        ItemMeta meta = item.getItemMeta();
        applyIdentity(meta, relicId, holder, history, durability);
        item.setItemMeta(meta);
        return item;
    }

    /** 物品是否为本模块遗物（PDC relic_id 存在且定义仍在配置中） */
    boolean isRelic(ItemStack item) {
        return relicIdOf(item) != null;
    }

    /** 读物品遗物 ID；非遗物或定义已删除返回 null */
    String relicIdOf(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer()
                .get(relicIdKey, PersistentDataType.STRING);
        return id != null && settings.relic(id) != null ? id : null;
    }

    /** 写入展示名 + 三段 lore + PDC 四键（发放 / 拾取转移 / 灵韵同步共用） */
    private void applyIdentity(ItemMeta meta, String relicId, UUID holder,
                               List<String> history, int durability) {
        RelicDefinition def = settings.relic(relicId);
        meta.displayName(Texts.parse(def.display()));
        List<Component> lore = new ArrayList<>();
        if (!def.lore().isEmpty()) {
            lore.add(Texts.parse(def.lore()));
        }
        lore.add(Texts.parse(settings.message("relic-lore"),
                Placeholder.unparsed("durability", String.valueOf(Math.max(0, durability))),
                Placeholder.unparsed("max", String.valueOf(def.durability())),
                Placeholder.unparsed("owner", holderName(holder))));
        if (!history.isEmpty()) {
            lore.add(Texts.parse(settings.message("relic-history"),
                    Placeholder.component("history", historyComponent(history))));
        }
        meta.lore(lore);
        var pdc = meta.getPersistentDataContainer();
        pdc.set(relicIdKey, PersistentDataType.STRING, relicId);
        pdc.set(durabilityKey, PersistentDataType.INTEGER, Math.max(0, durability));
        pdc.set(ownerKey, PersistentDataType.STRING, holder != null ? holder.toString() : "");
        pdc.set(historyKey, PersistentDataType.STRING, historyToJson(history));
    }

    // ---------------- 拾取转移与灵韵消耗 ----------------

    /**
     * 玩家拾取遗物掉落物：变更持有者、追加流转史、更新物品与 DB。
     * 由 EntityPickupItemEvent（HIGH + ignoreCancelled）调用。
     */
    void onPickup(Player player, ItemStack item) {
        String id = relicIdOf(item);
        if (id == null) {
            return;
        }
        CachedRelic cached = relics.get(id);
        if (cached == null) {
            // 未登记的遗物（如缓存加载前拾取）：不覆盖状态，避免以 0 灵韵覆盖权威值
            return;
        }
        ItemMeta meta = item.getItemMeta();
        List<String> history = appendHistory(meta, player.getName());
        applyIdentity(meta, id, player.getUniqueId(), history, cached.durability());
        item.setItemMeta(meta);
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("owner_id", player.getUniqueId());
        set.put("history_json", historyToJson(history));
        api.rowStore().update(TABLE, set, "relic_id = ?", id);
        api.publish("soul.relic_gained", new EventData(player.getUniqueId(), id, 1));
    }

    /**
     * 事件钩子触发：消耗 1 点灵韵并同步背包物品。
     * 归零时即时落库并提示沉眠；否则标脏等待批量落库。
     */
    void consume(Player player, RelicEffect effect) {
        Map<RelicEffect, String> owned = effects.get(player.getUniqueId());
        String relicId = owned != null ? owned.get(effect) : null;
        if (relicId == null) {
            return;
        }
        CachedRelic cached = relics.get(relicId);
        if (cached == null || cached.state() != RelicState.ACTIVE || cached.durability() <= 0) {
            return;
        }
        int newDurability = cached.durability() - 1;
        RelicState newState = newDurability <= 0 ? RelicState.EXHAUSTED : cached.state();
        relics.put(relicId, new CachedRelic(relicId, newDurability, newState));
        syncItemDurability(player, relicId, newDurability);
        if (newState == RelicState.EXHAUSTED) {
            // 状态迁移即时落库（不进脏集，防止 flush 间隔内被重复消耗）
            api.rowStore().update(TABLE,
                    Map.of("durability", 0, "state", newState.name()), "relic_id = ?", relicId);
            dirty.remove(relicId);
            owned.remove(effect);
            RelicDefinition def = settings.relic(relicId);
            player.sendMessage(Texts.parse(settings.feedback("relic-exhausted"),
                    Placeholder.component("display", Texts.parse(def.display()))));
        } else {
            dirty.add(relicId);
        }
    }

    /** 扫背包重建指定遗物物品的灵韵展示（引用定位，唯一物品至多命中一件） */
    private void syncItemDurability(Player player, String relicId, int durability) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            String id = meta.getPersistentDataContainer().get(relicIdKey, PersistentDataType.STRING);
            if (!relicId.equals(id)) {
                continue;
            }
            applyIdentity(meta, relicId, readHolder(meta), readHistory(meta), durability);
            item.setItemMeta(meta);
            return;
        }
    }

    // ---------------- 效果缓存与持续型效果 ----------------

    /** 读取玩家当前效果加成值（效果缓存由 2 秒周期重建） */
    double effectBonus(Player player, RelicEffect effect) {
        Map<RelicEffect, String> owned = effects.get(player.getUniqueId());
        String relicId = owned != null ? owned.get(effect) : null;
        if (relicId == null) {
            return 0.0;
        }
        RelicDefinition def = settings.relic(relicId);
        return def != null ? def.value() : 0.0;
    }

    /** 2 秒周期：重建效果缓存 → 药水差异增删 → 夜间回血 */
    void tickEffects() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<RelicEffect, String> owned = new EnumMap<>(RelicEffect.class);
            for (ItemStack item : player.getInventory().getContents()) {
                String id = relicIdOf(item);
                if (id == null) {
                    continue;
                }
                CachedRelic cached = relics.get(id);
                if (cached == null || cached.state() != RelicState.ACTIVE || cached.durability() <= 0) {
                    continue;
                }
                RelicDefinition def = settings.relic(id);
                if (def != null) {
                    owned.putIfAbsent(def.effect(), id);
                }
            }
            effects.put(player.getUniqueId(), owned);
            applyPotions(player, owned);
            applyNightRegen(player, owned);
        }
    }

    /** 持续型药水：期望 - 已上 差异增删；INFINITE_DURATION 一次施加，牛奶清除后下周期自动恢复 */
    private void applyPotions(Player player, Map<RelicEffect, String> owned) {
        // PotionEffectType 非枚举类，只能用 HashMap 做键容器
        Map<PotionEffectType, Integer> desired = new HashMap<>();
        if (owned.containsKey(RelicEffect.SPEED)) {
            // 速度药水每级 +20%，0.15 为期望加成粒度，上限 2 级
            double value = value(owned, RelicEffect.SPEED);
            desired.put(PotionEffectType.SPEED, Math.min(2, (int) Math.floor(value / 0.15)));
        }
        if (owned.containsKey(RelicEffect.NIGHT_VISION) && settings.isNight(player.getWorld().getTime())) {
            desired.put(PotionEffectType.NIGHT_VISION, 0);
        }
        if (owned.containsKey(RelicEffect.WATER_BREATH)) {
            desired.put(PotionEffectType.WATER_BREATHING, 0);
        }
        Set<PotionEffectType> current = applied.getOrDefault(player.getUniqueId(), Set.of());
        for (Map.Entry<PotionEffectType, Integer> entry : desired.entrySet()) {
            if (!current.contains(entry.getKey())) {
                // ambient 无图标扰动、无粒子、不显示在 HUD
                player.addPotionEffect(new PotionEffect(entry.getKey(),
                        PotionEffect.INFINITE_DURATION, entry.getValue(), true, false, false));
            }
        }
        for (PotionEffectType type : current) {
            if (!desired.containsKey(type)) {
                player.removePotionEffect(type);
            }
        }
        applied.put(player.getUniqueId(), Set.copyOf(desired.keySet()));
    }

    /** 夜间每 2 秒回 value 点生命（月亮井的碎片） */
    private void applyNightRegen(Player player, Map<RelicEffect, String> owned) {
        if (!owned.containsKey(RelicEffect.NIGHT_REGEN)
                || !settings.isNight(player.getWorld().getTime())) {
            return;
        }
        heal(player, value(owned, RelicEffect.NIGHT_REGEN));
    }

    /** 保守回血：setHealth 截断到属性上限（不依赖 Paper 扩展方法） */
    void heal(Player player, double amount) {
        AttributeInstance attr = player.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = attr != null ? attr.getValue() : 20.0;
        if (player.getHealth() > 0 && player.getHealth() < max) {
            player.setHealth(Math.min(player.getHealth() + amount, max));
        }
    }

    // ---------------- 判定辅助（供监听器） ----------------

    /** 实体是否为 cc-demon 标记的饿魔（未装 cc-demon 时恒 false，DEMON_WARD 自然降级） */
    boolean isDemon(Entity entity) {
        return entity.getPersistentDataContainer().has(DEMON_TYPE_KEY, PersistentDataType.STRING);
    }

    /** 玩家是否正被雨淋（主世界有风暴 且 头顶无方块遮挡） */
    boolean isRainingOn(Player player) {
        org.bukkit.World world = player.getWorld();
        return world.hasStorm()
                && player.getLocation().getBlockY() >= world.getHighestBlockYAt(player.getLocation());
    }

    // ---------------- 玩家退出 / 落库 / 入土 ----------------

    /** 玩家退出：移除已施加药水并清效果缓存（遗物登记保留，物品随玩家存档） */
    void handleQuit(Player player) {
        Set<PotionEffectType> current = applied.remove(player.getUniqueId());
        if (current != null) {
            for (PotionEffectType type : current) {
                player.removePotionEffect(type);
            }
        }
        effects.remove(player.getUniqueId());
    }

    /** 脏集批量落库（周期任务按 relics.db-flush-interval 调用） */
    void flushNow() {
        if (dirty.isEmpty()) {
            return;
        }
        for (String id : dirty) {
            CachedRelic cached = relics.get(id);
            if (cached == null) {
                continue;
            }
            api.rowStore().update(TABLE,
                    Map.of("durability", cached.durability(), "state", cached.state().name()),
                    "relic_id = ?", id);
        }
        dirty.clear();
    }

    /** 遗物入土（葬礼完成）：移出在场缓存并落库，同 ID 由此可重新出现 */
    void bury(String relicId) {
        relics.remove(relicId);
        dirty.remove(relicId);
        api.rowStore().update(TABLE, Map.of("state", RelicState.BURIED.name()), "relic_id = ?", relicId);
    }

    /** 读在场缓存；null = 未发放 / 已入土 */
    CachedRelic cached(String relicId) {
        return relics.get(relicId);
    }

    /** 全部在场遗物（图鉴命令展示用） */
    Map<String, CachedRelic> snapshot() {
        return Map.copyOf(relics);
    }

    // ---------------- 流转史 ----------------

    /** 追加流转史（尾部插入、超限丢头部、最后持有人同名不重复） */
    private List<String> appendHistory(ItemMeta meta, String playerName) {
        List<String> history = new ArrayList<>(readHistory(meta));
        if (!history.isEmpty() && history.get(history.size() - 1).equals(playerName)) {
            return history;
        }
        history.add(playerName);
        while (history.size() > HISTORY_CAP) {
            history.remove(0);
        }
        return history;
    }

    /** 流转史展示：名字列表按 relic-history-sep 模板拼接 */
    private Component historyComponent(List<String> history) {
        Component sep = Texts.parse(settings.message("relic-history-sep"));
        List<Component> parts = new ArrayList<>(history.size());
        for (String name : history) {
            parts.add(Component.text(name));
        }
        return Component.join(JoinConfiguration.separator(sep), parts);
    }

    /** 序列化流转史为 JSON 数组（手动转义，格式与 DB history_json 列一致） */
    private static String historyToJson(List<String> history) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"')
                    .append(history.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return sb.append(']').toString();
    }

    /** 从 PDC 读流转史（自写 JSON 轻量解析：仅识别 "..." 字符串） */
    private List<String> readHistory(ItemMeta meta) {
        String json = meta.getPersistentDataContainer().get(historyKey, PersistentDataType.STRING);
        return parseHistory(json);
    }

    private static List<String> parseHistory(String json) {
        List<String> result = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return result;
        }
        int i = 0;
        int len = json.length();
        while (i < len) {
            char c = json.charAt(i);
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                while (i < len) {
                    char ch = json.charAt(i);
                    if (ch == '\\' && i + 1 < len) {
                        sb.append(json.charAt(i + 1));
                        i += 2;
                    } else if (ch == '"') {
                        i++;
                        break;
                    } else {
                        sb.append(ch);
                        i++;
                    }
                }
                result.add(sb.toString());
            } else {
                i++;
            }
        }
        return result;
    }

    private UUID readHolder(ItemMeta meta) {
        String raw = meta.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String holderName(UUID holder) {
        if (holder == null) {
            return "未知";
        }
        String name = Bukkit.getOfflinePlayer(holder).getName();
        return name != null ? name : holder.toString().substring(0, 8);
    }

    private double value(Map<RelicEffect, String> owned, RelicEffect effect) {
        String id = owned.get(effect);
        RelicDefinition def = id != null ? settings.relic(id) : null;
        return def != null ? def.value() : 0.0;
    }
}
