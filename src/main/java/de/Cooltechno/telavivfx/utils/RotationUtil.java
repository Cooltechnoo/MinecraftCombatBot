package de.Cooltechno.telavivfx.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;

public class RotationUtil {
    public static void apply(MinecraftClient client, Vec3d target) {
        float[] rots = calculate(client.player.getEyePos(), target);
        client.player.setYaw(rots[0]);
        client.player.setPitch(rots[1]);
    }

    private static float[] calculate(Vec3d from, Vec3d to) {
        double dX = to.x - from.x;
        double dY = to.y - from.y;
        double dZ = to.z - from.z;
        double dXZ = Math.sqrt(dX * dX + dZ * dZ);
        return new float[]{
                (float) Math.toDegrees(Math.atan2(dZ, dX)) - 90f,
                (float) -Math.toDegrees(Math.atan2(dY, dXZ))
        };
    }
}