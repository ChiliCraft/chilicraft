package com.chilicraft.api;

import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** 不可变的模块命令路由描述。 */
public record ModuleCommandRoute(
        String moduleId,
        Set<String> aliases,
        Set<String> usePermissions,
        Set<String> adminPermissions,
        ModuleCommandExecutor executor,
        ModuleTabCompleter completer) {

    public ModuleCommandRoute {
        moduleId = normalize(moduleId, "moduleId");
        aliases = normalizeSet(aliases, "aliases");
        usePermissions = immutableSet(usePermissions, "usePermissions");
        adminPermissions = immutableSet(adminPermissions, "adminPermissions");
        executor = Objects.requireNonNull(executor, "executor");
        completer = Objects.requireNonNull(completer, "completer");
    }

    private static Set<String> normalizeSet(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        return values.stream().map(value -> normalize(value, name)).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> immutableSet(Set<String> values, String name) {
        Objects.requireNonNull(values, name);
        if (values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException(name + " 包含空值");
        }
        return Set.copyOf(values);
    }

    private static String normalize(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空");
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
