package net.minestom.scratch.registry;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.SendablePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.TagsPacket;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.Registries;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;

import java.util.List;

public final class ScratchRegistryTools {

    private static final Registries REGISTRIES = Registries.vanilla();

    public static final DynamicRegistry<Biome> BIOME = REGISTRIES.biome();
    public static final DynamicRegistry<DimensionType> DIMENSION_TYPE = REGISTRIES.dimensionType();

    public static final List<ServerPacket> REGISTRY_PACKETS = Registries.registryDataPackets(REGISTRIES, false).stream()
            .map(packet -> SendablePacket.extractServerPacket(ConnectionState.CONFIGURATION, packet))
            .toList();
    public static final TagsPacket TAGS_PACKET = Registries.tagsPacket(REGISTRIES);

    public static Registries registries() {
        return REGISTRIES;
    }
}
