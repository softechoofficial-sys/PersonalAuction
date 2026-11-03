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
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
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
    private Button startAuctionButton;
    private Button itemPickerButton;
    private String errorMessage = "";
    private final List<Item> selectableItems = new ArrayList<>();
    private int selectedItemIndex = 0;
    private boolean hasSelectedItem = false;
    private ItemStack selectedItemStack;
    private static ItemStack activeItemStack;
    private static String activeItemId = "";
    private static String activeItemName = "";
    private static long auctionEndAt;
    private static long activeStartingPrice;
    private static String activeStartingPriceDisplay = "";
    private static String lastResult = "";
    private static final Map<String, Long> paymentTotals = new LinkedHashMap<>();
    private static final Map<String, Long> latePaymentTotals = new LinkedHashMap<>();
    private static final Map<String, Long> recentPaymentNotices = new LinkedHashMap<>();
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
        try {
            long amount = parseAmount(amountText);
            if (amount <= 0 || sender == null || sender.isBlank()) return;
            long now = System.currentTimeMillis();
            String noticeKey = sender.trim().toLowerCase(java.util.Locale.ROOT) + "|" + amount;
            Long previousNotice = recentPaymentNotices.get(noticeKey);
            if (previousNotice != null && now - previousNotice < 2000L) return;
            recentPaymentNotices.put(noticeKey, now);
            recentPaymentNotices.entrySet().removeIf(entry -> now - entry.getValue() > 10_000L);
            boolean duringAuction = isAuctionAcceptingPayments();
            if (duringAuction) {
                paymentTotals.merge(sender, amount, Long::sum);
            } else {
                latePaymentTotals.merge(sender, amount, Long::sum);
            }
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
            auctionEndAt = 0;
            saveState();
        }
    }

    private static boolean hasActiveAuction() {
        return !activeItemId.isEmpty();
    }

    private static boolean isAuctionAcceptingPayments() {
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
        activeItemStack = null;
        auctionEndAt = 0;
        activeStartingPrice = 0;
        activeStartingPriceDisplay = "";
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
            latePaymentTotals.clear();
            int latePaymentCount = Integer.parseInt(properties.getProperty("auction.latePaymentCount", "0"));
            for (int i = 0; i < latePaymentCount; i++) {
                String player = properties.getProperty("auction.latePayment." + i + ".player");
                long total = Long.parseLong(properties.getProperty("auction.latePayment." + i + ".total", "0"));
                if (player != null && total > 0) latePaymentTotals.put(player, total);
            }
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
            activeStartingPriceDisplay = properties.getProperty("auction.startingPriceDisplay", Long.toString(activeStartingPrice));
            if (!activeItemId.isBlank()) {
                Identifier id = Identifier.tryParse(activeItemId);
                Item item = id == null ? null : BuiltInRegistries.ITEM.get(id)
                        .map(reference -> reference.value()).orElse(null);
                if (item == null || item == net.minecraft.world.item.Items.AIR) {
                    clearActiveAuction();
                    if (auctionEndAt > 0 && auctionEndAt <= System.currentTimeMillis()) {
                        lastResult = "Auktion beendet: " + activeItemName + " | Kein Verkauf (keine Gebote).";
                    }
                    saveState();
                } else {
                    activeItemStack = new ItemStack(item);
                    if (activeItemName.isBlank()) activeItemName = item.getDefaultInstance().getHoverName().getString();
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
            properties.setProperty("auction.startingPriceDisplay", activeStartingPriceDisplay);
        }
        properties.setProperty("auction.paymentCount", Integer.toString(paymentTotals.size()));
        properties.setProperty("auction.latePaymentCount", Integer.toString(latePaymentTotals.size()));
        int lateIndex = 0;
        for (Map.Entry<String, Long> entry : latePaymentTotals.entrySet()) {
            properties.setProperty("auction.latePayment." + lateIndex + ".player", entry.getKey());
            properties.setProperty("auction.latePayment." + lateIndex + ".total", Long.toString(entry.getValue()));
            lateIndex++;
        }
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

    private void drawItemSlot(GuiGraphicsExtractor graphics, ItemStack item, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 31, y + 31, 0xFF8FC9EE);
        graphics.fill(x, y, x + 30, y + 30, 0xFF263746);
        if (item == null || item.isEmpty()) {
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
        graphics.item(item, 0, 0);
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

        startAuctionButton = Button.builder(Component.literal("Auktion starten"), button -> startAuction())
                .bounds(left + 12, top + 243, 96, 20).build();
        addRenderableWidget(startAuctionButton);
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
        boolean active = hasActiveAuction();
        if (endAuctionButton != null) {
            endAuctionButton.visible = active;
            endAuctionButton.active = active;
        }
        if (startAuctionButton != null) {
            startAuctionButton.visible = !active;
            startAuctionButton.active = !active;
        }
        if (minPriceField != null) minPriceField.active = !active;
        if (durationField != null) durationField.active = !active;
    }

    private Component getSelectedItemLabel() {
        return Component.literal("Item");
    }

    private void openItemPicker() {
        if (hasActiveAuction()) return;
        Minecraft.getInstance().setScreenAndShow(new ItemPickerScreen(this, selected -> {
            if (hasActiveAuction()) return;
            selectedItemStack = selected.copy();
            selectedItemIndex = selectableItems.indexOf(selected.getItem());
            hasSelectedItem = true;
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
            ItemStack selected = selectedItemStack;
            if (selected == null || selected.isEmpty()) throw new NumberFormatException();
            activeItemStack = selected.copy();
            activeItemId = BuiltInRegistries.ITEM.getKey(selected.getItem()).toString();
            activeItemName = selected.getHoverName().getString();
            auctionEndAt = System.currentTimeMillis() + duration * 60_000L;
            activeStartingPrice = minPrice;
            activeStartingPriceDisplay = minPriceField.getValue().trim();
            lastResult = "";
            paymentTotals.clear();
            latePaymentTotals.clear();
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
        selectedItemStack = null;
        hasSelectedItem = false;
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

        int auctionTitleX = left + 20;
        int auctionTitleWidth = this.font.width("AUKTION");
        graphics.fill(auctionTitleX - 5, top + 40, auctionTitleX + auctionTitleWidth + 5, top + 56, 0xFF5BA9E6);
        graphics.fill(auctionTitleX - 4, top + 41, auctionTitleX + auctionTitleWidth + 4, top + 55, 0xFF263F56);
        graphics.text(this.font, "AUKTION", auctionTitleX, top + 43, 0xFFAEDFFF, true);
        if (hasActiveAuction()) {
            Identifier id = Identifier.tryParse(activeItemId);
            drawItemSlot(graphics, activeItemStack, left + 18, top + 61);
            graphics.text(this.font, fitText(activeItemName, 140), left + 62, top + 62, 0xFFFFFFFF, true);
            long seconds = auctionEndAt <= 0 ? 0 : Math.max(0, (auctionEndAt - System.currentTimeMillis()) / 1000);
            graphics.text(this.font, "Restzeit: " + (seconds / 60) + "m " + (seconds % 60) + "s",
                    left + 62, top + 78, 0xFFB8C3D1, true);
            graphics.text(this.font, "Startpreis: $" + activeStartingPriceDisplay,
                    left + 62, top + 90, 0xFF65E6A5, true);
        } else {
            ItemStack selectedItem = hasSelectedItem ? selectedItemStack : null;
            drawItemSlot(graphics, selectedItem, left + 18, top + 61);
            if (selectedItem != null && !selectedItem.isEmpty()) {
                graphics.text(this.font, "Ausgewähltes Item", left + 62, top + 62, 0xFF91A9C5, true);
                graphics.text(this.font, fitText(selectedItem.getHoverName().getString(), 140),
                        left + 62, top + 78, 0xFFFFFFFF, true);
            }
            if (!lastResult.isBlank()) {
                graphics.text(this.font, fitText(lastResult, 180), left + 16, top + 103, 0xFFCED7E2, true);
            }
        }

        graphics.fill(left + 14, top + 128, left + 206, top + 130, 0xFF58758F);
        graphics.text(this.font, "Startpreis ($)", left + 16, top + 141, 0xFFB8C3D1, true);
        graphics.text(this.font, "Dauer (Min)", left + 16, top + 180, 0xFFB8C3D1, true);

        int paymentsTitleX = left + 227;
        int paymentsTitleWidth = this.font.width("ZAHLUNGEN");
        graphics.fill(paymentsTitleX - 5, top + 40, paymentsTitleX + paymentsTitleWidth + 5, top + 56, 0xFF5BA9E6);
        graphics.fill(paymentsTitleX - 4, top + 41, paymentsTitleX + paymentsTitleWidth + 4, top + 55, 0xFF263F56);
        graphics.text(this.font, "ZAHLUNGEN", paymentsTitleX, top + 43, 0xFFAEDFFF, true);
        graphics.text(this.font, "GEWINNER", left + 224, top + 64, 0xFF91A9C5, true);
        graphics.text(this.font, fitText(getCurrentWinner(), 180), left + 224, top + 78, 0xFF65E6A5, true);
        graphics.text(this.font, "Spieler", left + 224, top + 101, 0xFFB8C3D1, true);
        graphics.text(this.font, "Gesamt", right - 68, top + 101, 0xFFB8C3D1, true);
        graphics.fill(left + 222, top + 114, right - 14, top + 115, 0xFF3C5068);

        List<Map.Entry<String, Long>> ranked = getRankedPayments();
        List<Map.Entry<String, Long>> latePayments = new ArrayList<>(latePaymentTotals.entrySet());
        int totalRows = ranked.size() + latePayments.size();
        int visibleRows = 8;
        int maxScroll = Math.max(0, totalRows - visibleRows);
        paymentScroll = Math.max(0, Math.min(paymentScroll, maxScroll));
        for (int row = paymentScroll; row < Math.min(totalRows, paymentScroll + visibleRows); row++) {
            boolean late = row >= ranked.size();
            Map.Entry<String, Long> entry = late
                    ? latePayments.get(row - ranked.size())
                    : ranked.get(row);
            int rowY = top + 123 + (row - paymentScroll) * 15;
            if (!late && row == 0) graphics.fill(left + 220, rowY - 3, right - 14, rowY + 12, 0xFF29483F);
            String playerLabel = late
                    ? (ranked.size() + (row - ranked.size()) + 1) + ". " + entry.getKey() + " (nach Auktion)"
                    : (row + 1) + ". " + entry.getKey();
            int rowColor = late ? 0xFFFF6B6B : (row == 0 ? 0xFF65E6A5 : 0xFFE0E6ED);
            graphics.text(this.font, fitText(playerLabel, 112), left + 224, rowY, rowColor, true);
            String total = fitText("$" + entry.getValue(), 66);
            graphics.text(this.font, total, right - 16 - this.font.width(total), rowY,
                    late ? 0xFFFF6B6B : 0xFFFFD479, true);
        }
        if (totalRows == 0) {
            graphics.text(this.font, "...", left + 224, top + 126, 0xFF8795A7, true);
        } else if (totalRows > visibleRows) {
            graphics.text(this.font, "Mausrad: Liste scrollen (" + totalRows + " Zahlungen)",
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
            int maxScroll = Math.max(0, getRankedPayments().size() + latePaymentTotals.size() - 8);
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
