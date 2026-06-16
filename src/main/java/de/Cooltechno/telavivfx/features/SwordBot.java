package de.Cooltechno.telavivfx.features;

import de.Cooltechno.telavivfx.utils.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SwordBot {
    public boolean enabled = false;

    private final double ATTACK_RANGE = 3.0;
    private final double IDEAL_DIST_LOW = 2.7;
    private final double IDEAL_DIST_HIGH = 3.1;
    private final float BASE_AIM_SMOOTHING = 1.3F;

    private int localHurtTimeTracker = 0;
    private int incomingHitsConsecutive = 0;
    private long escapeEndTime = 0;

    private int wTapTicks = 0;
    private boolean strafeRight = true;
    private int strafeChangeTicks = 0;

    private int comboCount = 0;
    private boolean needsSpacingOvershoot = false;

    private File logFile;
    private final ExecutorService logExecutor = Executors.newSingleThreadExecutor();
    private long botSessionId;
    private int tickCounter = 0;

    public SwordBot() {
        try {
            File logDir = new File(MinecraftClient.getInstance().runDirectory, "logs/swordbot_analysis");
            if (!logDir.exists()) logDir.mkdirs();

            this.botSessionId = System.currentTimeMillis();
            String dateStr = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            this.logFile = new File(logDir, "bot_log_" + dateStr + "_" + botSessionId + ".txt");

            logErrorAsync("SYSTEM", "Bot-Instanz erfolgreich initialisiert. Start der Aufzeichnung.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onTick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null) return;
        tickCounter++;

        PlayerEntity target = WorldUtil.getClosestTarget(15.0);
        if (target == null) {
            resetBotState();
            resetMovementKeys(client);
            return;
        }

        double distance = client.player.distanceTo(target);
        long now = System.currentTimeMillis();

        // Track incoming hits to track combo breaks and damage status
        if (client.player.hurtTime > localHurtTimeTracker && client.player.hurtTime == 10) {
            if (client.player.isOnGround()) {
                client.player.jump();
            }
            incomingHitsConsecutive++;

            if (comboCount > 0) {
                logErrorAsync("COMBO_BREAK", String.format("Eigene Combo nach %d Treffern unterbrochen. Gegner-Distanz: %.2f", comboCount, distance));
            }
            comboCount = 0;
        }
        localHurtTimeTracker = client.player.hurtTime;

        if (incomingHitsConsecutive >= 2 && now > escapeEndTime) {
            logErrorAsync("ANTI_COMBO_TRIGGERED", String.format("2+ Hits in Folge kassiert! Schalte in Fluchtmodus für 2 Sek. Aktuelle Distanz: %.2f", distance));
            escapeEndTime = now + 2000;
            incomingHitsConsecutive = 0;
        }

        if (distance <= ATTACK_RANGE + 3.0) {
            rotatePredictiveAndSmooth(client, target);
        }

        // Process movement modifications based on defensive states or current pacing adjustments
        if (now < escapeEndTime) {
            client.options.forwardKey.setPressed(false);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.backKey.setPressed(true);
            client.player.setSprinting(false);
        } else if (needsSpacingOvershoot) {
            if (distance < 3.1) {
                client.options.forwardKey.setPressed(false);
                client.options.leftKey.setPressed(false);
                client.options.rightKey.setPressed(false);
                client.options.backKey.setPressed(true);
                client.player.setSprinting(false);
            } else {
                needsSpacingOvershoot = false;
            }
        } else {
            handleVanillaMovement(client, distance);

            if (comboCount >= 2 && distance <= ATTACK_RANGE && client.player.isOnGround()) {
                client.player.jump();
            }
        }

        // Handle target validation and weapon switching when weapon cooldown is ready
        if (distance <= ATTACK_RANGE && now > escapeEndTime) {
            if (client.player.getAttackCooldownProgress(0.0F) >= 1.0F) {

                int swordSlot = InventoryUtil.findItem(Items.NETHERITE_SWORD);
                if (swordSlot == -1) swordSlot = InventoryUtil.findItem(Items.DIAMOND_SWORD);

                if (swordSlot != -1) {
                    Entity hitEntity = getTargetedEntityVanilla(client, ATTACK_RANGE);

                    if (hitEntity != target) {
                        double diffX = target.getX() - client.player.getX();
                        double diffZ = target.getZ() - client.player.getZ();
                        float targetYaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0F;
                        float deltaYaw = MathHelper.wrapDegrees(targetYaw - client.player.getYaw());

                        logErrorAsync("MISSED_OPPORTUNITY", String.format(
                                "Cooldown bereit, aber Schlag blockiert (Fadenkreuz verfehlt BoundingBox). Distanz: %.2f | Yaw-Abweichung: %.2f° | Target-Velocity: %s",
                                distance, deltaYaw, target.getVelocity().toString()
                        ));
                    }

                    if (hitEntity == target) {
                        InventoryUtil.switchTo(swordSlot);

                        client.interactionManager.attackEntity(client.player, target);
                        client.player.swingHand(Hand.MAIN_HAND);

                        if (distance < 2.95) {
                            needsSpacingOvershoot = true;
                        }

                        incomingHitsConsecutive = 0;
                        comboCount++;

                        if (client.player.isOnGround()) {
                            wTapTicks = 1;
                        }
                    }
                }
            }
        }
    }

    private void handleVanillaMovement(MinecraftClient client, double distance) {
        if (wTapTicks > 0) {
            wTapTicks--;
            client.options.forwardKey.setPressed(false);
        } else {
            if (distance > IDEAL_DIST_HIGH) {
                client.options.backKey.setPressed(false);
                client.options.forwardKey.setPressed(true);
            } else if (distance < IDEAL_DIST_LOW) {
                client.options.forwardKey.setPressed(false);
                client.options.backKey.setPressed(true);
            } else {
                client.options.backKey.setPressed(false);
                client.options.forwardKey.setPressed(true);
            }
        }

        if (strafeChangeTicks <= 0) {
            strafeRight = !strafeRight;
            strafeChangeTicks = 12 + (int) (Math.random() * 15);
        } else {
            strafeChangeTicks--;
        }

        if (distance <= ATTACK_RANGE + 1.5) {
            client.options.leftKey.setPressed(!strafeRight);
            client.options.rightKey.setPressed(strafeRight);
        } else {
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
        }
    }

    /**
     * Calculates smoothed, predictive look rotations toward the target's estimated position.
     */
    private void rotatePredictiveAndSmooth(MinecraftClient client, PlayerEntity target) {
        Vec3d targetVelocity = target.getVelocity();

        double predictedX = target.getX() + (targetVelocity.x * 0.35);
        double predictedZ = target.getZ() + (targetVelocity.z * 0.35);
        double predictedY = target.getEyeY() - 0.45;

        Vec3d predictedPos = new Vec3d(predictedX, predictedY, predictedZ);
        Vec3d playerEyePos = client.player.getEyePos();

        double diffX = predictedPos.x - playerEyePos.x;
        double diffY = predictedPos.y - playerEyePos.y;
        double diffZ = predictedPos.z - playerEyePos.z;

        double diffXZ = Math.sqrt(diffX * diffX + diffZ * diffZ);

        float targetYaw = (float) Math.toDegrees(Math.atan2(diffZ, diffX)) - 90.0F;
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(diffY, diffXZ)));

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float deltaYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float deltaPitch = MathHelper.wrapDegrees(targetPitch - currentPitch);

        float absoluteDelta = Math.abs(deltaYaw) + Math.abs(deltaPitch);
        float dynamicSmoothing = BASE_AIM_SMOOTHING;

        if (absoluteDelta < 5.0F) {
            dynamicSmoothing = 1.0F;
        } else if (absoluteDelta > 12.0F) {
            dynamicSmoothing = 1.05F;
        }

        float smoothedYaw = currentYaw + (deltaYaw / dynamicSmoothing);
        float smoothedPitch = currentPitch + (deltaPitch / dynamicSmoothing);

        smoothedPitch = MathHelper.clamp(smoothedPitch, -90.0F, 90.0F);

        client.player.setYaw(smoothedYaw);
        client.player.setPitch(smoothedPitch);
    }

    /**
     * Replicates vanilla client raycasting mechanics to look for attackable Players along the view vector.
     */
    private Entity getTargetedEntityVanilla(MinecraftClient client, double range) {
        if (client.player == null || client.world == null) return null;

        Vec3d eyePos = client.player.getEyePos();

        float yawRad = client.player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        float pitchRad = client.player.getPitch() * MathHelper.RADIANS_PER_DEGREE;

        float f1 = MathHelper.sin(-yawRad - MathHelper.PI);
        float f2 = MathHelper.cos(-yawRad - MathHelper.PI);
        float f3 = -MathHelper.cos(-pitchRad);
        float f4 = MathHelper.sin(-pitchRad);

        Vec3d lookDir = new Vec3d(f1 * f3, f4, f2 * f3);
        Vec3d rayEnd = eyePos.add(lookDir.multiply(range));

        Box searchBox = client.player.getBoundingBox().stretch(lookDir.multiply(range)).expand(1.0, 1.0, 1.0);

        EntityHitResult hitResult = ProjectileUtil.raycast(
                client.player,
                eyePos,
                rayEnd,
                searchBox,
                entity -> !entity.isSpectator() && entity.canHit(),
                range * range
        );

        if (hitResult != null) {
            return hitResult.getEntity();
        }

        return null;
    }
    private void logErrorAsync(String errorType, String message) {
        if (logFile == null) return;

        logExecutor.submit(() -> {
            try (FileWriter fw = new FileWriter(logFile, true);
                 PrintWriter pw = new PrintWriter(fw)) {

                String timeStamp = new SimpleDateFormat("HH:mm:ss.SSS").format(new Date());
                pw.printf("[%s] [Tick:%d] [%s]: %s%n", timeStamp, tickCounter, errorType, message);

            } catch (IOException e) {
                // Ignore
            }
        });
    }

    private void resetBotState() {
        localHurtTimeTracker = 0;
        incomingHitsConsecutive = 0;
        escapeEndTime = 0;
        wTapTicks = 0;
        comboCount = 0;
        needsSpacingOvershoot = false;
    }

    private void resetMovementKeys(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.backKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }
}