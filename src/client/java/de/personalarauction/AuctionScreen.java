package de.personalarauction;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class AuctionScreen extends Screen {
    private static AuctionPayloads.Sync auctionState =
            new AuctionPayloads.Sync(false, "", 0, 0, 0L, false, "");

    private int selectedSlot;
    private EditBox priceInput;
    private EditBox durationInput;
    private String message = "Wähle einen Hotbar-Slot und erstelle deine Auktion.";

    public AuctionScreen() {
        super(Component.literal("PersonalAuction"));
        if (Minecraft.getInstance().player != null) {
            selectedSlot = Minecraft.getInstance().player.getInventory().getSelectedSlot();
        }
    }

    public static void update(AuctionPayloads.Sync state) {
        auctionState = state;
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int top = this.height / 2 - 90;

        this.addRenderableWidget(Button.builder(Component.literal("< Slot"), button -> {
            selectedSlot = (selectedSlot + 8) % 9;
        }).bounds(center - 115, top + 28, 55, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Slot >"), button -> {
            selectedSlot = (selectedSlot + 1) % 9;
        }).bounds(center + 60, top + 28, 55, 20).build());

        priceInput = new EditBox(this.font, center - 70, top + 62, 140, 20, Component.literal("Startpreis"));
        priceInput.setValue("100");
        priceInput.setMaxLength(10);
        this.addRenderableWidget(priceInput);

        durationInput = new EditBox(this.font, center - 70, top + 92, 140, 20, Component.literal("Dauer in Minuten"));
        durationInput.setValue("10");
        durationInput.setMaxLength(5);
        this.addRenderableWidget(durationInput);

        this.addRenderableWidget(Button.builder(Component.literal("Auktion starten"), button -> createAuction())
                .bounds(center - 70, top + 124, 140, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Schließen"), button -> onClose())
                .bounds(center - 70, top + 152, 140, 20).build());
    }

    private void createAuction() {
        try {
            int price = Integer.parseInt(priceInput.getValue());
            int duration = Integer.parseInt(durationInput.getValue());
            ClientPlayNetworking.send(new AuctionPayloads.Create(selectedSlot, price, duration));
            message = "Auktionsanfrage gesendet ...";
        } catch (NumberFormatException exception) {
            message = "Bitte gültige Zahlen für Preis und Dauer eingeben.";
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int center = this.width / 2;
        int top = this.height / 2 - 90;
        graphics.text(this.font, "Persönliche Auktion", center - 65, top - 12, 0xFFFFFFFF, true);

        ItemStack stack = Minecraft.getInstance().player == null
                ? ItemStack.EMPTY
                : Minecraft.getInstance().player.getInventory().getItem(selectedSlot);
        String selectedName = stack.isEmpty() ? "Leerer Slot" : stack.getCount() + "x " + stack.getHoverName().getString();
        graphics.text(this.font, "Ausgewähltes Item: " + selectedName, center - 110, top + 12, 0xFFFFFFFF, true);
        graphics.text(this.font, "Startpreis:", center - 70, top + 52, 0xFFAAAAAA, true);
        graphics.text(this.font, "Dauer (Minuten):", center - 70, top + 82, 0xFFAAAAAA, true);
        graphics.text(this.font, message, center - 110, top + 180, 0xFFFFFF55, true);

        if (auctionState.active()) {
            String status = auctionState.ended()
                    ? "Status: " + auctionState.result()
                    : "Restzeit: " + formatRemaining(auctionState.endAtMillis());
            graphics.text(this.font, "Aktuelle Auktion: " + auctionState.itemCount() + "x "
                    + auctionState.itemName() + " | Startpreis: " + auctionState.price(),
                    center - 110, top + 205, 0xFF55FF55, true);
            graphics.text(this.font, status, center - 110, top + 218, 0xFF55FF55, true);
        } else {
            graphics.text(this.font, "Aktuell läuft keine Auktion.", center - 110, top + 205, 0xFFAAAAAA, true);
        }
    }

    private static String formatRemaining(long endAtMillis) {
        long seconds = Math.max(0L, (endAtMillis - System.currentTimeMillis()) / 1000L);
        long minutes = seconds / 60L;
        long remainingSeconds = seconds % 60L;
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
