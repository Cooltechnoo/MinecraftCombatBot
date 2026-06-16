package de.Cooltechno.telavivfx.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;


public class WorldUtil {
    public static PlayerEntity getClosestTarget(double range) {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.world.getPlayers().stream()
                .filter(p -> p != client.player && !p.isDead())
                .filter(p -> client.player.distanceTo(p) <= range)
                .findFirst().orElse(null);
    }
}