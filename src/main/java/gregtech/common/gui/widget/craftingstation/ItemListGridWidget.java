package gregtech.common.gui.widget.craftingstation;

import gregtech.api.gui.INativeWidget;
import gregtech.api.gui.Widget;
import gregtech.api.gui.widgets.ScrollableListWidget;
import gregtech.api.gui.widgets.WidgetGroup;
import gregtech.api.util.ItemStackHashStrategy;
import gregtech.common.inventory.IItemInfo;
import gregtech.common.inventory.IItemList;
import gregtech.common.inventory.IItemList.InsertMode;
import gregtech.common.inventory.SimpleItemInfo;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.*;

public class ItemListGridWidget extends ScrollableListWidget {

    private static final Comparator<IItemInfo> COMPARATOR = Comparator.comparing(
            IItemInfo::getItemStack,
            Comparator.<ItemStack>comparingInt(it -> Item.REGISTRY.getIDForObject(it.getItem()))
                    .thenComparing(ItemStack::getItemDamage)
                    .thenComparing(ItemStack::hasTagCompound)
                    .thenComparing(it -> -Objects.hashCode(it.getTagCompound()))
                    .thenComparing(it -> -it.getCount()));

    @Nullable
    private final IItemList itemList;
    private final int slotAmountX;
    private final int slotAmountY;
    private int slotRowsAmount = 0;
    private final Map<ItemStack, SimpleItemInfo> cachedItemList = new Object2ObjectOpenCustomHashMap<>(
            ItemStackHashStrategy.comparingAllButCount());
    private final List<SimpleItemInfo> itemsChanged = new ArrayList<>();
    private final List<ItemStack> itemsRemoved = new ArrayList<>();

    private final List<SimpleItemInfo> displayItemList = new ArrayList<>();
    /** client-side view of {@link #displayItemList} after applying {@link #searchFilter} */
    private final List<SimpleItemInfo> filteredItemList = new ArrayList<>();
    private String searchFilter = "";

    public ItemListGridWidget(int x, int y, int slotsX, int slotsY, @Nullable IItemList itemList) {
        super(x, y, slotsX * 18 + 10, slotsY * 18);
        this.itemList = itemList;
        this.slotAmountX = slotsX;
        this.slotAmountY = slotsY;
    }

    @Nullable
    public IItemList getItemList() {
        return itemList;
    }

    /**
     * Returns the item shown in the given grid cell (after search filtering).
     *
     * @param index cell index, row-major from the top-left of the full list
     * @return item info, or {@code null} for an empty cell
     */
    @Nullable
    public IItemInfo getItemInfoAt(int index) {
        return filteredItemList.size() > index ? filteredItemList.get(index) : null;
    }

    /**
     * Current search text.
     *
     * @return search filter, never {@code null}
     */
    public String getSearchFilter() {
        return searchFilter;
    }

    /**
     * Sets the search text, re-filters the list and scrolls back to the top.
     * Matches display name, or mod id when the query starts with {@code @}; case-insensitive.
     *
     * @param filter new search text ({@code null} treated as empty)
     */
    public void setSearchFilter(String filter) {
        String newFilter = filter == null ? "" : filter;
        if (newFilter.equals(searchFilter)) return;
        this.searchFilter = newFilter;
        refilter();
        this.scrollOffset = 0;
        updateElementPositions();
    }

