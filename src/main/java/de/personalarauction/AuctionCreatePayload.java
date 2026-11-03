package de.personalarauction;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record AuctionCreatePayload(String itemId, int startPrice, int durationMinutes) implements CustomPacketPayload {
    public static final Type<AuctionCreatePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "create_auction"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionCreatePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, AuctionCreatePayload::itemId,
                    ByteBufCodecs.VAR_INT, AuctionCreatePayload::startPrice,
                    ByteBufCodecs.VAR_INT, AuctionCreatePayload::durationMinutes,
                    AuctionCreatePayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
