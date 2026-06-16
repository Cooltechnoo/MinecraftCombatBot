package de.Cooltechno.telavivfx.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;

public class InventoryUtil {
    public static int findItem(Item item) {
        var inv = MinecraftClient.getInstance().player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    public static boolean switchTo(int slot) {
        if (slot >= 0 && slot < 9) {
            MinecraftClient.getInstance().player.getInventory().setSelectedSlot(slot);
            return true;
        }
        return false;
    }
}