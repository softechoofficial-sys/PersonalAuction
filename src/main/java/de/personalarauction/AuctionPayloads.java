package de.personalarauction;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class AuctionPayloads {
    private AuctionPayloads() {}

    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC =
                StreamCodec.unit(new Request());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Create(int slot, int startPrice, int durationMinutes) implements CustomPacketPayload {
        public static final Type<Create> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "create"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Create> CODEC =
                StreamCodec.composite(ByteBufCodecs.VAR_INT, Create::slot,
                        ByteBufCodecs.VAR_INT, Create::startPrice,
                        ByteBufCodecs.VAR_INT, Create::durationMinutes,
                        Create::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record Sync(boolean active, String itemName, int itemCount, int price,
                       long endAtMillis, boolean ended, String result) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(Identifier.fromNamespaceAndPath(PersonalAuction.MOD_ID, "sync"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, Sync::active,
                        ByteBufCodecs.STRING_UTF8, Sync::itemName,
                        ByteBufCodecs.VAR_INT, Sync::itemCount,
                        ByteBufCodecs.VAR_INT, Sync::price,
                        ByteBufCodecs.VAR_LONG, Sync::endAtMillis,
                        ByteBufCodecs.BOOL, Sync::ended,
                        ByteBufCodecs.STRING_UTF8, Sync::result,
                        Sync::new);
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
