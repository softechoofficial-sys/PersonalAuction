package de.personalarauction;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AuctionScreen extends Screen {
    private EditBox priceField;
    private EditBox durationField;

    public AuctionScreen() {
        super(Component.literal("PersonalAuction"));
    }

    @Override
    protected void init() {
        int center = this.width / 2;
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

    private void startAuction() {
        try {
            int price = Integer.parseInt(priceField.getValue());
            int duration = Integer.parseInt(durationField.getValue());
            if (price < 1 || duration < 1 || duration > 10080) {
                throw new NumberFormatException();
            }
            ClientPlayNetworking.send(new AuctionCreatePayload(price, duration));
            onClose();
        } catch (NumberFormatException exception) {
            this.minecraft.gui.getChat().addClientSystemMessage(
                    Component.literal("Bitte gib einen Startpreis ab 1 und eine Dauer von 1 bis 10080 Minuten ein.")
            );
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.text(this.font, "Halte das Item in deiner Haupthand.", this.width / 2 - 100,
                this.height / 2 - 55, 0xFFFFFFFF, true);
        graphics.text(this.font, "Startpreis", this.width / 2 - 100, this.height / 2 - 40,
                0xFFAAAAAA, true);
        graphics.text(this.font, "Dauer (Minuten)", this.width / 2 - 100, this.height / 2 - 10,
                0xFFAAAAAA, true);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
