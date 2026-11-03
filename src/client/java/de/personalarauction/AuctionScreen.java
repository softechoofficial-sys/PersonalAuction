package de.personalarauction;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class AuctionScreen extends Screen {
    private EditBox priceField;
    private EditBox durationField;
    private Button endAuctionButton;
    private String errorMessage = "";
    private final List<Item> selectableItems = new ArrayList<>();
    private int selectedItemIndex = 0;
    private static String auctionStatus = "Auktionsstatus wird geladen...";
    private static boolean canCreate = false;
    private static String activeAuctionItemId = "";
    private static String paymentNotice = "";
    private static AuctionScreen currentScreen;
    private final List<AbstractWidget> creatorWidgets = new ArrayList<>();
    private int statusRequestTicks = 0;

    public AuctionScreen() {
        super(Component.literal("PersonalAuction"));
        currentScreen = this;
        BuiltInRegistries.ITEM.forEach(selectableItems::add);
        selectableItems.removeIf(item -> item == net.minecraft.world.item.Items.AIR);
    }

    public static void setPaymentNotice(String message) {
        paymentNotice = message;
    }

    public static void setAuctionStatus(String message, boolean permission, String itemId) {
        auctionStatus = message;
        canCreate = permission;
        activeAuctionItemId = itemId == null ? "" : itemId;
        if (currentScreen != null) currentScreen.updateCreatorVisibility();
    }

    private void updateCreatorVisibility() {
        for (AbstractWidget widget : creatorWidgets) {
            widget.visible = canCreate;
            widget.active = canCreate;
        }
        if (endAuctionButton != null) {
            boolean showEndButton = canCreate && !activeAuctionItemId.isEmpty();
            endAuctionButton.visible = showEndButton;
            endAuctionButton.active = showEndButton;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (this.minecraft.player != null && ++statusRequestTicks >= 20) {
            statusRequestTicks = 0;
            ClientPlayNetworking.send(new AuctionStatusRequestPayload());
        }
    }

    @Override
    protected void init() {
        creatorWidgets.clear();
        int center = this.width / 2;
        Button previous = Button.builder(Component.literal("<"), button -> changeSelectedItem(-1))
                .bounds(center - 100, this.height / 2 - 82, 20, 20).build();
        addRenderableWidget(previous);
        creatorWidgets.add(previous);
        Button next = Button.builder(Component.literal(">"), button -> changeSelectedItem(1))
                .bounds(center + 80, this.height / 2 - 82, 20, 20).build();
        addRenderableWidget(next);
        creatorWidgets.add(next);

        priceField = new EditBox(this.font, center - 100, this.height / 2 - 28, 200, 20, Component.literal("Startpreis"));
        priceField.setMaxLength(10);
        priceField.setValue("100");
        priceField.setHint(Component.literal("Startpreis"));
        addRenderableWidget(priceField);
        creatorWidgets.add(priceField);

        durationField = new EditBox(this.font, center - 100, this.height / 2 + 2, 200, 20, Component.literal("Dauer in Minuten"));
        durationField.setMaxLength(5);
        durationField.setValue("5");
        durationField.setHint(Component.literal("Dauer in Minuten"));
        addRenderableWidget(durationField);
        creatorWidgets.add(durationField);

        Button start = Button.builder(Component.literal("Auktion starten"), button -> startAuction())
                .bounds(center - 100, this.height / 2 + 34, 200, 20).build();
        addRenderableWidget(start);
        creatorWidgets.add(start);
        endAuctionButton = Button.builder(Component.literal("Auktion Beenden"), button -> {
                    ClientPlayNetworking.send(new AuctionEndPayload());
                    errorMessage = "Anfrage zum Beenden der Auktion gesendet.";
                })
                .bounds(center - 100, this.height / 2 + 60, 200, 20).build();
        addRenderableWidget(endAuctionButton);
        addRenderableWidget(Button.builder(Component.literal("Schließen"), button -> onClose())
                .bounds(center - 100, this.height / 2 + 86, 200, 20).build());
        updateCreatorVisibility();
        ClientPlayNetworking.send(new AuctionStatusRequestPayload());
    }

    @Override
    public void removed() {
        if (currentScreen == this) currentScreen = null;
        super.removed();
    }

    private void changeSelectedItem(int direction) {
        if (!selectableItems.isEmpty()) {
            selectedItemIndex = Math.floorMod(selectedItemIndex + direction, selectableItems.size());
        }
    }

    private void startAuction() {
        if (!canCreate) {
            errorMessage = "Keine Berechtigung zum Erstellen von Auktionen.";
            return;
        }
        try {
            int price = Integer.parseInt(priceField.getValue());
            int duration = Integer.parseInt(durationField.getValue());
            if (price < 1 || duration < 1 || duration > 10080 || selectableItems.isEmpty()) {
                throw new NumberFormatException();
            }
            Identifier itemId = BuiltInRegistries.ITEM.getKey(selectableItems.get(selectedItemIndex));
            ClientPlayNetworking.send(new AuctionCreatePayload(itemId.toString(), price, duration));
            errorMessage = "Auktionsanfrage gesendet. Serverbestätigung abwarten.";
        } catch (NumberFormatException exception) {
            errorMessage = "Ungültiger Startpreis, Dauer oder Item-Auswahl.";
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (!activeAuctionItemId.isEmpty()) {
            Identifier activeId = Identifier.tryParse(activeAuctionItemId);
            Item activeItem = activeId == null ? null
                    : BuiltInRegistries.ITEM.get(activeId).map(reference -> reference.value()).orElse(null);
            if (activeItem != null && activeItem != net.minecraft.world.item.Items.AIR) {
                graphics.item(activeItem.getDefaultInstance(), this.width / 2 - 120, this.height / 2 - 110);
            }
        }
        graphics.text(this.font, auctionStatus, this.width / 2 - 96, this.height / 2 - 105, 0xFFFFFFFF, true);
        if (canCreate) {
            if (!selectableItems.isEmpty()) {
                Item selected = selectableItems.get(selectedItemIndex);
                ItemStack preview = selected.getDefaultInstance();
                graphics.item(preview, this.width / 2 - 8, this.height / 2 - 82);
                graphics.text(this.font, preview.getHoverName().getString(),
                        this.width / 2 + 14, this.height / 2 - 76, 0xFFFFFFFF, true);
            }
            graphics.text(this.font, "Startpreis", this.width / 2 - 100, this.height / 2 - 40, 0xFFAAAAAA, true);
            graphics.text(this.font, "Dauer (Minuten)", this.width / 2 - 100, this.height / 2 - 10, 0xFFAAAAAA, true);
        }
        if (!paymentNotice.isEmpty()) {
            graphics.text(this.font, paymentNotice, this.width / 2 - 120, this.height / 2 + 108, 0xFF55FF55, true);
        }
        if (!errorMessage.isEmpty()) {
            graphics.text(this.font, errorMessage, this.width / 2 - 100, this.height / 2 + 88, 0xFFFF5555, true);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
