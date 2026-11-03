package de.personalarauction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AuctionStatusRequestPayload() implements CustomPacketPayload {
    public static final Type<AuctionStatusRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "auction_status_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionStatusRequestPayload> CODEC =
            StreamCodec.unit(new AuctionStatusRequestPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
