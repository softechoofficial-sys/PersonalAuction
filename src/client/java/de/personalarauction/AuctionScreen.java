package de.personalarauction;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
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
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.Properties;

public class AuctionScreen extends Screen {
    private EditBox priceField;
    private EditBox durationField;
    private Button endAuctionButton;
    private Button itemPickerButton;
    private String errorMessage = "";
    private final List<Item> selectableItems = new ArrayList<>();
    private int selectedItemIndex = 0;
    private static String activeItemId = "";
    private static String activeItemName = "";
    private static int activeStartPrice;
    private static long auctionEndAt;
    private static String lastResult = "";
    private static String paymentNotice = "";
    private static final Map<String, Long> paymentTotals = new LinkedHashMap<>();
    private int paymentScroll = 0;
    private static Path saveFile;

    public AuctionScreen() {
        super(Component.literal("PersonalAuction"));
        BuiltInRegistries.ITEM.forEach(selectableItems::add);
        selectableItems.removeIf(item -> item == net.minecraft.world.item.Items.AIR);
        loadState();
    }

    public static void setPaymentNotice(String sender, String amountText) {
        if (!hasActiveAuction()) return;
        try {
            long amount = Long.parseLong(amountText.replaceAll("[^0-9]", ""));
            if (amount <= 0) return;
            paymentTotals.merge(sender, amount, Long::sum);
            paymentNotice = "Zahlung von " + sender + " erkannt: +" + amount + " Cash";
            saveState();
        } catch (NumberFormatException ignored) {
            paymentNotice = "Zahlung erkannt, Betrag konnte nicht gelesen werden.";
        }
    }

    private static List<Map.Entry<String, Long>> getRankedPayments() {
        return paymentTotals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .toList();
    }

    private static String getCurrentWinner() {
        List<Map.Entry<String, Long>> ranked = getRankedPayments();
        return ranked.isEmpty() ? "Noch keine Zahlungen" :
                ranked.get(0).getKey() + " – " + ranked.get(0).getValue() + " Cash";
    }

    public static void tickAuction() {
        if (!activeItemId.isEmpty() && auctionEndAt > 0 && System.currentTimeMillis() >= auctionEndAt) {
            lastResult = paymentTotals.isEmpty()
                    ? "Auktion beendet: " + activeItemName + " | Kein Verkauf."
                    : "Auktion beendet: " + activeItemName + " | Gewinner: " + getCurrentWinner();
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
            paymentTotals.clear();
            int paymentCount = Integer.parseInt(properties.getProperty("auction.paymentCount", "0"));
            for (int i = 0; i < paymentCount; i++) {
                String player = properties.getProperty("auction.payment." + i + ".player");
                long total = Long.parseLong(properties.getProperty("auction.payment." + i + ".total", "0"));
                if (player != null && total > 0) paymentTotals.put(player, total);
            }
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
        properties.setProperty("auction.paymentCount", Integer.toString(paymentTotals.size()));
        int paymentIndex = 0;
        for (Map.Entry<String, Long> entry : paymentTotals.entrySet()) {
            properties.setProperty("auction.payment." + paymentIndex + ".player", entry.getKey());
            properties.setProperty("auction.payment." + paymentIndex + ".total", Long.toString(entry.getValue()));
            paymentIndex++;
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
        int left = this.width / 2 - 210;
        int top = this.height / 2 - 125;
        itemPickerButton = Button.builder(getSelectedItemLabel(), button -> openItemPicker())
                .bounds(left + 16, top + 102, 190, 20).build();
        addRenderableWidget(itemPickerButton);

        priceField = new EditBox(this.font, left + 16, top + 178, 190, 20, Component.literal("Startpreis"));
        priceField.setMaxLength(10);
        priceField.setValue("100");
        priceField.setHint(Component.literal("Startpreis in Cash"));
        addRenderableWidget(priceField);

        durationField = new EditBox(this.font, left + 16, top + 213, 190, 20, Component.literal("Dauer"));
        durationField.setMaxLength(5);
        durationField.setValue("5");
        durationField.setHint(Component.literal("Dauer in Minuten"));
        addRenderableWidget(durationField);

        addRenderableWidget(Button.builder(Component.literal("Auktion starten"), button -> startAuction())
                .bounds(left + 16, top + 239, 92, 20).build());
        endAuctionButton = Button.builder(Component.literal("Auktion beenden"), button -> endAuction())
                .bounds(left + 114, top + 239, 92, 20).build();
        addRenderableWidget(endAuctionButton);
        updateEndButton();
    }

    private void updateEndButton() {
        if (endAuctionButton != null) {
            endAuctionButton.visible = hasActiveAuction();
            endAuctionButton.active = hasActiveAuction();
        }
    }

    private Component getSelectedItemLabel() {
        if (selectableItems.isEmpty()) return Component.literal("Kein Item verfügbar");
        return Component.literal("Item auswählen: " + selectableItems.get(selectedItemIndex)
                .getDefaultInstance().getHoverName().getString());
    }

    private void openItemPicker() {
        Minecraft.getInstance().setScreenAndShow(new ItemPickerScreen(this, selected -> {
            int index = selectableItems.indexOf(selected);
            if (index >= 0) selectedItemIndex = index;
            if (itemPickerButton != null) itemPickerButton.setMessage(getSelectedItemLabel());
        }));
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
            paymentTotals.clear();
            paymentScroll = 0;
            paymentNotice = "";
            errorMessage = "Auktion lokal gestartet.";
            saveState();
            updateEndButton();
        } catch (NumberFormatException exception) {
            errorMessage = "Ungültiger Startpreis, Dauer oder Item-Auswahl.";
        }
    }

    private void endAuction() {
        if (!hasActiveAuction()) return;
        lastResult = paymentTotals.isEmpty()
                ? "Auktion vorzeitig beendet: " + activeItemName + " | Kein Verkauf."
                : "Auktion vorzeitig beendet: " + activeItemName + " | Gewinner: " + getCurrentWinner();
        clearActiveAuction();
        saveState();
        errorMessage = "Auktion lokal beendet.";
        updateEndButton();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        int left = this.width / 2 - 210;
        int top = this.height / 2 - 125;
        int right = left + 420;
        int bottom = top + 270;

        graphics.fill(left, top, right, bottom, 0xF0141822);
        graphics.fill(left, top, right, top + 30, 0xFF27364A);
        graphics.fill(left + 1, top + 31, left + 210, bottom - 1, 0xFF1B2432);
        graphics.fill(left + 211, top + 31, right - 1, bottom - 1, 0xFF202B3A);
        graphics.text(this.font, "PERSONAL AUCTION", left + 14, top + 11, 0xFFFFFFFF, true);
        graphics.text(this.font, hasActiveAuction() ? "LIVE" : "BEREIT",
                right - 48, top + 11, hasActiveAuction() ? 0xFF65E6A5 : 0xFFB8C3D1, true);

        graphics.text(this.font, "AUKTION", left + 16, top + 43, 0xFF91A9C5, true);
        if (hasActiveAuction()) {
            Identifier id = Identifier.tryParse(activeItemId);
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id)
                    .map(reference -> reference.value()).orElse(null);
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                graphics.fill(left + 16, top + 58, left + 54, top + 96, 0xFF303E50);
                graphics.item(item.getDefaultInstance(), left + 27, top + 69);
            }
            graphics.text(this.font, activeItemName, left + 62, top + 62, 0xFFFFFFFF, true);
            long seconds = Math.max(0, (auctionEndAt - System.currentTimeMillis()) / 1000);
            graphics.text(this.font, "Restzeit: " + (seconds / 60) + "m " + (seconds % 60) + "s",
                    left + 62, top + 78, 0xFFB8C3D1, true);
            graphics.text(this.font, "Startpreis: " + activeStartPrice + " Cash",
                    left + 16, top + 91, 0xFFFFD479, true);
        } else {
            graphics.text(this.font, lastResult.isBlank() ? "Keine aktive Auktion" : lastResult,
                    left + 16, top + 66, 0xFFCED7E2, true);
        }

