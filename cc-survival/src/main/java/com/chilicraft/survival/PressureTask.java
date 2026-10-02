package com.chilicraft.survival;

import com.chilicraft.api.ChiliCraftAPI;
import com.chilicraft.api.ParamKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.slf4j.Logger;

import java.util.UUID;

/**
 * 1 秒统一压力循环：负重效果 / 体温效果与失温伤害 / 饥饿加速耗竭 / 动作栏 HUD。
 *
 * <p>规格红线：温度与负重一律由周期任务驱动，禁止 PlayerMoveEvent 与高频事件重算。
 * 单玩家处理异常不中断整轮，避免一人问题影响全体。</p>
 */
final class PressureTask implements Runnable {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * 禁跳实现：JUMP 效果 amplifier 250 经网络 byte 截断为负值，
     * 跳跃起始速度变负，跳跃高度归零；使用药水效果保持与现有压力系统一致。
     */
    private static final int JUMP_BLOCK_AMPLIFIER = 250;

    private final Logger logger;
    private final SurvivalSettings settings;
    private final WeightService weight;
    private final TemperatureService temperature;
    private final ChiliCraftAPI api;

    PressureTask(Logger logger, SurvivalSettings settings, WeightService weight,
                 TemperatureService temperature, ChiliCraftAPI api) {
        this.logger = logger;
        this.settings = settings;
        this.weight = weight;
        this.temperature = temperature;
        this.api = api;
    }

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                handle(player);
            } catch (Exception e) {
                logger.warn("玩家 {} 生存压力更新失败", player.getName(), e);
            }
        }
    }

    private void handle(Player player) {
        // 压力只作用于生存/冒险模式玩家（创造/旁观免疫）
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return;
        }
        UUID playerId = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean night = settings.isNight(player.getWorld().getTime());
        // 方街安全区（巡演联动）：名单内世界压力按倍率折减
        double streetFactor = settings.inStreet(player.getWorld().getName()) ? settings.streetMultiplier : 1.0;

        // ---- 负重：消费脏位重算 → 比例 → 惩罚阈值 ----
        int weightPct = 0;
        int weightSlow = -1;
        boolean jumpBlock = false;
        if (settings.weightEnabled) {
            if (weight.consumeDirty(playerId)) {
                weight.recalculate(player);
            }
            double limit = settings.weightLimit * api.getParam(playerId, ParamKey.WEIGHT_LIMIT);
            weightPct = (int) Math.floor(weight.ratio(playerId, limit) * 100.0);
            if (weightPct >= settings.slowThreshold) {
                weightSlow = settings.slowAmplifier;
            }
            if (weightPct >= settings.jumpBlockThreshold) {
                jumpBlock = true;
            }
        }

        // ---- 体温：周期步进 → 分区 ----
        int tempSlow = -1;
        int tempWeakness = -1;
        double tempExtraExhaustion = 0.0;
        boolean hypothermia = false;
        double temp = settings.initialTemp;
        if (settings.tempEnabled) {
            temp = temperature.tick(player);
            SurvivalSettings.Zone zone = temperature.zone(temp);
            tempSlow = zone.slowAmplifier();
            tempWeakness = zone.weaknessAmplifier();
            tempExtraExhaustion = zone.extraExhaustion();
            // 掉入最低档（默认 0-19 失温）且周期伤害到期
            if (zone.min() <= 0 && temperature.hypothermiaDue(playerId, now)) {
                hypothermia = true;
            }
        }

        // ---- 效果施加：每秒续期 40 ticks，插件停止后自然过期，无残留 ----
        // 缓慢取负重与体温中较强者，统一施加一次，避免两个来源互相顶掉
        int slow = Math.max(weightSlow, tempSlow);
        if (slow >= 0) {
            apply(player, PotionEffectType.SLOWNESS, slow);
        }
        if (tempWeakness >= 0) {
            apply(player, PotionEffectType.WEAKNESS, tempWeakness);
        }
        if (jumpBlock) {
            apply(player, PotionEffectType.JUMP_BOOST, JUMP_BLOCK_AMPLIFIER);
        }

        // ---- 饥饿加速：总倍率 > 1 时施加额外耗竭 ----
        // （倍率 < 1 的减缓路径由 SurvivalListener 按概率取消掉食）
        if (settings.hungerEnabled) {
            double hungerMult = settings.hungerMultiplier
                    * api.getParam(playerId, ParamKey.HUNGER_DECAY);
            if (night) {
                hungerMult *= settings.nightMultiplier;
            }
            hungerMult *= streetFactor;
            if (hungerMult > 1.0) {
                addExhaustion(player, settings.baseExhaustion * (hungerMult - 1.0));
            }
        }

        // ---- 燥热额外耗竭 / 失温周期伤害 ----
        if (tempExtraExhaustion > 0.0) {
            addExhaustion(player, tempExtraExhaustion * streetFactor);
        }
        if (hypothermia) {
            player.damage(settings.hypothermiaDamage * streetFactor);
        }

        sendHud(player, temp, weightPct);
    }

    private void apply(Player player, PotionEffectType type, int amplifier) {
        player.addPotionEffect(new PotionEffect(type, settings.effectDurationTicks, amplifier, false, true, true));
    }

    private void addExhaustion(Player player, double amount) {
        player.setExhaustion(player.getExhaustion() + (float) amount);
    }

    // ---------------- HUD ----------------

    private void sendHud(Player player, double temp, int weightPct) {
        if (!settings.hudEnabled) {
            return;
        }
        Component tempPart = settings.tempEnabled ? tempMessage(temp) : null;
        Component weightPart = settings.weightEnabled ? weightMessage(weightPct) : null;
        if (tempPart == null && weightPart == null) {
            return;
        }
        // 均处正常区间时保持动作栏安静（体温 40-89 无惩罚 + 负重未达减速阈值）
        if (settings.hudHideWhenNormal) {
            boolean tempNormal = tempPart == null || (temp >= 40.0 && temp < 90.0);
            boolean weightNormal = weightPart == null || weightPct < settings.slowThreshold;
            if (tempNormal && weightNormal) {
                return;
            }
        }
        Component line = tempPart == null ? weightPart
                : weightPart == null ? tempPart
                : tempPart.append(Component.text("  ")).append(weightPart);
        player.sendActionBar(line);
    }

    /** 体温 → 档位消息（90/60/40/20 五档，与默认 zones 分区一致） */
    private Component tempMessage(double temp) {
        String key;
        if (temp >= 90.0) {
            key = "temp-scorching";
        } else if (temp >= 60.0) {
            key = "temp-comfort";
        } else if (temp >= 40.0) {
            key = "temp-mild";
        } else if (temp >= 20.0) {
            key = "temp-cold";
        } else {
            key = "temp-freezing";
        }
        return msg(key, null, -1);
    }

    /** 负重 → 档位消息 */
    private Component weightMessage(int pct) {
        String key;
        if (pct >= settings.jumpBlockThreshold) {
            key = "weight-overload";
        } else if (pct >= settings.slowThreshold) {
            key = "weight-heavy";
        } else {
            key = "weight-fine";
        }
        return msg(key, "pct", pct);
    }

    /**
     * 读缓存模板并解析为 Component；pctKey 非空时注入占位。
     * 模板缺失返回空组件（HUD 容忍缺键静默），解析失败回退纯文本。
     */
    private Component msg(String key, String pctKey, int pctValue) {
        String template = settings.message(key);
        if (template.isEmpty()) {
            return Component.empty();
        }
        try {
            if (pctKey == null) {
                return MINI.deserialize(template);
            }
            return MINI.deserialize(template, Placeholder.unparsed(pctKey, String.valueOf(pctValue)));
        } catch (Throwable t) {
            return Component.text(template);
        }
    }
}
