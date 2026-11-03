package de.personalarauction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AuctionEndPayload() implements CustomPacketPayload {
    public static final Type<AuctionEndPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "end_auction"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionEndPayload> CODEC =
            StreamCodec.unit(new AuctionEndPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
