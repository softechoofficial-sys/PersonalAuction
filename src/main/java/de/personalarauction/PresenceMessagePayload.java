package de.personalarauction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record PresenceMessagePayload(String senderId, String recipientId, String ciphertext)
        implements CustomPacketPayload {
    public static final Type<PresenceMessagePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "presence_message"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PresenceMessagePayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PresenceMessagePayload::senderId,
                    ByteBufCodecs.STRING_UTF8, PresenceMessagePayload::recipientId,
                    ByteBufCodecs.STRING_UTF8, PresenceMessagePayload::ciphertext,
                    PresenceMessagePayload::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
