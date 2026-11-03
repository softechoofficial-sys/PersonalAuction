package de.personalarauction;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AuctionScreen extends Screen {
    private EditBox durationField;
    private EditBox minPriceField;
    private Button endAuctionButton;
    private Button itemPickerButton;
    private String errorMessage = "";
    private final List<Item> selectableItems = new ArrayList<>();
    private int selectedItemIndex = 0;
    private boolean hasSelectedItem = false;
    private static String activeItemId = "";
    private static String activeItemName = "";
    private static long auctionEndAt;
    private static long activeStartingPrice;
    private static String lastResult = "";
    private static final Map<String, Long> paymentTotals = new LinkedHashMap<>();
    private int paymentScroll = 0;
    private static Path saveFile;

    public AuctionScreen() {
        super(Component.literal("Auktion"));
        BuiltInRegistries.ITEM.forEach(selectableItems::add);
        selectableItems.removeIf(item -> item == net.minecraft.world.item.Items.AIR);
        loadState();
    }

    /**
     * Parses plain amounts and abbreviated Minecraft economy amounts:
     * k = thousand, m = million, b = billion, t = trillion.
     * Decimal abbreviations such as 1.5m and 1,5m are supported too.
     */
    private static long parseAmount(String input) {
        if (input == null) throw new NumberFormatException("Betrag fehlt");
        String value = input.trim().replace("$", "").replace("€", "").replaceAll("\\s+", "");
        Matcher matcher = Pattern.compile("([+-]?(?:[0-9]+(?:[.,][0-9]*)?|[.,][0-9]+))([kKmMbBtT]?)").matcher(value);
        if (!matcher.matches()) throw new NumberFormatException("Ungültiger Betrag");

        String number = matcher.group(1);
        String suffix = matcher.group(2).toLowerCase(java.util.Locale.ROOT);
        BigDecimal amount;
        if (!suffix.isEmpty()) {
            amount = new BigDecimal(number.replace(',', '.'));
            BigDecimal multiplier = switch (suffix) {
                case "k" -> BigDecimal.valueOf(1_000L);
                case "m" -> BigDecimal.valueOf(1_000_000L);
                case "b" -> BigDecimal.valueOf(1_000_000_000L);
                case "t" -> BigDecimal.valueOf(1_000_000_000_000L);
                default -> throw new NumberFormatException("Unbekannte Abkürzung");
            };
            amount = amount.multiply(multiplier);
        } else {
            // Preserve support for ordinary grouped values such as 1,000 or 1.000.
            amount = new BigDecimal(number.replace(",", "").replace(".", ""));
        }
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    public static void setPaymentNotice(String sender, String amountText) {
        if (!hasActiveAuction()) return;
        try {
            long amount = parseAmount(amountText);
            if (amount <= 0) return;
            paymentTotals.merge(sender, amount, Long::sum);
            saveState();
        } catch (NumberFormatException ignored) {
        }
    }

    private static List<Map.Entry<String, Long>> getRankedPayments() {
        return paymentTotals.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .toList();
    }

    private static String getCurrentWinner() {
        List<Map.Entry<String, Long>> ranked = getRankedPayments();
        return ranked.isEmpty() ? "..." : ranked.get(0).getKey();
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
        return "Aktuell: " + activeItemName + " | Restzeit: " + (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static void clearActiveAuction() {
        activeItemId = "";
        activeItemName = "";
        auctionEndAt = 0;
        activeStartingPrice = 0;
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
            auctionEndAt = Long.parseLong(properties.getProperty("auction.endAt", "0"));
            activeStartingPrice = Long.parseLong(properties.getProperty("auction.startingPrice", "0"));
            if (!activeItemId.isBlank()) {
                Identifier id = Identifier.tryParse(activeItemId);
                Item item = id == null ? null : BuiltInRegistries.ITEM.get(id)
                        .map(reference -> reference.value()).orElse(null);
                if (item == null || item == net.minecraft.world.item.Items.AIR
                        || auctionEndAt <= System.currentTimeMillis()) {
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
            properties.setProperty("auction.endAt", Long.toString(auctionEndAt));
            properties.setProperty("auction.startingPrice", Long.toString(activeStartingPrice));
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

    private String fitText(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (this.font.width(text) <= maxWidth) return text;
        String suffix = "...";
        int end = text.length();
        while (end > 0 && this.font.width(text.substring(0, end) + suffix) > maxWidth) end--;
        return end == 0 ? suffix : text.substring(0, end) + suffix;
    }

    private void drawItemSlot(GuiGraphicsExtractor graphics, Item item, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 31, y + 31, 0xFF8FC9EE);
        graphics.fill(x, y, x + 30, y + 30, 0xFF263746);
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            float scale = 2.3f;
            float textWidth = this.font.width("?") * scale;
            float textHeight = this.font.lineHeight * scale;
            graphics.pose().pushMatrix();
            graphics.pose().translate(x + (30.0f - textWidth) / 2.0f, y + (30.0f - textHeight) / 2.0f);
            graphics.pose().scale(scale, scale);
            graphics.text(this.font, "?", 0, 0, 0xFFB8E2FF, true);
            graphics.pose().popMatrix();
            return;
        }

        // Draw the 16x16 Minecraft item sprite at 1.5x (24x24), centered in the 30x30 slot.
        graphics.pose().pushMatrix();
        graphics.pose().translate(x + 3, y + 3);
        graphics.pose().scale(1.5f, 1.5f);
        graphics.item(item.getDefaultInstance(), 0, 0);
        graphics.pose().popMatrix();
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 210;
        int top = this.height / 2 - 125;
        itemPickerButton = Button.builder(getSelectedItemLabel(), button -> openItemPicker())
                .bounds(left + 16, top + 102, 190, 20).build();
        addRenderableWidget(itemPickerButton);
        updateItemPickerButton();

        minPriceField = new EditBox(this.font, left + 16, top + 153, 190, 20, Component.literal("Startpreis"));
        minPriceField.setMaxLength(12);
        minPriceField.setHint(Component.empty());
        addRenderableWidget(minPriceField);

        durationField = new EditBox(this.font, left + 16, top + 192, 190, 20, Component.literal("Dauer"));
        durationField.setMaxLength(5);
        durationField.setHint(Component.empty());
        addRenderableWidget(durationField);

        addRenderableWidget(Button.builder(Component.literal("Auktion starten"), button -> startAuction())
                .bounds(left + 12, top + 243, 96, 20).build());
        endAuctionButton = Button.builder(Component.literal("Auktion beenden"), button -> endAuction())
                .bounds(left + 112, top + 243, 96, 20).build();
        addRenderableWidget(endAuctionButton);
        updateEndButton();
    }

    private void updateItemPickerButton() {
        if (itemPickerButton != null) {
            itemPickerButton.active = !hasActiveAuction();
            itemPickerButton.setMessage(Component.literal("Item"));
        }
    }

    private void updateEndButton() {
        updateItemPickerButton();
        if (endAuctionButton != null) {
            endAuctionButton.visible = hasActiveAuction();
            endAuctionButton.active = hasActiveAuction();
        }
    }

    private Component getSelectedItemLabel() {
        return Component.literal("Item");
    }

    private void openItemPicker() {
        if (hasActiveAuction()) return;
        Minecraft.getInstance().setScreenAndShow(new ItemPickerScreen(this, selected -> {
            int index = selectableItems.indexOf(selected);
            if (hasActiveAuction()) return;
            if (index >= 0) {
                selectedItemIndex = index;
                hasSelectedItem = true;
            }
            updateItemPickerButton();
        }));
    }

    private void startAuction() {
        if (hasActiveAuction()) {
            errorMessage = "Es läuft bereits eine Auktion.";
            return;
        }
        try {
            int duration = Integer.parseInt(durationField.getValue());
            long minPrice = parseAmount(minPriceField.getValue().trim());
            if (duration < 1 || duration > 10080 || minPrice < 0 || selectableItems.isEmpty() || !hasSelectedItem) {
                throw new NumberFormatException();
            }
            Item selected = selectableItems.get(selectedItemIndex);
            activeItemId = BuiltInRegistries.ITEM.getKey(selected).toString();
            activeItemName = selected.getDefaultInstance().getHoverName().getString();
            auctionEndAt = System.currentTimeMillis() + duration * 60_000L;
            activeStartingPrice = minPrice;
            lastResult = "";
            paymentTotals.clear();
            paymentScroll = 0;
            errorMessage = "";
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
        errorMessage = "";
        updateEndButton();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        int left = this.width / 2 - 210;
        int top = this.height / 2 - 125;
        int right = left + 420;
        int bottom = top + 290;

        graphics.fill(left - 1, top - 1, right + 1, bottom + 1, 0xFF8FC9EE);
        graphics.fill(left, top, right, bottom, 0xF016202B);
        graphics.fill(left, top, right, top + 30, 0xFF304B63);
        graphics.fill(left, top + 29, right, top + 30, 0xFF8FC9EE);
        graphics.fill(left + 1, top + 31, left + 210, bottom - 1, 0xFF1B2936);
        graphics.fill(left + 211, top + 31, right - 1, bottom - 1, 0xFF202F3D);
        String ownerName = Minecraft.getInstance().getUser().getName();
        graphics.text(this.font, fitText(ownerName + " AUKTION", 300), left + 14, top + 11, 0xFFFFFFFF, true);
        graphics.text(this.font, hasActiveAuction() ? "LIVE" : "BEREIT",
                right - 48, top + 11, hasActiveAuction() ? 0xFF65E6A5 : 0xFFB8C3D1, true);

        graphics.text(this.font, "AUKTION", left + 16, top + 43, 0xFFAEDFFF, true);
        if (hasActiveAuction()) {
            Identifier id = Identifier.tryParse(activeItemId);
            Item item = id == null ? null : BuiltInRegistries.ITEM.get(id)
                    .map(reference -> reference.value()).orElse(null);
            drawItemSlot(graphics, item, left + 18, top + 61);
            graphics.text(this.font, fitText(activeItemName, 140), left + 62, top + 62, 0xFFFFFFFF, true);
            long seconds = Math.max(0, (auctionEndAt - System.currentTimeMillis()) / 1000);
            graphics.text(this.font, "Restzeit: " + (seconds / 60) + "m " + (seconds % 60) + "s",
                    left + 62, top + 78, 0xFFB8C3D1, true);
            graphics.text(this.font, "Startpreis: $" + activeStartingPrice,
                    left + 62, top + 94, 0xFFFFD479, true);
        } else {
            Item selectedItem = hasSelectedItem && !selectableItems.isEmpty()
                    ? selectableItems.get(Math.max(0, Math.min(selectedItemIndex, selectableItems.size() - 1)))
                    : null;
            drawItemSlot(graphics, selectedItem, left + 18, top + 61);
            if (selectedItem != null) {
                graphics.text(this.font, "Ausgewähltes Item", left + 62, top + 62, 0xFF91A9C5, true);
                graphics.text(this.font, fitText(selectedItem.getDefaultInstance().getHoverName().getString(), 140),
                        left + 62, top + 78, 0xFFFFFFFF, true);
            }
            if (!lastResult.isBlank()) {
                graphics.text(this.font, fitText(lastResult, 180), left + 16, top + 103, 0xFFCED7E2, true);
            }
        }

        graphics.fill(left + 14, top + 128, left + 206, top + 130, 0xFF58758F);
        graphics.text(this.font, "Startpreis ($)", left + 16, top + 141, 0xFFB8C3D1, true);
        graphics.text(this.font, "Dauer (Min)", left + 16, top + 180, 0xFFB8C3D1, true);

        graphics.text(this.font, "ZAHLUNGEN", left + 224, top + 43, 0xFF91A9C5, true);
        graphics.text(this.font, "GEWINNER", left + 224, top + 64, 0xFF91A9C5, true);
        graphics.text(this.font, fitText(getCurrentWinner(), 180), left + 224, top + 78, 0xFF65E6A5, true);
        graphics.text(this.font, "Spieler", left + 224, top + 101, 0xFFB8C3D1, true);
        graphics.text(this.font, "Gesamt", right - 68, top + 101, 0xFFB8C3D1, true);
        graphics.fill(left + 222, top + 114, right - 14, top + 115, 0xFF3C5068);

        List<Map.Entry<String, Long>> ranked = getRankedPayments();
        int visibleRows = 8;
        int maxScroll = Math.max(0, ranked.size() - visibleRows);
        paymentScroll = Math.max(0, Math.min(paymentScroll, maxScroll));
        for (int i = paymentScroll; i < Math.min(ranked.size(), paymentScroll + visibleRows); i++) {
            Map.Entry<String, Long> entry = ranked.get(i);
            int rowY = top + 123 + (i - paymentScroll) * 15;
            if (i == 0) graphics.fill(left + 220, rowY - 3, right - 14, rowY + 12, 0xFF29483F);
            graphics.text(this.font, fitText((i + 1) + ". " + entry.getKey(), 112), left + 224, rowY,
                    i == 0 ? 0xFF65E6A5 : 0xFFE0E6ED, true);
            String total = fitText("$" + entry.getValue(), 66);
            graphics.text(this.font, total, right - 16 - this.font.width(total), rowY,
                    0xFFFFD479, true);
        }
        if (ranked.isEmpty()) {
            graphics.text(this.font, "...", left + 224, top + 126, 0xFF8795A7, true);
        } else if (ranked.size() > visibleRows) {
            graphics.text(this.font, "Mausrad: Liste scrollen (" + ranked.size() + " Spieler)",
                    left + 224, bottom - 18, 0xFF8795A7, true);
        }
        if (!errorMessage.isEmpty()) {
            graphics.text(this.font, fitText(errorMessage, 180), left + 16, bottom - 16, 0xFFFFB86B, true);
        }
        updateEndButton();
        // Widgets zuletzt zeichnen, damit Buttons und Eingabefelder sichtbar und anklickbar bleiben.
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int left = this.width / 2 - 210;
        int top = this.height / 2 - 125;
        if (mouseX >= left + 215 && mouseX <= left + 415 && mouseY >= top + 116 && mouseY <= top + 245) {
            int maxScroll = Math.max(0, getRankedPayments().size() - 8);
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
