package net.minestom.scratch.inventory;

import net.kyori.adventure.text.Component;
import net.minestom.server.entity.EquipmentSlot;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.inventory.InventoryType;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.inventory.click.ClickPreprocessor;
import net.minestom.server.item.ItemStack;
import net.minestom.scratch.registry.ScratchRegistryTools;
import net.minestom.server.network.packet.client.play.*;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manage a player inventory and its potential open container.
 */
public final class InventoryHolder {
    private final int entityId;
    private final Consumer<ServerPacket.Play> selfConsumer;
    private final Consumer<ServerPacket.Play> localBroadcastConsumer;
    private final ItemStack[] inventory = new ItemStack[46];
    private ItemStack cursor = ItemStack.AIR;
    private int heldSlot;

    private Container openContainer;
    private final ClickPreprocessor clickPreprocessor = new ClickPreprocessor();

    public InventoryHolder(int entityId, Consumer<ServerPacket.Play> selfConsumer,
                           Consumer<ServerPacket.Play> localBroadcastConsumer) {
        this.entityId = entityId;
        this.selfConsumer = selfConsumer;
        this.localBroadcastConsumer = localBroadcastConsumer;

        Arrays.fill(inventory, ItemStack.AIR);
    }

    public int heldSlot() {
        return heldSlot;
    }

    public void setItem(int slot, @NotNull ItemStack item) {
        inventory[slot] = item;
    }

    public ItemStack getItem(int slot) {
        return inventory[slot];
    }

    public ItemStack getEquipment(@NotNull EquipmentSlot slot) {
        return inventory[equipmentSlot(slot)];
    }

    public ItemStack getHandItem(PlayerHand hand) {
        final int slot = handSlot(hand);
        return inventory[slot];
    }

    public void setHandItem(PlayerHand hand, ItemStack item) {
        final int slot = handSlot(hand);
        inventory[slot] = item;
    }

    public int handSlot(PlayerHand hand) {
        return switch (hand) {
            case MAIN -> heldSlot;
            case OFF -> PlayerInventoryUtils.OFFHAND_SLOT;
        };
    }

    public Map<EquipmentSlot, ItemStack> equipments() {
        return Map.of(
                EquipmentSlot.MAIN_HAND, inventory[heldSlot],
                EquipmentSlot.OFF_HAND, inventory[PlayerInventoryUtils.OFFHAND_SLOT],
                EquipmentSlot.HELMET, inventory[PlayerInventoryUtils.HELMET_SLOT],
                EquipmentSlot.CHESTPLATE, inventory[PlayerInventoryUtils.CHESTPLATE_SLOT],
                EquipmentSlot.LEGGINGS, inventory[PlayerInventoryUtils.LEGGINGS_SLOT],
                EquipmentSlot.BOOTS, inventory[PlayerInventoryUtils.BOOTS_SLOT]
        );
    }

    public void consumeItem(int slot) {
        ItemStack current = inventory[slot];
        if (current.isAir()) return;
        final ItemStack updated = current.withAmount(current.amount() - 1);
        this.inventory[slot] = updated;
    }

    public void consumeItem(PlayerHand hand) {
        final int slot = handSlot(hand);
        consumeItem(slot);
    }

