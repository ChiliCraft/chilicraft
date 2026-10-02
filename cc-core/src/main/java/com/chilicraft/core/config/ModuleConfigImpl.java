package com.chilicraft.core.config;

import com.chilicraft.api.ModuleConfig;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/**
 * ModuleConfig 通用实现：包装一份 FileConfiguration 的指定根节。
 *
 * <p>读操作穿透到底层配置对象（不做拷贝快照），
 * 因此附属 reload 自己的文件后，后续读取自动看到最新值，
 * 与接口契约「核心 /cc reload 时自动合并最新文件内容」一致。</p>
 */
public final class ModuleConfigImpl implements ModuleConfig {

    private final String moduleId;
    private final FileConfiguration config;
    private final String root;

    /**
     * @param moduleId 模块标识（如 cc-demon）
     * @param config   附属已加载的 FileConfiguration
     * @param root     模块节根路径（如 "demon"）；传空串表示从根读取
     */
    public ModuleConfigImpl(String moduleId, FileConfiguration config, String root) {
        this.moduleId = moduleId;
        this.config = config;
        this.root = root == null || root.isEmpty() ? "" : root;
    }

    @Override
    public String moduleId() {
        return moduleId;
    }

    private String path(String relative) {
        return root.isEmpty() ? relative : root + "." + relative;
    }

    @Override
    public int getInt(String path, int def) {
        return config.getInt(path(path), def);
    }

    @Override
    public double getDouble(String path, double def) {
        return config.getDouble(path(path), def);
    }

    @Override
    public boolean getBoolean(String path, boolean def) {
        return config.getBoolean(path(path), def);
    }

    @Override
    public String getString(String path, String def) {
        return config.getString(path(path), def);
    }

    @Override
    public List<String> getStringList(String path) {
        List<String> list = config.getStringList(path(path));
        return list == null ? List.of() : list;
    }
}