    /**
     * Rebuilds {@link #filteredItemList} from {@link #displayItemList} and the search filter.
     */
    private void refilter() {
        filteredItemList.clear();
        String query = searchFilter.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            filteredItemList.addAll(displayItemList);
            return;
        }
        for (SimpleItemInfo info : displayItemList) {
            if (matches(info.getItemStack(), query)) filteredItemList.add(info);
        }
    }

    /**
     * Tests one stack against a lower-cased search query.
     *
     * @param stack stack to test
     * @param query lower-cased, trimmed query; a leading {@code @} matches the mod id instead
     * @return {@code true} if the stack matches
     */
    private static boolean matches(ItemStack stack, String query) {
        if (query.startsWith("@")) {
            ResourceLocation id = stack.getItem().getRegistryName();
            return id != null && id.getNamespace().toLowerCase(Locale.ROOT).contains(query.substring(1));
        }
        return stack.getDisplayName().toLowerCase(Locale.ROOT).contains(query);
    }

    /**
     * Only the rows that contain filtered items (at least one page) are scrollable.
     *
     * @return scrollable height in pixels
     */
    @Override
    protected int getScrollableHeight() {
        int rows = Math.max(slotAmountY, (filteredItemList.size() + slotAmountX - 1) / slotAmountX);
        return Math.min(totalListHeight, rows * 18);
    }

    /**
     * Scrolls one slot row per wheel notch.
     *
     * @param mouseX     mouse x
     * @param mouseY     mouse y
     * @param wheelDelta raw wheel delta (positive = up)
     * @return {@code true} if the mouse is over the grid (event consumed)
     */
    @Override
    public boolean mouseWheelMove(int mouseX, int mouseY, int wheelDelta) {
        if (!isMouseOverElement(mouseX, mouseY)) return false;
        scrollListBy(-Integer.signum(wheelDelta) * 18);
        return true;
    }

    @Override
    public boolean mouseClicked(int mouseX, int mouseY, int button) {
        boolean result = super.mouseClicked(mouseX, mouseY, button);
        if (!result && isShiftDown()) {
            INativeWidget hoveredSlot = findHoveredSlot(mouseX, mouseY);
            if (hoveredSlot != null) {
                dispatchOtherSlotShiftClick(hoveredSlot);
                return true;
            }
        }
        return result;
    }

    private void dispatchOtherSlotShiftClick(INativeWidget clickedSlot) {
        ItemStack stackInSlot = clickedSlot.getHandle().getStack();
        if (!stackInSlot.isEmpty()) {
            writeClientAction(4, buf -> buf.writeVarInt(clickedSlot.getHandle().slotNumber));
        }
    }

    private void handleSlotShiftClick(INativeWidget clickedSlot) {
        ItemStack itemStack = clickedSlot.getHandle().getStack();
        if (clickedSlot.getHandle().canTakeStack(gui.entityPlayer) && !itemStack.isEmpty()) {
            itemStack = clickedSlot.onItemTake(gui.entityPlayer, itemStack, true);
            int amountInserted = getItemList().insertItem(itemStack, itemStack.getCount(), false,
                    InsertMode.LOWEST_PRIORITY);
            if (amountInserted > 0) {
                clickedSlot.onItemTake(gui.entityPlayer, itemStack, false);
                itemStack.shrink(amountInserted);
                if (!clickedSlot.canMergeSlot(itemStack)) {
                    gui.entityPlayer.dropItem(itemStack.copy(), false, false);
                    itemStack.setCount(0);
                }
                clickedSlot.getHandle().onSlotChanged();
                uiAccess.sendSlotUpdate(clickedSlot);
                gui.entityPlayer.openContainer.detectAndSendChanges();
            }
        }
    }

    private void addSlotRows(int amount) {
        for (int i = 0; i < amount; i++) {
            int widgetAmount = widgets.size();
            WidgetGroup widgetGroup = new WidgetGroup();
            for (int j = 0; j < slotAmountX; j++) {
                Widget widget = new ItemListSlotWidget(j * 18, 0, this, widgetAmount * slotAmountX + j);
                widgetGroup.addWidget(widget);
            }
            addWidget(widgetGroup);
        }
    }

    private void removeSlotRows(int amount) {
        for (int i = 0; i < amount; i++) {
            Widget slotWidget = widgets.remove(widgets.size() - 1);
            removeWidget(slotWidget);
        }
    }

    private void modifySlotRows(int delta) {
        if (delta > 0) {
            addSlotRows(delta);
        } else {
            removeSlotRows(-delta);
        }
    }

    private void checkItemListForChanges() {
        Iterator<ItemStack> iterator = cachedItemList.keySet().iterator();
        while (iterator.hasNext()) {
            ItemStack itemStack = iterator.next();
            if (!itemList.hasItemStored(itemStack)) {
                iterator.remove();
                itemsRemoved.add(itemStack);
            }
        }
        for (ItemStack itemStack : itemList.getStoredItems()) {
            IItemInfo itemInfo = itemList.getItemInfo(itemStack);
            if (itemInfo == null)
                continue;

            if (!cachedItemList.containsKey(itemStack)) {
                SimpleItemInfo lookupInfo = new SimpleItemInfo(itemStack);
                int totalAmount = itemInfo.getTotalItemAmount();

                if (totalAmount == 0) {
                    itemsRemoved.add(itemStack);
                } else {
                    lookupInfo.setTotalItemAmount(totalAmount);
                    cachedItemList.put(itemStack, lookupInfo);
                    itemsChanged.add(lookupInfo);
                }
            } else {
                SimpleItemInfo cachedItemInfo = cachedItemList.get(itemStack);
                if (cachedItemInfo.getTotalItemAmount() != itemInfo.getTotalItemAmount()) {
                    cachedItemInfo.setTotalItemAmount(itemInfo.getTotalItemAmount());
                    itemsChanged.add(cachedItemInfo);
                }
            }
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (itemList == null) return;
        int amountOfItemTypes = itemList.getStoredItems().size();
        int slotRowsRequired = Math.max(slotAmountY, (int) Math.ceil(amountOfItemTypes / (slotAmountX * 1.0)));
        if (slotRowsAmount != slotRowsRequired) {
            int slotsToAdd = slotRowsRequired - slotRowsAmount;
            this.slotRowsAmount = slotRowsRequired;
            writeUpdateInfo(2, buf -> buf.writeVarInt(slotsToAdd));
            modifySlotRows(slotsToAdd);
        }

        this.itemsChanged.clear();
        this.itemsRemoved.clear();
        checkItemListForChanges();
        if (!itemsChanged.isEmpty() || !itemsRemoved.isEmpty()) {
            writeUpdateInfo(3, buf -> {
                buf.writeVarInt(itemsRemoved.size());
                for (ItemStack stack : itemsRemoved) {
                    buf.writeItemStack(stack);
                }
                buf.writeVarInt(itemsChanged.size());
                for (SimpleItemInfo itemInfo : itemsChanged) {
                    buf.writeItemStack(itemInfo.getItemStack());
                    buf.writeVarInt(itemInfo.getTotalItemAmount());
                }
            });
        }
    }

    @Override
    public void readUpdateInfo(int id, PacketBuffer buffer) {
        super.readUpdateInfo(id, buffer);
        if (id == 2) {
            int slotsToAdd = buffer.readVarInt();
            modifySlotRows(slotsToAdd);
            scrollListBy(0);
        }
        if (id == 3) {
            try {
                int itemsRemoved = buffer.readVarInt();
                for (int i = 0; i < itemsRemoved; i++) {
                    ItemStack itemStack = buffer.readItemStack();
                    this.displayItemList.removeIf(
                            it -> ItemStackHashStrategy.comparingAllButCount().equals(it.getItemStack(), itemStack));
                }
                int itemsChanged = buffer.readVarInt();
                for (int i = 0; i < itemsChanged; i++) {
                    ItemStack itemStack = buffer.readItemStack();
                    int newTotalAmount = buffer.readVarInt();
                    SimpleItemInfo itemInfo = displayItemList.stream()
                            .filter(it -> ItemStackHashStrategy.comparingAllButCount().equals(it.getItemStack(),
                                    itemStack))
                            .findAny()
                            .orElse(null);
                    if (itemInfo == null) {
                        itemInfo = new SimpleItemInfo(itemStack);
                        this.displayItemList.add(itemInfo);
                    }
                    itemInfo.setTotalItemAmount(newTotalAmount);
                }
                this.displayItemList.sort(COMPARATOR);
                refilter();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    @Override
    public void handleClientAction(int id, PacketBuffer buffer) {
        super.handleClientAction(id, buffer);
        if (id == 4) {
            INativeWidget clickedSlot = findSlotByNumber(buffer.readVarInt());
            if (clickedSlot != null) {
                handleSlotShiftClick(clickedSlot);
            }
        }
    }

    @Nullable
    private INativeWidget findHoveredSlot(int mouseX, int mouseY) {
        return gui.guiWidgets.values().stream()
                .flatMap(it -> it.getNativeWidgets().stream())
                .filter(it -> it.getHandle().isEnabled())
                .filter(it -> it.getHandle().canTakeStack(gui.entityPlayer))
                .filter(it -> ((Widget) it).isMouseOverElement(mouseX, mouseY))
                .findFirst().orElse(null);
    }

    @Nullable
    private INativeWidget findSlotByNumber(int slotNumber) {
        return gui.guiWidgets.values().stream()
                .flatMap(it -> it.getNativeWidgets().stream())
                .filter(it -> it.getHandle().slotNumber == slotNumber)
                .findFirst().orElse(null);
    }
}
