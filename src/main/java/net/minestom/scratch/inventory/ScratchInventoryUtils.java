package net.minestom.scratch.inventory;

import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.server.play.WindowItemsPacket;
import net.minestom.server.utils.inventory.PlayerInventoryUtils;

import java.util.ArrayList;
import java.util.List;

public final class ScratchInventoryUtils {
    public static WindowItemsPacket makePlayerPacket(ItemStack[] inventoryItems, ItemStack cursor) {
        List<ItemStack> items = new ArrayList<>();
        for (int i = 0; i < inventoryItems.length; i++) {
            final int internalSlot = PlayerInventoryUtils.convertPlayerInventorySlot(i, PlayerInventoryUtils.OFFSET);
            items.add(inventoryItems[internalSlot]);
        }
        return new WindowItemsPacket((byte) 0, 0, items, cursor);
    }
}
