package com.chilicraft.season;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.WrappedLevelChunkData;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 使用 ProtocolLib 仅修改玩家收到的区块 biome palette，不改变服务器真实区块。 */
final class ProtocolVisualAdapter {

    private final ChiliSeasonPlugin plugin;
    private final SeasonService service;
    private final SeasonSettings settings;
    private PacketAdapter listener;
    private BukkitTask refreshTask;
    private boolean available;
    private final Map<String, Integer> biomeIds = new HashMap<>();
    private final ArrayDeque<RefreshTarget> refreshQueue = new ArrayDeque<>();
    private long rewritten;
    private long skipped;
    private long failed;
    private long refreshedChunks;
    private long refreshNanos;

    ProtocolVisualAdapter(ChiliSeasonPlugin plugin, SeasonService service, SeasonSettings settings) {
        this.plugin = plugin;
        this.service = service;
        this.settings = settings;
        Plugin protocolLib = plugin.getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib == null || !protocolLib.isEnabled() || !settings.visualEnabled) {
            if (settings.visualEnabled) {
                plugin.getLogger().warning("未检测到 ProtocolLib，季节视觉适配已禁用");
            }
            return;
        }
        try {
            listener = new PacketAdapter(plugin, ListenerPriority.NORMAL, PacketType.Play.Server.MAP_CHUNK) {
                @Override
                public void onPacketSending(PacketEvent event) {
                    rewrite(event);
                }
            };
            ProtocolLibrary.getProtocolManager().addPacketListener(listener);
            available = true;
            reload();
            plugin.getLogger().info("已启用逐玩家季节 biome 发包视觉");
        } catch (Throwable throwable) {
            plugin.getLogger().warning("ProtocolLib biome 视觉初始化失败，已降级：" + throwable.getMessage());
        }
    }

    private void rewrite(PacketEvent event) {
        Player player = event.getPlayer();
        World world = player.getWorld();
        if (settings.disabledWorlds.contains(world.getName())) {
            return;
        }
        String biomeName = settings.visualBiomes.get(
                service.state().season().name().toLowerCase(Locale.ROOT));
        if (biomeName == null) {
            skipped++;
            return;
        }
        try {
            Integer biomeId = biomeIds.get(biomeName);
            if (biomeId == null || biomeId < 0) {
                skipped++;
                return;
            }
            StructureModifier<WrappedLevelChunkData.ChunkData> modifier =
                    event.getPacket().getLevelChunkData();
            if (modifier.size() == 0) {
                skipped++;
                return;
            }
            WrappedLevelChunkData.ChunkData data = modifier.read(0);
            byte[] rewritten = rewriteSections(data.getBuffer(), world, biomeId);
            if (rewritten != null) {
                data.setBuffer(rewritten);
                modifier.write(0, data);
                this.rewritten++;
            } else {
                skipped++;
            }
        } catch (Throwable throwable) {
            failed++;
        }
    }

    private int resolveBiomeId(String biomeName) throws IOException {
        try {
            Class<?> craftWorld = Class.forName("org.bukkit.craftbukkit.CraftWorld");
            Method getHandle = craftWorld.getMethod("getHandle");
            Object handle = getHandle.invoke(Bukkit.getWorlds().get(0));
            Method registryAccessMethod = findNoArgMethod(handle.getClass(), "registryAccess");
            Object registryAccess = registryAccessMethod.invoke(handle);

            Class<?> resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation");
            Method parse = findStaticMethod(resourceLocationClass, "tryParse", String.class);
            Object location = parse.invoke(null, "minecraft:" + biomeName.toLowerCase(Locale.ROOT));
            if (location == null) {
                throw new IOException("无法解析 biome 资源位置：" + biomeName);
            }

            Class<?> registriesClass = Class.forName("net.minecraft.core.registries.Registries");
            Object biomeRegistryKey = registriesClass.getField("BIOME").get(null);
            Class<?> resourceKeyClass = Class.forName("net.minecraft.resources.ResourceKey");
            Method create = findStaticMethod(resourceKeyClass, "create", biomeRegistryKey.getClass(), resourceLocationClass);
            Object resourceKey = create.invoke(null, biomeRegistryKey, location);

            Method registry = findOneArgMethod(
                    registryAccess.getClass(), "registryOrThrow", resourceKeyClass);
            Object biomeRegistry = registry.invoke(registryAccess, biomeRegistryKey);
            Method get = findOneArgMethod(
                    biomeRegistry.getClass(), "get", resourceKeyClass);
            Object biome = get.invoke(biomeRegistry, resourceKey);
            Method getId = findRegistryIdMethod(biomeRegistry.getClass(), biome.getClass());
            return ((Number) getId.invoke(biomeRegistry, biome)).intValue();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IOException("无法读取服务器 biome registry：" + biomeName, exception);
        }
    }

    private Method findNoArgMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 0) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private Method findStaticMethod(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private Method findOneArgMethod(Class<?> type, String name, Class<?> parameter)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(parameter)) {
                return method;
            }
        }
        throw new NoSuchMethodException(name + " on " + type.getName());
    }

    private Method findRegistryIdMethod(Class<?> type, Class<?> valueType)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            Class<?> returnType = method.getReturnType();
            if (method.getName().equals("getId")
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(valueType)
                    && (returnType == int.class || returnType == Integer.class)) {
                return method;
            }
        }
        throw new NoSuchMethodException("getId on " + type.getName());
    }

    private byte[] rewriteSections(byte[] input, World world, int biomeId) throws IOException {
        ByteReader reader = new ByteReader(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        int sections = (world.getMaxHeight() - world.getMinHeight()) / 16;
        for (int section = 0; section < sections; section++) {
            copyBlockContainer(reader, output);
            skipBiomeContainer(reader);
            writeSingleBiomeContainer(output, biomeId);
        }
        if (reader.remaining() != 0) {
            return null;
        }
        return output.toByteArray();
    }

    private void copyBlockContainer(ByteReader reader, ByteArrayOutputStream output) throws IOException {
        int bits = reader.readUnsignedByte();
        output.write(bits);
        int paletteBits = Math.max(4, bits);
        if (bits == 0 || bits <= 8) {
            int paletteSize = reader.readVarInt();
            writeVarInt(output, paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                writeVarInt(output, reader.readVarInt());
            }
        }
        int longCount = reader.readVarInt();
        writeVarInt(output, longCount);
        for (int i = 0; i < longCount; i++) {
            long value = reader.readLong();
            writeLong(output, value);
        }
        if (bits > 8 && paletteBits < 9) {
            throw new IOException("非法 block palette bits");
        }
    }

    private void skipBiomeContainer(ByteReader reader) throws IOException {
        int bits = reader.readUnsignedByte();
        if (bits == 0 || bits <= 3) {
            int paletteSize = reader.readVarInt();
            for (int i = 0; i < paletteSize; i++) {
                reader.readVarInt();
            }
        }
        int longCount = reader.readVarInt();
        for (int i = 0; i < longCount; i++) {
            reader.readLong();
        }
    }

    private void writeSingleBiomeContainer(ByteArrayOutputStream output, int biomeId) throws IOException {
        output.write(0);
        writeVarInt(output, biomeId);
        writeVarInt(output, 0);
    }

    private void writeVarInt(ByteArrayOutputStream output, int value) throws IOException {
        while ((value & 0xFFFFFF80) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private void writeLong(ByteArrayOutputStream output, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            output.write((int) (value >>> shift) & 0xFF);
        }
    }

    void reload() {
        biomeIds.clear();
        if (!available || !settings.visualEnabled) {
            return;
        }
        for (String biomeName : settings.visualBiomes.values()) {
            try {
                biomeIds.put(biomeName, resolveBiomeId(biomeName));
            } catch (IOException exception) {
                biomeIds.put(biomeName, -1);
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                        "无法缓存 biome registry ID：" + biomeName, exception);
            }
        }
    }

    void refresh() {
        if (!available || !settings.resend) {
            return;
        }
        if (refreshTask != null) {
            refreshTask.cancel();
        }
        refreshQueue.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();
            if (settings.disabledWorlds.contains(world.getName())) {
                continue;
            }
            int view = Math.max(1, player.getClientViewDistance());
            int centerX = player.getLocation().getBlockX() >> 4;
            int centerZ = player.getLocation().getBlockZ() >> 4;
            for (int x = centerX - view; x <= centerX + view; x++) {
                for (int z = centerZ - view; z <= centerZ + view; z++) {
                    refreshQueue.add(new RefreshTarget(world, x, z));
                }
            }
        }
        refreshTask = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int count = 0;
            long started = System.nanoTime();
            while (count++ < settings.refreshBatchSize && !refreshQueue.isEmpty()) {
                RefreshTarget target = refreshQueue.removeFirst();
                target.world().refreshChunk(target.x(), target.z());
                refreshedChunks++;
            }
            refreshNanos += System.nanoTime() - started;
            if (refreshQueue.isEmpty()) {
                refreshTask.cancel();
                refreshTask = null;
                plugin.getLogger().info("季节视觉刷新完成：区块 " + refreshedChunks + "，耗时 "
                        + (refreshNanos / 1_000_000) + "ms；发包成功 " + rewritten
                        + "，跳过 " + skipped + "，失败 " + failed);
                refreshedChunks = 0;
                refreshNanos = 0;
                rewritten = 0;
                skipped = 0;
                failed = 0;
            }
        }, 1L, 1L);
    }

    void close() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
        refreshQueue.clear();
        if (listener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(listener);
            listener = null;
        }
        available = false;
        biomeIds.clear();
    }

    private record RefreshTarget(World world, int x, int z) {
    }

    private static final class ByteReader {
        private final byte[] data;
        private int index;

        private ByteReader(byte[] data) {
            this.data = data;
        }

        private int remaining() {
            return data.length - index;
        }

        private int readUnsignedByte() throws IOException {
            if (index >= data.length) {
                throw new IOException("区块数据提前结束");
            }
            return data[index++] & 0xFF;
        }

        private int readVarInt() throws IOException {
            int result = 0;
            int shift = 0;
            while (true) {
                int value = readUnsignedByte();
                result |= (value & 0x7F) << shift;
                if ((value & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("区块数据 VarInt 过长");
                }
            }
        }

        private long readLong() throws IOException {
            long result = 0;
            for (int shift = 56; shift >= 0; shift -= 8) {
                result |= (long) readUnsignedByte() << shift;
            }
            return result;
        }
    }
}
