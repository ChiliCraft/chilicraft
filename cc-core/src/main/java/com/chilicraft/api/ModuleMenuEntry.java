package com.chilicraft.api;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 不可变的模块菜单入口描述。 */
public record ModuleMenuEntry(
        String moduleId,
        String displayName,
        MenuCategory category,
        Material icon,
        int sortOrder,
        Set<String> usePermissions,
        Set<String> adminPermissions,
        String description,
        BooleanSupplier available,
        Consumer<Player> openAction,
        Consumer<Player> helpAction) {

    public ModuleMenuEntry {
        moduleId = requireText(moduleId, "moduleId");
        displayName = Objects.requireNonNull(displayName, "displayName");
        category = Objects.requireNonNull(category, "category");
        icon = Objects.requireNonNull(icon, "icon");
        usePermissions = immutablePermissions(usePermissions, "usePermissions");
        adminPermissions = immutablePermissions(adminPermissions, "adminPermissions");
        description = Objects.requireNonNull(description, "description");
        available = Objects.requireNonNull(available, "available");
        openAction = Objects.requireNonNull(openAction, "openAction");
        helpAction = Objects.requireNonNull(helpAction, "helpAction");
    }

    private static Set<String> immutablePermissions(Set<String> permissions, String name) {
        Objects.requireNonNull(permissions, name);
        if (permissions.stream().anyMatch(permission -> permission == null || permission.isBlank())) {
            throw new IllegalArgumentException(name + " 包含空权限节点");
        }
        return Set.copyOf(permissions);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value;
    }
}