        graphics.fill(left + 14, top + 128, left + 206, top + 130, 0xFF3C5068);
        graphics.text(this.font, "AKTUELLER GEWINNER", left + 16, top + 126, 0xFF91A9C5, true);
        graphics.text(this.font, getCurrentWinner(), left + 16, top + 143, 0xFF65E6A5, true);
        graphics.text(this.font, "Item bleibt im Inventar", left + 16, top + 159, 0xFF8795A7, true);
        graphics.text(this.font, "Startpreis (Cash)", left + 16, top + 166, 0xFFB8C3D1, true);
        graphics.text(this.font, "Dauer (Min.)", left + 16, top + 201, 0xFFB8C3D1, true);

        graphics.text(this.font, "ZAHLUNGEN & TEILNEHMER", left + 224, top + 43, 0xFF91A9C5, true);
        graphics.text(this.font, "Spieler", left + 224, top + 64, 0xFFB8C3D1, true);
        graphics.text(this.font, "Gesamt", right - 68, top + 64, 0xFFB8C3D1, true);
        graphics.fill(left + 222, top + 78, right - 14, top + 79, 0xFF3C5068);

        List<Map.Entry<String, Long>> ranked = getRankedPayments();
        int visibleRows = 9;
        int maxScroll = Math.max(0, ranked.size() - visibleRows);
        paymentScroll = Math.max(0, Math.min(paymentScroll, maxScroll));
        for (int i = paymentScroll; i < Math.min(ranked.size(), paymentScroll + visibleRows); i++) {
            Map.Entry<String, Long> entry = ranked.get(i);
            int rowY = top + 88 + (i - paymentScroll) * 16;
            if (i == 0) graphics.fill(left + 220, rowY - 3, right - 14, rowY + 12, 0xFF29483F);
            graphics.text(this.font, (i + 1) + ". " + entry.getKey(), left + 224, rowY,
                    i == 0 ? 0xFF65E6A5 : 0xFFE0E6ED, true);
            String total = entry.getValue() + " Cash";
            graphics.text(this.font, total, right - 16 - this.font.width(total), rowY,
                    0xFFFFD479, true);
        }
        if (ranked.isEmpty()) {
            graphics.text(this.font, "Noch keine Zahlungen erkannt.", left + 224, top + 91, 0xFF8795A7, true);
        } else if (ranked.size() > visibleRows) {
            graphics.text(this.font, "Mausrad: Liste scrollen (" + ranked.size() + " Spieler)",
                    left + 224, bottom - 18, 0xFF8795A7, true);
        }
        if (!paymentNotice.isEmpty()) {
            graphics.text(this.font, paymentNotice, left + 224, top + 238, 0xFF65E6A5, true);
        }
        if (!errorMessage.isEmpty()) {
            graphics.text(this.font, errorMessage, left + 16, bottom - 16, 0xFFFFB86B, true);
        }
        updateEndButton();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int left = this.width / 2 - 210;
        int top = this.height / 2 - 125;
        if (mouseX >= left + 215 && mouseX <= left + 415 && mouseY >= top + 80 && mouseY <= top + 245) {
            int maxScroll = Math.max(0, getRankedPayments().size() - 9);
            paymentScroll = Math.max(0, Math.min(maxScroll, paymentScroll - (int) Math.signum(verticalAmount)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}