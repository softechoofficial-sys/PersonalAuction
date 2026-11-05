package de.personalarauction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresenceKeyPayload(String playerId, String publicKey) implements CustomPacketPayload {
    public static final Type<PresenceKeyPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "presence_key"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresenceKeyPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PresenceKeyPayload::playerId,
                    ByteBufCodecs.STRING_UTF8, PresenceKeyPayload::publicKey, PresenceKeyPayload::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
