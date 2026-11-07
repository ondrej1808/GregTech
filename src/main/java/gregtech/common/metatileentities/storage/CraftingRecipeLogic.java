package gregtech.common.metatileentities.storage;

import gregtech.api.storage.ICraftingStorage;
import gregtech.api.util.DummyContainer;
import gregtech.common.inventory.IItemList;
import gregtech.common.inventory.itemsource.ItemSources;
import gregtech.common.inventory.itemsource.sources.TileItemSource;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.items.ItemStackHandler;

import com.google.common.collect.Lists;

import java.util.Collections;
import java.util.Map;

public class CraftingRecipeLogic {

    private final World world;
    private final ItemSources itemSources;
    private final ItemStackHandler craftingGrid;
    private final ItemStack[] oldCraftingGrid = new ItemStack[9];
    private final InventoryCrafting inventoryCrafting = new InventoryCrafting(new DummyContainer(), 3, 3);
    private final IInventory craftingResultInventory = new InventoryCraftResult();
    private ItemStack oldResult = ItemStack.EMPTY;
    private final CachedRecipeData cachedRecipeData;
    private final CraftingRecipeMemory recipeMemory;
    private IRecipe cachedRecipe = null;
    private int itemsCrafted = 0;
    public static short ALL_INGREDIENTS_PRESENT = 511;
    private short tintLocation = ALL_INGREDIENTS_PRESENT;
    /** ticks since the last forced recipe re-check (see {@link #update()}) */
    private int recheckTimer = 0;

    public CraftingRecipeLogic(ICraftingStorage craftingStorage) {
        this.world = craftingStorage.getWorld();
        this.craftingGrid = craftingStorage.getCraftingGrid();
        this.recipeMemory = craftingStorage.getRecipeMemory();
        this.itemSources = new ItemSources(world);
        this.cachedRecipeData = new CachedRecipeData(itemSources, null, inventoryCrafting);
    }

    public ItemSources getItemSourceList() {
        return itemSources;
    }

    public IInventory getCraftingResultInventory() {
        return craftingResultInventory;
    }

    public int getItemsCraftedAmount() {
        return itemsCrafted;
    }

    public void setItemsCraftedAmount(int itemsCrafted) {
        this.itemsCrafted = itemsCrafted;
    }

    public void clearCraftingGrid() {
        fillCraftingGrid(Collections.emptyMap());
    }

    /**
     * Fills the ghost crafting grid (JEI transfer / clear button).
     *
     * @param ingredients 1-based grid slot -> stack (slot 0 of a JEI layout is the output)
     */
    public void fillCraftingGrid(Map<Integer, ItemStack> ingredients) {
        for (int i = 0; i < craftingGrid.getSlots(); i++) {
            craftingGrid.setStackInSlot(i, sanitizeGhostStack(ingredients.getOrDefault(i + 1, ItemStack.EMPTY)));
        }
    }

