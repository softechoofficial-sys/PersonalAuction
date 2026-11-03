package de.personalarauction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AuctionStatusPayload(String message) implements CustomPacketPayload {
    public static final Type<AuctionStatusPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "auction_status"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionStatusPayload> CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, AuctionStatusPayload::message,
                    AuctionStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
