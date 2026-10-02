package com.chilicraft.api;

import java.util.List;

/**
 * 附属模块配置包装接口。
 *
 * <p>约定流程：附属 onEnable 时先 {@code registerModuleConfig("cc-demon", 附属默认配置)}，
 * 核心据此把 dataFolder/config.yml 的对应节与默认值合并，
 * 附属后续始终通过 {@code getModuleConfig("cc-demon")} 读取配置，
 * 核心执行 /cc reload 时自动合并最新文件内容。</p>
 *
 * <p>path 为模块节内的相对路径（如 {@code "mob.spawn-chance"}）。</p>
 */
public interface ModuleConfig {
    /** 模块标识（如 cc-demon） */
    String moduleId();

    int getInt(String path, int def);

    double getDouble(String path, double def);

    boolean getBoolean(String path, boolean def);

    String getString(String path, String def);

    List<String> getStringList(String path);
}
