package com.chilicraft.soul;

import org.slf4j.Logger;

/**
 * 统一 1 秒权威循环：葬礼站桩判定 / 遗物效果与碎片业务 / 灵韵批量落库。
 *
 * <p>单任务单句柄单权威循环，避免多任务松散协调；
 * 各业务独立 try-catch，一项失败不拖垮整轮。</p>
 *
 * <ul>
 *   <li>每秒：葬礼站桩判定（离线 / 离开 / 到期结算）</li>
 *   <li>每 2 秒：遗物效果缓存与药水差异 → 碎片自动拾回 → 碎片过期清理</li>
 *   <li>每 relics.db-flush-interval 秒：灵韵脏数据批量落库</li>
 * </ul>
 */
final class SoulTickTask implements Runnable {

    private final Logger logger;
    private final SoulSettings settings;
    private final RelicService relics;
    private final FragmentService fragments;
    private final FuneralService funeral;

    /** 秒计数器（只增不减，模运算触发各业务） */
    private long second;

    SoulTickTask(Logger logger, SoulSettings settings,
                 RelicService relics, FragmentService fragments, FuneralService funeral) {
        this.logger = logger;
        this.settings = settings;
        this.relics = relics;
        this.fragments = fragments;
        this.funeral = funeral;
    }

    @Override
    public void run() {
        second++;

        try {
            funeral.tick();
        } catch (Throwable t) {
            logger.warn("葬礼周期异常", t);
        }

        if (second % 2 == 0) {
            try {
                relics.tickEffects();
            } catch (Throwable t) {
                logger.warn("遗物效果周期异常", t);
            }
            try {
                fragments.tickPickup(relics, funeral.activePlayers());
            } catch (Throwable t) {
                logger.warn("碎片拾回周期异常", t);
            }
            try {
                fragments.tickExpire();
            } catch (Throwable t) {
                logger.warn("碎片过期周期异常", t);
            }
        }

        // 落库间隔从活配置读取：/cc reload 调整间隔后无需重启
        long flushEvery = Math.max(1, settings.relicDbFlushIntervalSec);
        if (second % flushEvery == 0) {
            try {
                relics.flushNow();
            } catch (Throwable t) {
                logger.warn("灵韵落库周期异常", t);
            }
        }
    }
}