    /**
     * Patch: JEI may hand over its displayed ingredient with a wildcard damage (32767) or a count > 1;
     * a wildcard stack does not match meta-specific recipes, so no result was shown.
     *
     * @param stack stack from JEI
     * @return single-item copy with a concrete damage value, or EMPTY
     */
    private static ItemStack sanitizeGhostStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack copy = stack.copy();
        copy.setCount(1);
        if (copy.getItemDamage() == OreDictionary.WILDCARD_VALUE) copy.setItemDamage(0);
        return copy;
    }

    /**
     * Compares the ghost grid with the last seen state (stored as COPIES - the old code stored live references,
     * so in-place changes were never detected) and mirrors changes into {@link #inventoryCrafting}.
     *
     * @return {@code true} if any slot changed
     */
    private boolean hasCraftingGridUpdated() {
        boolean craftingGridChanged = false;
        for (int i = 0; i < craftingGrid.getSlots(); i++) {
            ItemStack oldStack = oldCraftingGrid[i] == null ? ItemStack.EMPTY : oldCraftingGrid[i];
            ItemStack newStack = craftingGrid.getStackInSlot(i);
            if (oldStack.isEmpty() && newStack.isEmpty()) continue;
            if (oldStack.isEmpty() != newStack.isEmpty() ||
                    !ItemStack.areItemsEqual(oldStack, newStack) ||
                    !ItemStack.areItemStackTagsEqual(oldStack, newStack)) {
                oldCraftingGrid[i] = newStack.isEmpty() ? null : newStack.copy();
                inventoryCrafting.setInventorySlotContents(i, newStack.isEmpty() ? ItemStack.EMPTY : newStack.copy());
                craftingGridChanged = true;
            }
        }
        return craftingGridChanged;
    }

    public boolean performRecipe(EntityPlayer player) {
        if (!isRecipeValid()) {
            return false;
        }
        if (!cachedRecipeData.consumeRecipeItems()) {
            return false;
        }
        ForgeHooks.setCraftingPlayer(player);
        NonNullList<ItemStack> remainingItems = cachedRecipe.getRemainingItems(inventoryCrafting); // todo right here is
                                                                                                   // where tools get
                                                                                                   // damaged (in UI)
        ForgeHooks.setCraftingPlayer(null);
        for (int i = 0; i < remainingItems.size(); i++) {
            ItemStack itemStack = remainingItems.get(i);
            if (itemStack.isEmpty()) {
                continue;
            }

            ItemStack current = inventoryCrafting.getStackInSlot(i);
            inventoryCrafting.setInventorySlotContents(i, itemStack);
            if (!cachedRecipe.matches(inventoryCrafting, itemSources.getWorld())) {
                inventoryCrafting.setInventorySlotContents(i, current);
            }

            int remainingAmount = itemStack.getCount() - itemSources.insertItem(itemStack, itemStack.getCount(), false,
                    IItemList.InsertMode.HIGHEST_PRIORITY);
            if (remainingAmount > 0) {
                itemStack.setCount(remainingAmount);
                player.addItemStackToInventory(itemStack);
                if (itemStack.getCount() > 0) {
                    player.dropItem(itemStack, false, false);
                }
            }
        }
        return true;
    }

    public void handleItemCraft(ItemStack itemStack, EntityPlayer player) {
        itemStack.onCrafting(world, player, 1);
        itemStack.getItem().onCreated(itemStack, world, player);
        // if we're not simulated, fire the event, unlock recipe and add crafted items, and play sounds
        FMLCommonHandler.instance().firePlayerCraftingEvent(player, itemStack, inventoryCrafting);

        if (cachedRecipe != null && !cachedRecipe.isDynamic()) {
            player.unlockRecipes(Lists.newArrayList(cachedRecipe));
        }
        if (cachedRecipe != null) {
            ItemStack resultStack = cachedRecipe.getCraftingResult(inventoryCrafting);
            this.itemsCrafted += resultStack.getCount();
            recipeMemory.notifyRecipePerformed(craftingGrid, resultStack);
        }
    }

    public void refreshOutputSlot() {
        ItemStack itemStack = ItemStack.EMPTY;
        if (cachedRecipe != null) {
            itemStack = cachedRecipe.getCraftingResult(inventoryCrafting);
        }
        this.craftingResultInventory.setInventorySlotContents(0, itemStack);
    }

    public boolean isRecipeValid() {
        return cachedRecipeData.getRecipe() != null && cachedRecipeData.matches(inventoryCrafting, this.world) &&
                cachedRecipeData.attemptMatchRecipe() == ALL_INGREDIENTS_PRESENT;
    }

    /**
     * Re-resolves the recipe and result for the current grid. Patch: always looks the recipe up again
     * (the old "cached recipe still matches and gives the same result" shortcut kept stale results, GT #2285)
     * and resets the remembered result when there is no recipe.
     */
    private void updateCurrentRecipe() {
        IRecipe newRecipe = CraftingManager.findMatchingRecipe(inventoryCrafting, world);
        this.cachedRecipe = newRecipe;
        ItemStack resultStack = newRecipe == null ? ItemStack.EMPTY : newRecipe.getCraftingResult(inventoryCrafting);
        this.oldResult = resultStack.copy();
        this.craftingResultInventory.setInventorySlotContents(0, resultStack);
        if (this.cachedRecipeData.getRecipe() != newRecipe) {
            this.cachedRecipeData.setRecipe(newRecipe);
        }
    }

    /**
     * Server tick: refreshes item sources and tinting, and the recipe when the grid changed. Additionally
     * re-checks once per second if the grid has items but the output is empty (safety net for missed updates).
     */
    public void update() {
        // update item sources every tick for fast tinting updates
        itemSources.update();
        if (getCachedRecipeData().getRecipe() != null) {
            tintLocation = getCachedRecipeData().attemptMatchRecipe();
        } else {
            tintLocation = ALL_INGREDIENTS_PRESENT;
        }
        if (hasCraftingGridUpdated()) {
            updateCurrentRecipe();
            recheckTimer = 0;
        } else if (++recheckTimer >= 20) {
            recheckTimer = 0;
            if (craftingResultInventory.getStackInSlot(0).isEmpty() && !inventoryCrafting.isEmpty()) {
                updateCurrentRecipe();
            }
        }
    }

    public short getTintLocations() {
        return tintLocation;
    }

    public void checkNeighbourInventories(BlockPos blockPos) {
        for (EnumFacing side : EnumFacing.VALUES) {
            TileItemSource itemSource = new TileItemSource(world, blockPos, side);
            this.itemSources.addItemHandler(itemSource);
        }
    }

    public CachedRecipeData getCachedRecipeData() {
        return this.cachedRecipeData;
    }
}
