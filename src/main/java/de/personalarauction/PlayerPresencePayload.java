package de.personalarauction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PlayerPresencePayload(String playerId, String playerName, boolean online)
        implements CustomPacketPayload {
    public static final Type<PlayerPresencePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "player_presence"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerPresencePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, PlayerPresencePayload::playerId,
                    ByteBufCodecs.STRING_UTF8, PlayerPresencePayload::playerName,
                    ByteBufCodecs.BOOL, PlayerPresencePayload::online,
                    PlayerPresencePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
