package de.personalarauction;

import net.fabricmc.loader.api.FabricLoader;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class AuctionScreen extends Screen {
    private EditBox priceField;
    private EditBox durationField;
    private Button endAuctionButton;
    private String errorMessage = "";
    private final List<Item> selectableItems = new ArrayList<>();
    private int selectedItemIndex = 0;
    private static String activeItemId = "";
    private static String activeItemName = "";
    private static int activeStartPrice;
    private static long auctionEndAt;
    private static String lastResult = "";
    private static String paymentNotice = "";
    private static Path saveFile;

    public AuctionScreen() {
        super(Component.literal("PersonalAuction"));
        BuiltInRegistries.ITEM.forEach(selectableItems::add);
        selectableItems.removeIf(item -> item == net.minecraft.world.item.Items.AIR);
        loadState();
    }

    public static void setPaymentNotice(String message) {
        paymentNotice = message;
    }

    public static void tickAuction() {
        if (!activeItemId.isEmpty() && auctionEndAt > 0 && System.currentTimeMillis() >= auctionEndAt) {
            lastResult = "Auktion beendet: " + activeItemName + " | Kein Verkauf (keine Gebote).";
            clearActiveAuction();
            saveState();
        }
    }

    private static boolean hasActiveAuction() {
        return !activeItemId.isEmpty() && auctionEndAt > System.currentTimeMillis();
    }

    private static String getStatus() {
        if (!hasActiveAuction()) return lastResult.isBlank() ? "Keine aktive Auktion." : lastResult;
        long seconds = Math.max(0, (auctionEndAt - System.currentTimeMillis()) / 1000);
        return "Aktuell: " + activeItemName + " | Startpreis: " + activeStartPrice
                + " Cash | Restzeit: " + (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static void clearActiveAuction() {
        activeItemId = "";
        activeItemName = "";
        activeStartPrice = 0;
        auctionEndAt = 0;
    }

    private static Path getSaveFile() {
        if (saveFile == null) saveFile = FabricLoader.getInstance().getConfigDir().resolve("personalauction.properties");
        return saveFile;
    }

    private static void loadState() {
        Path file = getSaveFile();
        if (!Files.exists(file)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            lastResult = properties.getProperty("auction.lastResult", "");
            activeItemId = properties.getProperty("auction.item", "");
            activeItemName = properties.getProperty("auction.itemName", "");
            activeStartPrice = Integer.parseInt(properties.getProperty("auction.price", "0"));
            auctionEndAt = Long.parseLong(properties.getProperty("auction.endAt", "0"));
            if (!activeItemId.isBlank()) {
                Identifier id = Identifier.tryParse(activeItemId);
                Item item = id == null ? null : BuiltInRegistries.ITEM.get(id)
                        .map(reference -> reference.value()).orElse(null);
                if (item == null || item == net.minecraft.world.item.Items.AIR
                        || activeStartPrice < 1 || auctionEndAt <= System.currentTimeMillis()) {
                    clearActiveAuction();
                    if (auctionEndAt > 0 && auctionEndAt <= System.currentTimeMillis()) {
                        lastResult = "Auktion beendet: " + activeItemName + " | Kein Verkauf (keine Gebote).";
                    }
                    saveState();
                } else if (activeItemName.isBlank()) {
                    activeItemName = item.getDefaultInstance().getHoverName().getString();
                }
            }
        } catch (IOException | NumberFormatException exception) {
            System.err.println("[PersonalAuction] Lokaler Auktionsstand konnte nicht geladen werden: " + exception.getMessage());
        }
    }

    private static void saveState() {
        Properties properties = new Properties();
        if (!lastResult.isBlank()) properties.setProperty("auction.lastResult", lastResult);
        if (!activeItemId.isBlank()) {
            properties.setProperty("auction.item", activeItemId);
            properties.setProperty("auction.itemName", activeItemName);
            properties.setProperty("auction.price", Integer.toString(activeStartPrice));
            properties.setProperty("auction.endAt", Long.toString(auctionEndAt));
        }
        Path file = getSaveFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "PersonalAuction local client state");
            }
        } catch (IOException exception) {
            System.err.println("[PersonalAuction] Lokaler Auktionsstand konnte nicht gespeichert werden: " + exception.getMessage());
        }
    }

    @Override
    public void tick() {
        super.tick();
        tickAuction();
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        addRenderableWidget(Button.builder(Component.literal("<"), button -> changeSelectedItem(-1))
                .bounds(center - 100, this.height / 2 - 82, 20, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> changeSelectedItem(1))
                .bounds(center + 80, this.height / 2 - 82, 20, 20).build());

        priceField = new EditBox(this.font, center - 100, this.height / 2 - 28, 200, 20, Component.literal("Startpreis"));
        priceField.setMaxLength(10);
        priceField.setValue("100");
        priceField.setHint(Component.literal("Startpreis in Cash"));
        addRenderableWidget(priceField);

        durationField = new EditBox(this.font, center - 100, this.height / 2 + 2, 200, 20, Component.literal("Dauer"));
        durationField.setMaxLength(5);
        durationField.setValue("5");
        durationField.setHint(Component.literal("Dauer in Minuten"));
        addRenderableWidget(durationField);

        addRenderableWidget(Button.builder(Component.literal("Auktion starten"), button -> startAuction())
                .bounds(center - 100, this.height / 2 + 34, 200, 20).build());
        endAuctionButton = Button.builder(Component.literal("Auktion beenden"), button -> endAuction())
                .bounds(center - 100, this.height / 2 + 60, 200, 20).build();
        addRenderableWidget(endAuctionButton);
        addRenderableWidget(Button.builder(Component.literal("Schließen"), button -> onClose())
                .bounds(center - 100, this.height / 2 + 86, 200, 20).build());
        updateEndButton();
    }

    private void updateEndButton() {
        if (endAuctionButton != null) {
            endAuctionButton.visible = hasActiveAuction();
            endAuctionButton.active = hasActiveAuction();
        }
    }

    private void changeSelectedItem(int direction) {
        if (!selectableItems.isEmpty()) {
            selectedItemIndex = Math.floorMod(selectedItemIndex + direction, selectableItems.size());
        }
    }

    private void startAuction() {
        if (hasActiveAuction()) {
            errorMessage = "Es läuft bereits eine Auktion.";
            return;
        }
        try {
            int price = Integer.parseInt(priceField.getValue());
            int duration = Integer.parseInt(durationField.getValue());
            if (price < 1 || price > 1_000_000_000 || duration < 1 || duration > 10080 || selectableItems.isEmpty()) {
                throw new NumberFormatException();
            }
            Item selected = selectableItems.get(selectedItemIndex);
            activeItemId = BuiltInRegistries.ITEM.getKey(selected).toString();
            activeItemName = selected.getDefaultInstance().getHoverName().getString();
            activeStartPrice = price;
            auctionEndAt = System.currentTimeMillis() + duration * 60_000L;
            lastResult = "";
            errorMessage = "Auktion lokal gestartet.";
            saveState();
            updateEndButton();
        } catch (NumberFormatException exception) {
            errorMessage = "Ungültiger Startpreis, Dauer oder Item-Auswahl.";
        }
    }

    private void endAuction() {
        if (!hasActiveAuction()) return;
        lastResult = "Auktion vorzeitig beendet: " + activeItemName + " | Kein Verkauf (keine Gebote).";
        clearActiveAuction();
        saveState();
        errorMessage = "Auktion lokal beendet.";
        updateEndButton();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (hasActiveAuction()) {
            Identifier id = Identifier.tryParse(activeItemId);
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id)
                    .map(reference -> reference.value()).orElse(null);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                graphics.item(item.getDefaultInstance(), this.width / 2 - 120, this.height / 2 - 110);
            }
        }
        graphics.text(this.font, getStatus(), this.width / 2 - 120, this.height / 2 - 105, 0xFFFFFFFF, true);
        if (!selectableItems.isEmpty()) {
            ItemStack preview = selectableItems.get(selectedItemIndex).getDefaultInstance();
            graphics.item(preview, this.width / 2 - 8, this.height / 2 - 82);
            graphics.text(this.font, preview.getHoverName().getString(),
                    this.width / 2 + 14, this.height / 2 - 76, 0xFFFFFFFF, true);
        }
        graphics.text(this.font, "Startpreis (Cash)", this.width / 2 - 100, this.height / 2 - 40, 0xFFAAAAAA, true);
        graphics.text(this.font, "Dauer (Minuten)", this.width / 2 - 100, this.height / 2 - 10, 0xFFAAAAAA, true);
        if (!paymentNotice.isEmpty()) {
            graphics.text(this.font, paymentNotice, this.width / 2 - 120, this.height / 2 + 108, 0xFF55FF55, true);
        }
        if (!errorMessage.isEmpty()) {
            graphics.text(this.font, errorMessage, this.width / 2 - 120, this.height / 2 + 130, 0xFFFFAA55, true);
        }
        updateEndButton();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}