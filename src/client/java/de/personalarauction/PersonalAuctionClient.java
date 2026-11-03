package de.personalarauction;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

public class PersonalAuctionClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("auction").executes(context -> {
                    Minecraft.getInstance().gui.setScreen(new AuctionScreen());
                    ClientPlayNetworking.send(new AuctionPayloads.Request());
                    return 1;
                })));

        ClientPlayNetworking.registerGlobalReceiver(AuctionPayloads.Sync.TYPE, (payload, context) ->
                context.client().execute(() -> AuctionScreen.update(payload)));
    }
}