    public WindowItemsPacket itemsPacket() {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < inventory.length; i++) {
            final int internalSlot = PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(i);
            items.add(inventory[internalSlot]);
        }
        return new WindowItemsPacket((byte) 0, 0, items, cursor);
    }

    public EntityEquipmentPacket equipmentPacket() {
        return new EntityEquipmentPacket(entityId, equipments());
    }

    public void consume(ClientHeldItemChangePacket packet) {
        this.heldSlot = packet.slot();
        this.localBroadcastConsumer.accept(equipmentPacket());
    }

    public void consume(ClientClickWindowPacket packet) {
        final int windowId = packet.windowId();
        final Container container = this.openContainer;
        final Integer containerSize;

        if (windowId == 0) {
            containerSize = null;
        } else {
            if (container == null) return;
            if ((container.id & 0xFF) != windowId) return;
            containerSize = container.type.getSize();
        }

        Click click = clickPreprocessor.processClick(packet, containerSize);
        if (click == null) return;

        boolean equipmentChanged = handleClick(click, containerSize);

        if (equipmentChanged) {
            localBroadcastConsumer.accept(equipmentPacket());
        }

        if (container != null) {
            for (InventoryHolder viewer : container.viewers) {
                viewer.selfConsumer.accept(container.itemsPacket(viewer.inventory, viewer.cursor));
            }
        }

        if (!ItemStack.Hash.of(cursor, ScratchRegistryTools.registries()).equals(packet.clickedItem())) {
            selfConsumer.accept(new SetCursorItemPacket(cursor));
        }
    }
    private boolean handleClick(Click click, @Nullable Integer containerSize) {
        return switch (click) {
            case Click.Left(int slot) -> handleLeft(slot, containerSize);
            case Click.Right(int slot) -> handleRight(slot, containerSize);
            case Click.Middle(int slot) -> handleMiddle(slot, containerSize);
            case Click.LeftShift(int slot) -> handleShift(slot, containerSize);
            case Click.RightShift(int slot) -> handleShift(slot, containerSize);
            case Click.Double(int slot) -> handleDouble(slot, containerSize);
            case Click.LeftDrag(List<Integer> slots) -> handleLeftDrag(slots, containerSize);
            case Click.RightDrag(List<Integer> slots) -> handleRightDrag(slots, containerSize);
            case Click.MiddleDrag(List<Integer> slots) -> handleMiddleDrag(slots, containerSize);
            case Click.LeftDropCursor() -> { handleLeftDropCursor(); yield false; }
            case Click.RightDropCursor() -> { handleRightDropCursor(); yield false; }
            case Click.MiddleDropCursor() -> false;
            case Click.DropSlot(int slot, boolean all) -> handleDropSlot(slot, all, containerSize);
            case Click.HotbarSwap(int hotbarSlot, int slot) -> handleHotbarSwap(hotbarSlot, slot, containerSize);
            case Click.OffhandSwap(int slot) -> handleOffhandSwap(slot, containerSize);
        };
    }

    private ItemStack getClickedItem(int slot, @Nullable Integer containerSize) {
        if (containerSize != null && slot < containerSize) {
            return openContainer.inventory[slot];
        } else {
            int playerSlot = containerSize != null ? slot - containerSize : slot;
            return inventory[playerSlot];
        }
    }

    private void setClickedItem(int slot, @Nullable Integer containerSize, ItemStack item) {
        if (containerSize != null && slot < containerSize) {
            openContainer.inventory[slot] = item;
        } else {
            int playerSlot = containerSize != null ? slot - containerSize : slot;
            inventory[playerSlot] = item;
        }
    }

    private boolean isPlayerEquipmentSlot(int slot, @Nullable Integer containerSize) {
        if (containerSize != null && slot < containerSize) return false;
        int playerSlot = containerSize != null ? slot - containerSize : slot;
        return isEquipmentSlot(playerSlot);
    }

    private boolean handleLeft(int slot, @Nullable Integer containerSize) {
        ItemStack clicked = getClickedItem(slot, containerSize);
        ItemStack cursorItem = this.cursor;

        if (cursorItem.isAir()) {
            this.cursor = clicked;
            setClickedItem(slot, containerSize, ItemStack.AIR);
        } else if (clicked.isAir()) {
            setClickedItem(slot, containerSize, cursorItem);
            this.cursor = ItemStack.AIR;
        } else if (cursorItem.isSimilar(clicked)) {
            int total = cursorItem.amount() + clicked.amount();
            int max = clicked.maxStackSize();
            if (total <= max) {
                setClickedItem(slot, containerSize, clicked.withAmount(total));
                this.cursor = ItemStack.AIR;
            } else {
                setClickedItem(slot, containerSize, clicked.withAmount(max));
                this.cursor = cursorItem.withAmount(total - max);
            }
        } else {
            setClickedItem(slot, containerSize, cursorItem);
            this.cursor = clicked;
        }

        return isPlayerEquipmentSlot(slot, containerSize);
    }

    private boolean handleRight(int slot, @Nullable Integer containerSize) {
        ItemStack clicked = getClickedItem(slot, containerSize);
        ItemStack cursorItem = this.cursor;

        if (cursorItem.isAir()) {
            if (!clicked.isAir()) {
                int half = (clicked.amount() + 1) / 2;
                this.cursor = clicked.withAmount(half);
                setClickedItem(slot, containerSize, clicked.withAmount(clicked.amount() - half));
            }
        } else {
            if (clicked.isAir()) {
                setClickedItem(slot, containerSize, cursorItem.withAmount(1));
                this.cursor = cursorItem.withAmount(cursorItem.amount() - 1);
            } else if (cursorItem.isSimilar(clicked) && clicked.amount() < clicked.maxStackSize()) {
                setClickedItem(slot, containerSize, clicked.withAmount(clicked.amount() + 1));
                this.cursor = cursorItem.withAmount(cursorItem.amount() - 1);
            } else if (!cursorItem.isSimilar(clicked)) {
                setClickedItem(slot, containerSize, cursorItem);
                this.cursor = clicked;
            }
        }

        return isPlayerEquipmentSlot(slot, containerSize);
    }

    private boolean handleMiddle(int slot, @Nullable Integer containerSize) {
        if (!cursor.isAir()) return false;
        ItemStack clicked = getClickedItem(slot, containerSize);
        if (!clicked.isAir()) {
            this.cursor = clicked.withAmount(clicked.maxStackSize());
        }
        return false;
    }

    private boolean handleShift(int slot, @Nullable Integer containerSize) {
        ItemStack clicked = getClickedItem(slot, containerSize);
        if (clicked.isAir()) return false;

        boolean equipmentChanged;

        if (containerSize != null) {
            if (slot < containerSize) {
                ItemStack remaining = addToPlayerInventory(clicked, 0, 36);
                setClickedItem(slot, containerSize, remaining);
                equipmentChanged = true;
            } else {
                ItemStack remaining = addToContainer(clicked);
                setClickedItem(slot, containerSize, remaining);
                equipmentChanged = isPlayerEquipmentSlot(slot, containerSize);
            }
        } else {
            int playerSlot = slot;
            boolean inHotbar = playerSlot < 9;
            ItemStack remaining = addToPlayerInventory(clicked, inHotbar ? 9 : 0, inHotbar ? 36 : 9);
            setClickedItem(slot, containerSize, remaining);
            equipmentChanged = isEquipmentSlot(playerSlot);
        }

        return equipmentChanged;
    }

    private ItemStack addToPlayerInventory(ItemStack item, int start, int end) {
        int amount = item.amount();
        for (int i = start; i < end && amount > 0; i++) {
            ItemStack existing = inventory[i];
            if (existing.isSimilar(item) && existing.amount() < existing.maxStackSize()) {
                int space = existing.maxStackSize() - existing.amount();
                int toAdd = Math.min(space, amount);
                inventory[i] = existing.withAmount(existing.amount() + toAdd);
                amount -= toAdd;
            }
        }
        for (int i = start; i < end && amount > 0; i++) {
            if (inventory[i].isAir()) {
                int toAdd = Math.min(item.maxStackSize(), amount);
                inventory[i] = item.withAmount(toAdd);
                amount -= toAdd;
            }
        }
        return amount > 0 ? item.withAmount(amount) : ItemStack.AIR;
    }

    private ItemStack addToContainer(ItemStack item) {
        if (openContainer == null) return item;
        ItemStack[] containerInv = openContainer.inventory;
        int amount = item.amount();
        for (int i = 0; i < containerInv.length && amount > 0; i++) {
            ItemStack existing = containerInv[i];
            if (existing.isSimilar(item) && existing.amount() < existing.maxStackSize()) {
                int space = existing.maxStackSize() - existing.amount();
                int toAdd = Math.min(space, amount);
                containerInv[i] = existing.withAmount(existing.amount() + toAdd);
                amount -= toAdd;
            }
        }
        for (int i = 0; i < containerInv.length && amount > 0; i++) {
            if (containerInv[i].isAir()) {
                int toAdd = Math.min(item.maxStackSize(), amount);
                containerInv[i] = item.withAmount(toAdd);
                amount -= toAdd;
            }
        }
        return amount > 0 ? item.withAmount(amount) : ItemStack.AIR;
    }

    private boolean handleDouble(int slot, @Nullable Integer containerSize) {
        if (cursor.isAir()) return false;

        int maxStack = cursor.maxStackSize();
        int amount = cursor.amount();
        boolean equipmentChanged = false;

        for (int i = 0; i < 36 && amount < maxStack; i++) {
            if (inventory[i].isSimilar(cursor)) {
                int toTake = Math.min(inventory[i].amount(), maxStack - amount);
                inventory[i] = inventory[i].withAmount(inventory[i].amount() - toTake);
                amount += toTake;
                if (isEquipmentSlot(i)) equipmentChanged = true;
            }
        }

        if (openContainer != null) {
            ItemStack[] containerInv = openContainer.inventory;
            for (int i = 0; i < containerInv.length && amount < maxStack; i++) {
                if (containerInv[i].isSimilar(cursor)) {
                    int toTake = Math.min(containerInv[i].amount(), maxStack - amount);
                    containerInv[i] = containerInv[i].withAmount(containerInv[i].amount() - toTake);
                    amount += toTake;
                }
            }
        }

        this.cursor = cursor.withAmount(amount);
        return equipmentChanged;
    }

    private boolean handleLeftDrag(List<Integer> slots, @Nullable Integer containerSize) {
        if (cursor.isAir() || slots.isEmpty()) return false;

        int totalAmount = cursor.amount();
        int perSlot = totalAmount / slots.size();
        if (perSlot == 0) return false;

        boolean equipmentChanged = false;
        int remaining = totalAmount;

        for (int slot : slots) {
            ItemStack existing = getClickedItem(slot, containerSize);
            if (existing.isAir() || existing.isSimilar(cursor)) {
                int currentAmount = existing.isAir() ? 0 : existing.amount();
                int maxStack = cursor.maxStackSize();
                int canAdd = Math.min(perSlot, maxStack - currentAmount);
                if (canAdd > 0) {
                    setClickedItem(slot, containerSize, cursor.withAmount(currentAmount + canAdd));
                    remaining -= canAdd;
                    if (isPlayerEquipmentSlot(slot, containerSize)) equipmentChanged = true;
                }
            }
        }

        this.cursor = remaining > 0 ? cursor.withAmount(remaining) : ItemStack.AIR;
        return equipmentChanged;
    }

    private boolean handleRightDrag(List<Integer> slots, @Nullable Integer containerSize) {
        if (cursor.isAir() || slots.isEmpty()) return false;

        boolean equipmentChanged = false;
        int remaining = cursor.amount();

        for (int slot : slots) {
            if (remaining <= 0) break;
            ItemStack existing = getClickedItem(slot, containerSize);
            if (existing.isAir()) {
                setClickedItem(slot, containerSize, cursor.withAmount(1));
                remaining--;
                if (isPlayerEquipmentSlot(slot, containerSize)) equipmentChanged = true;
            } else if (existing.isSimilar(cursor) && existing.amount() < existing.maxStackSize()) {
                setClickedItem(slot, containerSize, existing.withAmount(existing.amount() + 1));
                remaining--;
                if (isPlayerEquipmentSlot(slot, containerSize)) equipmentChanged = true;
            }
        }

        this.cursor = remaining > 0 ? cursor.withAmount(remaining) : ItemStack.AIR;
        return equipmentChanged;
    }

    private boolean handleMiddleDrag(List<Integer> slots, @Nullable Integer containerSize) {
        if (cursor.isAir()) return false;

        boolean equipmentChanged = false;
        for (int slot : slots) {
            ItemStack existing = getClickedItem(slot, containerSize);
            if (existing.isAir()) {
                setClickedItem(slot, containerSize, cursor.withAmount(cursor.maxStackSize()));
                if (isPlayerEquipmentSlot(slot, containerSize)) equipmentChanged = true;
            }
        }
        return equipmentChanged;
    }

    private void handleLeftDropCursor() {
        this.cursor = ItemStack.AIR;
    }

    private void handleRightDropCursor() {
        if (!cursor.isAir()) {
            this.cursor = cursor.withAmount(cursor.amount() - 1);
        }
    }

    private boolean handleDropSlot(int slot, boolean all, @Nullable Integer containerSize) {
        ItemStack clicked = getClickedItem(slot, containerSize);
        if (clicked.isAir()) return false;

        if (all) {
            setClickedItem(slot, containerSize, ItemStack.AIR);
        } else {
            setClickedItem(slot, containerSize, clicked.withAmount(clicked.amount() - 1));
        }
        return isPlayerEquipmentSlot(slot, containerSize);
    }

    private boolean handleHotbarSwap(int hotbarSlot, int slot, @Nullable Integer containerSize) {
        ItemStack clicked = getClickedItem(slot, containerSize);
        ItemStack hotbarItem = inventory[hotbarSlot];

        setClickedItem(slot, containerSize, hotbarItem);
        inventory[hotbarSlot] = clicked;

        return hotbarSlot == heldSlot || isPlayerEquipmentSlot(slot, containerSize);
    }

    private boolean handleOffhandSwap(int slot, @Nullable Integer containerSize) {
        ItemStack clicked = getClickedItem(slot, containerSize);
        ItemStack offhandItem = inventory[PlayerInventoryUtils.OFFHAND_SLOT];

        setClickedItem(slot, containerSize, offhandItem);
        inventory[PlayerInventoryUtils.OFFHAND_SLOT] = clicked;

        return true;
    }

    public void consume(ClientUseItemPacket packet) {
        final int slot = switch (packet.hand()) {
            case MAIN -> heldSlot;
            case OFF -> PlayerInventoryUtils.OFFHAND_SLOT;
        };

        final ItemStack item = inventory[slot];
        final EquipmentSlot equipmentSlot = item.material().equipmentSlot();
        if (equipmentSlot != null && equipmentSlot.isArmor()) {
            final int internalSlot = equipmentSlot(equipmentSlot);
            // Swap the armor piece with the one in the hand
            final ItemStack currentArmor = inventory[internalSlot];
            inventory[internalSlot] = item;
            inventory[slot] = currentArmor;
            this.localBroadcastConsumer.accept(equipmentPacket());
        }
    }

    public void consume(ClientCreativeInventoryActionPacket packet) {
        final int internalSlot = PlayerInventoryUtils.convertWindow0SlotToMinestomSlot(packet.slot());
        if (internalSlot < 0) return;
        this.inventory[internalSlot] = packet.item();
        if (isEquipmentSlot(internalSlot)) {
            this.localBroadcastConsumer.accept(equipmentPacket());
        }
    }

    public void consume(ClientCloseWindowPacket packet) {
        clickPreprocessor.clearCache();
        Container openContainer = this.openContainer;
        if (openContainer != null) {
            openContainer.viewers.remove(this);
            this.openContainer = null;
        }
    }

    private boolean isCraftingSlot(int slot) {
        return slot == PlayerInventoryUtils.CRAFT_SLOT_1 || slot == PlayerInventoryUtils.CRAFT_SLOT_2 ||
                slot == PlayerInventoryUtils.CRAFT_SLOT_3 || slot == PlayerInventoryUtils.CRAFT_SLOT_4;
    }

    private boolean isHandSlot(int slot) {
        return slot == heldSlot || slot == PlayerInventoryUtils.OFFHAND_SLOT;
    }

    private boolean isEquipmentSlot(int slot) {
        return switch (slot) {
            case PlayerInventoryUtils.HELMET_SLOT, PlayerInventoryUtils.CHESTPLATE_SLOT,
                 PlayerInventoryUtils.LEGGINGS_SLOT, PlayerInventoryUtils.BOOTS_SLOT,
                 PlayerInventoryUtils.OFFHAND_SLOT -> true;
            default -> slot == heldSlot;
        };
    }

    private int equipmentSlot(EquipmentSlot equipmentSlot) {
        return switch (equipmentSlot) {
            case MAIN_HAND -> heldSlot;
            case OFF_HAND -> PlayerInventoryUtils.OFFHAND_SLOT;
            case HELMET -> PlayerInventoryUtils.HELMET_SLOT;
            case CHESTPLATE -> PlayerInventoryUtils.CHESTPLATE_SLOT;
            case LEGGINGS -> PlayerInventoryUtils.LEGGINGS_SLOT;
            case BOOTS -> PlayerInventoryUtils.BOOTS_SLOT;
            case BODY -> equipmentSlot.armorSlot();
            case SADDLE -> -1; // ?
        };
    }

    public boolean openContainer(Container container) {
        final boolean success = container.viewers.add(this);
        if (success) {
            this.openContainer = container;
            selfConsumer.accept(container.openPacket());
            selfConsumer.accept(container.itemsPacket(this.inventory, cursor));
        }
        return success;
    }

    public Container openContainer() {
        return openContainer;
    }

    public void closeContainer() {
        clickPreprocessor.clearCache();
        var openContainer = this.openContainer;
        if (openContainer != null) {
            openContainer.viewers.remove(this);
            this.openContainer = null;
            selfConsumer.accept(new CloseWindowPacket(openContainer.id));
        }
    }

    public boolean canAddItem(ItemStack itemStack) {
        int amount = itemStack.amount();
        for (int i = 0; i < 36; i++) {
            ItemStack inventoryItem = inventory[i];
            if (inventoryItem.isAir()) return true;
            if (inventoryItem.isSimilar(itemStack)) {
                final int mergedAmount = inventoryItem.amount() + itemStack.amount();
                final int maxStackSize = inventoryItem.maxStackSize();
                if (mergedAmount <= maxStackSize) return true;
                amount = mergedAmount - maxStackSize;
            }

            if (amount <= 0) return true;
        }
        return false;
    }

    public void addItem(ItemStack itemStack) {
        int amount = itemStack.amount();
        for (int i = 0; i < 36; i++) {
            ItemStack inventoryItem = inventory[i];
            boolean updated = false;
            if (inventoryItem.isAir()) {
                inventory[i] = itemStack;
                updated = true;
                amount = 0;
            } else if (inventoryItem.isSimilar(itemStack)) {
                final int mergedAmount = inventoryItem.amount() + itemStack.amount();
                final int maxStackSize = inventoryItem.maxStackSize();
                if (mergedAmount > maxStackSize) {
                    inventory[i] = inventoryItem.withAmount(maxStackSize);
                    itemStack = itemStack.withAmount(mergedAmount - maxStackSize);
                    amount = itemStack.amount();
                } else {
                    inventory[i] = inventoryItem.withAmount(mergedAmount);
                    amount = 0;
                }
                updated = true;
            }
            if (updated) {
                if (i == heldSlot) this.localBroadcastConsumer.accept(equipmentPacket());
            }
            if (amount <= 0) return;
        }
    }

    public static class Container {
        private static final AtomicInteger ID_GENERATOR = new AtomicInteger(1);
        private final byte id = (byte) ID_GENERATOR.getAndIncrement(); // collision?
        private final Component title;
        private final InventoryType type;
        private final Set<InventoryHolder> viewers = new HashSet<>();
        private final ItemStack[] inventory;

        public Container(Component title, InventoryType type) {
            this.title = title;
            this.type = type;

            this.inventory = new ItemStack[type.getSize()];
            Arrays.fill(inventory, ItemStack.AIR);
        }

        public OpenWindowPacket openPacket() {
            return new OpenWindowPacket(id, type.ordinal(), title);
        }

        public WindowItemsPacket itemsPacket(ItemStack[] playerInventory, ItemStack cursor) {
            int containerSize = type.getSize();
            List<ItemStack> items = new ArrayList<>(containerSize + 36);

            items.addAll(Arrays.asList(inventory));

            for (int i = containerSize; i < containerSize + 36; i++) {
                int minestomSlot = PlayerInventoryUtils.convertWindowSlotToMinestomSlot(i, containerSize);
                items.add(playerInventory[minestomSlot]);
            }

            return new WindowItemsPacket(id & 0xFF, 0, items, cursor);
        }

        public byte id() {
            return id;
        }

        public InventoryType type() {
            return type;
        }

        public Set<InventoryHolder> viewers() {
            return viewers;
        }

        public ItemStack[] inventory() {
            return inventory;
        }
    }
}
