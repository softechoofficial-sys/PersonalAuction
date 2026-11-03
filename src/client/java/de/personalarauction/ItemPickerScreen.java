package de.personalarauction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class ItemPickerScreen extends Screen {
    private static final int COLUMNS = 9;
    private static final int ROWS = 5;
    private static final int PAGE_SIZE = COLUMNS * ROWS;
    private static final int CELL_SIZE = 20;

    private final Screen parent;
    private final Consumer<Item> onSelect;
    private final List<Item> allItems = new ArrayList<>();
    private EditBox searchField;
    private Button previousPageButton;
    private Button nextPageButton;
    private int page;

    public ItemPickerScreen(Screen parent, Consumer<Item> onSelect) {
        super(Component.literal("Item auswählen"));
        this.parent = parent;
        this.onSelect = onSelect;
        BuiltInRegistries.ITEM.forEach(allItems::add);
        allItems.removeIf(item -> item == net.minecraft.world.item.Items.AIR);
    }

    @Override
    protected void init() {
        int searchWidth = Math.min(260, this.width - 40);
        searchField = new EditBox(this.font, (this.width - searchWidth) / 2, 34,
                searchWidth, 20, Component.literal("Items suchen"));
        searchField.setMaxLength(100);
        searchField.setHint(Component.literal("Item suchen ..."));
        searchField.setResponder(value -> page = 0);
        addRenderableWidget(searchField);

        previousPageButton = Button.builder(Component.literal("< Zurück"), button -> {
            if (page > 0) page--;
            updatePageButtons();
        }).bounds(this.width / 2 - 105, this.height - 32, 100, 20).build();
        addRenderableWidget(previousPageButton);

        nextPageButton = Button.builder(Component.literal("Weiter >"), button -> {
            if (page + 1 < getPageCount()) page++;
            updatePageButtons();
        }).bounds(this.width / 2 + 5, this.height - 32, 100, 20).build();
        addRenderableWidget(nextPageButton);

        addRenderableWidget(Button.builder(Component.literal("Abbrechen"), button -> returnToParent())
                .bounds(this.width - 95, 10, 85, 20).build());
        updatePageButtons();
    }

    private List<Item> getFilteredItems() {
        String query = searchField == null ? "" : searchField.getValue().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) return allItems;
        return allItems.stream().filter(item -> {
            String name = item.getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT);
            String id = BuiltInRegistries.ITEM.getKey(item).toString().toLowerCase(Locale.ROOT);
            return name.contains(query) || id.contains(query);
        }).toList();
    }

    private int getPageCount() {
        return Math.max(1, (getFilteredItems().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void updatePageButtons() {
        if (previousPageButton != null) previousPageButton.active = page > 0;
        if (nextPageButton != null) nextPageButton.active = page + 1 < getPageCount();
    }

    private int gridStartX() {
        return (this.width - COLUMNS * CELL_SIZE) / 2;
    }

    private int gridStartY() {
        return 68;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.fill(this.width / 2 - 96, 27, this.width / 2 + 96, 28, 0xFF8FC9EE);
        graphics.text(this.font, "Item für die Auktion auswählen", this.width / 2, 16, 0xFFAEDFFF, true);
        List<Item> filtered = getFilteredItems();
        int pageCount = getPageCount();
        if (page >= pageCount) page = pageCount - 1;
        int start = page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, filtered.size());
        int startX = gridStartX();
        int startY = gridStartY();

        for (int index = start; index < end; index++) {
            int localIndex = index - start;
            int column = localIndex % COLUMNS;
            int row = localIndex / COLUMNS;
            int x = startX + column * CELL_SIZE;
            int y = startY + row * CELL_SIZE;
            Item item = filtered.get(index);
            graphics.fill(x, y, x + CELL_SIZE - 1, y + CELL_SIZE - 1, 0xAA263746);
            graphics.fill(x, y, x + CELL_SIZE - 1, y + 1, 0xFF45627A);
            graphics.item(item.getDefaultInstance(), x + 2, y + 2);
            if (mouseX >= x && mouseX < x + CELL_SIZE && mouseY >= y && mouseY < y + CELL_SIZE) {
                graphics.fill(x, y, x + CELL_SIZE - 1, y + 1, 0xFFB8E2FF);
                graphics.fill(x, y + CELL_SIZE - 1, x + CELL_SIZE - 1, y + CELL_SIZE, 0xFFB8E2FF);
                graphics.fill(x, y, x + 1, y + CELL_SIZE - 1, 0xFFB8E2FF);
                graphics.fill(x + CELL_SIZE - 1, y, x + CELL_SIZE, y + CELL_SIZE - 1, 0xFFB8E2FF);
                graphics.text(this.font, item.getDefaultInstance().getHoverName(),
                        this.width / 2, this.height - 52, 0xFFB8E2FF, true);
            }
        }

        graphics.text(this.font, filtered.size() + " Items | Seite " + (page + 1) + "/" + pageCount,
                this.width / 2, this.height - 44, 0xFFAEDFFF, true);
        updatePageButtons();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() != 0) return false;

        double mouseX = event.x();
        double mouseY = event.y();
        if (mouseX < gridStartX() || mouseX >= gridStartX() + COLUMNS * CELL_SIZE
                || mouseY < gridStartY() || mouseY >= gridStartY() + ROWS * CELL_SIZE) return false;

        int column = (int) (mouseX - gridStartX()) / CELL_SIZE;
        int row = (int) (mouseY - gridStartY()) / CELL_SIZE;
        if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) return false;

        List<Item> filtered = getFilteredItems();
        int index = page * PAGE_SIZE + row * COLUMNS + column;
        if (index < 0 || index >= filtered.size()) return false;

        onSelect.accept(filtered.get(index));
        returnToParent();
        return true;
    }

    private void returnToParent() {
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
