package de.personalarauction;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class AuctionScreen extends Screen {
    private EditBox priceField;
    private EditBox durationField;
    private String errorMessage = "";
    private int selectedSlot = 0;
    private static String auctionStatus = "Auktionsstatus wird geladen...";
    private int statusRequestTicks = 0;

    public static void setAuctionStatus(String message) {
        auctionStatus = message;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft.player != null && ++statusRequestTicks >= 20) {
            statusRequestTicks = 0;
            ClientPlayNetworking.send(new AuctionStatusRequestPayload());
        }
    }

    public AuctionScreen() {
        super(Component.literal("PersonalAuction"));
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("<"), button -> changeSelectedSlot(-1))
                .bounds(center - 100, this.height / 2 - 82, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> changeSelectedSlot(1))
                .bounds(center + 80, this.height / 2 - 82, 20, 20).build());

        priceField = new EditBox(this.font, center - 100, this.height / 2 - 28, 200, 20,
                Component.literal("Startpreis"));
        priceField.setMaxLength(10);
        priceField.setValue("100");
        priceField.setHint(Component.literal("Startpreis"));
        addRenderableWidget(priceField);

        durationField = new EditBox(this.font, center - 100, this.height / 2 + 2, 200, 20,
                Component.literal("Dauer in Minuten"));
        durationField.setMaxLength(5);
        durationField.setValue("5");
        durationField.setHint(Component.literal("Dauer in Minuten"));
        addRenderableWidget(durationField);

        addRenderableWidget(Button.builder(Component.literal("Auktion starten"), button -> startAuction())
                .bounds(center - 100, this.height / 2 + 34, 200, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Schließen"), button -> onClose())
                .bounds(center - 100, this.height / 2 + 60, 200, 20).build());
    }

    private void changeSelectedSlot(int direction) {
        selectedSlot = (selectedSlot + direction + 36) % 36;
    }

    private void startAuction() {
        try {
            int price = Integer.parseInt(priceField.getValue());
            int duration = Integer.parseInt(durationField.getValue());
            if (price < 1 || duration < 1 || duration > 10080) {
                throw new NumberFormatException();
            }
            ClientPlayNetworking.send(new AuctionCreatePayload(selectedSlot, price, duration));
            onClose();
        } catch (NumberFormatException exception) {
            errorMessage = "Ungültiger Startpreis oder ungültige Dauer (1 bis 10080 Minuten).";
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        ItemStack selectedItem = this.minecraft.player.getInventory().getItem(selectedSlot);
        String itemName = selectedItem.isEmpty() ? "Leer" : selectedItem.getHoverName().getString()
                + " x" + selectedItem.getCount();
        graphics.text(this.font, auctionStatus, this.width / 2 - 120, this.height / 2 - 105,\n                0xFFFFFFFF, true);\n        graphics.text(this.font, "Item (Slot " + selectedSlot + "): " + itemName, this.width / 2 - 75,
                this.height / 2 - 76, 0xFFFFFFFF, true);
        graphics.text(this.font, "Startpreis", this.width / 2 - 100, this.height / 2 - 40,
                0xFFAAAAAA, true);
        graphics.text(this.font, "Dauer (Minuten)", this.width / 2 - 100, this.height / 2 - 10,
                0xFFAAAAAA, true);
        if (!errorMessage.isEmpty()) {
            graphics.text(this.font, errorMessage, this.width / 2 - 100, this.height / 2 + 88,
                    0xFFFF5555, true);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
