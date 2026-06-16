package de.Cooltechno.telavivfx.features;

import de.Cooltechno.telavivfx.utils.*;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class CombatBot {
    public enum BotMode { DRAIN }
    public BotMode currentMode = BotMode.DRAIN;

    private enum BotState { IDLE, ATTACKING, DRAIN_CYCLE, ELYTRA_FLY }
    private BotState currentState = BotState.IDLE;

    private enum AnchorPhase { IDLE, PLACING, CHARGING, DETONATING }
    private AnchorPhase currentAnchorPhase = AnchorPhase.IDLE;
    private BlockPos activeAnchorPos = null;

    public boolean enabled = false;
    private int tickDelay = 0;

    private long frenzyEndTime = 0;
    private long crystalPauseTime = 0;

    private int lastTargetTotems = -1;
    private long antiPopWindowEnd = 0;

    private final double MELEE_RANGE = 4.2;
    private BlockPos lastPlacedPos = null;
    private long lastPlacementTime = 0;

    private int elytraActivationTicks = 0;
    private long lastRocketActivationTime = 0;

    public void onTick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null) return;

        manageTotems(client);

        PlayerEntity target = WorldUtil.getClosestTarget(25.0);
        if (target == null) {
            currentState = BotState.IDLE;
            currentAnchorPhase = AnchorPhase.IDLE;
            activeAnchorPos = null;
            lastTargetTotems = -1;
            elytraActivationTicks = 0;
            return;
        }

        double dist = client.player.distanceTo(target);
        long now = System.currentTimeMillis();

        // Track if the target just popped a totem to adjust our timing windows
        int currentTotems = target.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING) ? 1 : 0;
        if (lastTargetTotems != -1 && currentTotems < lastTargetTotems) {
            antiPopWindowEnd = now + 400;
        }
        lastTargetTotems = currentTotems;

        boolean hasElytraEquipped = client.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        if (dist >= 10.0 || elytraActivationTicks > 0 || client.player.isGliding() || (hasElytraEquipped && !client.player.isOnGround())) {
            handleElytraFlightCycle(client, target, dist, now);
        } else {
            equipOptimalDefensiveChestplate(client);
            elytraActivationTicks = 0;
        }

        // Predict target movement based on their current velocity
        double predictionMultiplier = (now < antiPopWindowEnd) ? 2.0 : 1.5;
        Vec3d velocity = target.getVelocity();
        Vec3d predictedTargetPos = target.getEntityPos().add(velocity != null ? velocity.multiply(predictionMultiplier) : Vec3d.ZERO);
        BlockPos predictedFeet = BlockPos.ofFloored(predictedTargetPos);

        // Break shields if the target is actively blocking
        if (dist <= MELEE_RANGE && target.isBlocking()) {
            if (handleShieldBreak(client, target)) {
                frenzyEndTime = now + 400;
                currentState = BotState.ATTACKING;
            }
        }

        if (dist <= MELEE_RANGE && client.player.fallDistance > 0.4F && !client.player.isOnGround()) {
            if (executeMaceStrike(client, target)) {
                return;
            }
        }

        if (dist <= MELEE_RANGE && now > crystalPauseTime && now > frenzyEndTime && !target.isBlocking()) {
            executeSwordAttack(client, target);
        }

        if (tickDelay > 0) {
            tickDelay--;
            return;
        }

        if (dist <= MELEE_RANGE) {
            currentState = BotState.DRAIN_CYCLE;

            breakNearbyCrystals(client, target);

            if (currentAnchorPhase != AnchorPhase.IDLE && activeAnchorPos != null) {
                processAnchorPhaseCycle(client);
                return;
            }

            BlockPos crysPos = findBestCrystalPos(client, target, predictedFeet);
            BlockPos anchorPos = findBestAnchorPos(client, target, predictedFeet);

            // Prioritize anchor sequences if viable
            if (anchorPos != null && (crysPos == null || client.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR))) {
                activeAnchorPos = anchorPos;
                currentAnchorPhase = AnchorPhase.PLACING;
                processAnchorPhaseCycle(client);
                tickDelay = (now < antiPopWindowEnd) ? 0 : 1;
            }
            else if (crysPos != null) {
                executeHitCrystalRefined(client, crysPos);
                if (now < antiPopWindowEnd) tickDelay = 0;
            }
        } else {
            currentAnchorPhase = AnchorPhase.IDLE;
            activeAnchorPos = null;
        }
    }

    /**
     * Handles Elytra use, rocket tracking, and landing right at the target.
     */
    private void handleElytraFlightCycle(MinecraftClient client, PlayerEntity target, double dist, long now) {
        int elytraItemSlot = InventoryUtil.findItem(Items.ELYTRA);
        int fireworkRocketSlot = InventoryUtil.findItem(Items.FIREWORK_ROCKET);
        boolean hasElytraEquipped = client.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);

        // Equip Elytra if it's currently in the hotbar but not worn
        if (!hasElytraEquipped) {
            if (elytraItemSlot != -1) {
                InventoryUtil.switchTo(elytraItemSlot);
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
            }
            return;
        }

        if (!client.player.isGliding() && hasElytraEquipped) {
            if (client.player.isOnGround() && elytraActivationTicks == 0) {
                client.player.jump();
                elytraActivationTicks = 1;
            } else if (elytraActivationTicks > 0) {
                elytraActivationTicks++;
                if (elytraActivationTicks >= 3) {
                    client.options.jumpKey.setPressed(true);
                    elytraActivationTicks = 0;
                }
            }
            return;
        }

        if (client.player.isGliding()) {
            // Drop out of flight and swap back to defense armor if close enough
            if (dist <= 6.5) {
                Vec3d landingPoint = target.getEntityPos().add(target.getRotationVecClient().multiply(-0.5));
                RotationUtil.apply(client, landingPoint);

                client.options.jumpKey.setPressed(true);
                equipOptimalDefensiveChestplate(client);
                return;
            }

            client.options.jumpKey.setPressed(false);
            currentState = BotState.ELYTRA_FLY;

            // Aim flight path towards the target's lower body
            Vec3d flightAimTarget = target.getEntityPos().add(0.0, 0.5, 0.0);
            RotationUtil.apply(client, flightAimTarget);

            Vec3d currentVelocity = client.player.getVelocity();
            double horizontalSpeed = Math.sqrt(currentVelocity.x * currentVelocity.x + currentVelocity.z * currentVelocity.z);

            if (dist > 12.0 && horizontalSpeed < 0.7 && now - lastRocketActivationTime > 1400 && fireworkRocketSlot != -1) {
                InventoryUtil.switchTo(fireworkRocketSlot);
                if (client.player.getInventory().getSelectedSlot() == fireworkRocketSlot) {
                    client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                    lastRocketActivationTime = now;
                }
            }
        }
    }

    private void equipOptimalDefensiveChestplate(MinecraftClient client) {
        if (!client.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) return;

        int chestplateSlot = InventoryUtil.findItem(Items.NETHERITE_CHESTPLATE);
        if (chestplateSlot == -1) chestplateSlot = InventoryUtil.findItem(Items.DIAMOND_CHESTPLATE);

        if (chestplateSlot != -1) {
            InventoryUtil.switchTo(chestplateSlot);
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        }
    }

    private void processAnchorPhaseCycle(MinecraftClient client) {
        if (activeAnchorPos == null) {
            currentAnchorPhase = AnchorPhase.IDLE;
            return;
        }

        // Prevent self-damage if anchor position is above or too close to client feet
        if (client.player.squaredDistanceTo(activeAnchorPos.toCenterPos()) < 3.5 && client.player.getY() <= activeAnchorPos.getY()) {
            currentAnchorPhase = AnchorPhase.IDLE;
            activeAnchorPos = null;
            return;
        }

        int anchorItem = InventoryUtil.findItem(Items.RESPAWN_ANCHOR);
        int glowstoneItem = InventoryUtil.findItem(Items.GLOWSTONE);
        if (anchorItem == -1 || glowstoneItem == -1) {
            currentAnchorPhase = AnchorPhase.IDLE;
            activeAnchorPos = null;
            return;
        }

        var blockState = client.world.getBlockState(activeAnchorPos);

        switch (currentAnchorPhase) {
            case PLACING:
                if (blockState.isReplaceable()) {
                    InventoryUtil.switchTo(anchorItem);
                    if (client.player.getInventory().getSelectedSlot() == anchorItem) {
                        interactAirSafe(client, activeAnchorPos);
                    }
                } else if (blockState.isOf(Blocks.RESPAWN_ANCHOR)) {
                    currentAnchorPhase = AnchorPhase.CHARGING;
                } else {
                    currentAnchorPhase = AnchorPhase.IDLE;
                    activeAnchorPos = null;
                }
                break;

            case CHARGING:
                if (blockState.isOf(Blocks.RESPAWN_ANCHOR)) {
                    int charges = blockState.get(RespawnAnchorBlock.CHARGES);
                    if (charges == 0) {
                        InventoryUtil.switchTo(glowstoneItem);
                        if (client.player.getInventory().getSelectedSlot() == glowstoneItem) {
                            interactAirSafe(client, activeAnchorPos);
                        }
                    } else {
                        currentAnchorPhase = AnchorPhase.DETONATING;
                    }
                } else {
                    currentAnchorPhase = AnchorPhase.PLACING;
                }
                break;

            case DETONATING:
                if (blockState.isOf(Blocks.RESPAWN_ANCHOR)) {
                    int triggerItem = InventoryUtil.findItem(Items.NETHERITE_SWORD);
                    if (triggerItem == -1) triggerItem = InventoryUtil.findItem(Items.TOTEM_OF_UNDYING);

                    if (triggerItem != -1) {
                        InventoryUtil.switchTo(triggerItem);
                        if (client.player.getInventory().getSelectedSlot() == triggerItem) {
                            interactAirSafe(client, activeAnchorPos);
                            currentAnchorPhase = AnchorPhase.IDLE;
                            activeAnchorPos = null;
                        }
                    }
                } else {
                    currentAnchorPhase = AnchorPhase.IDLE;
                    activeAnchorPos = null;
                }
                break;

            default:
                currentAnchorPhase = AnchorPhase.IDLE;
                activeAnchorPos = null;
                break;
        }
    }

    private boolean handleShieldBreak(MinecraftClient client, PlayerEntity target) {
        int axeSlot = InventoryUtil.findItem(Items.NETHERITE_AXE);
        if (axeSlot == -1) axeSlot = InventoryUtil.findItem(Items.DIAMOND_AXE);

        if (axeSlot != -1) {
            executeAxeDisable(client, target);
            return true;
        } else {
            if (canSee(client, target.getEyePos())) {
                RotationUtil.apply(client, target.getEyePos());
                client.interactionManager.attackEntity(client.player, target);
                client.player.swingHand(Hand.MAIN_HAND);
            }

            BlockPos behindPos = findPosBehindTarget(target);
            if (behindPos != null && currentAnchorPhase == AnchorPhase.IDLE) {
                activeAnchorPos = behindPos;
                currentAnchorPhase = AnchorPhase.PLACING;
            }
            return false;
        }
    }

    private boolean executeMaceStrike(MinecraftClient client, PlayerEntity target) {
        int maceSlot = InventoryUtil.findItem(Items.MACE);
        if (maceSlot != -1 && canSee(client, target.getEyePos())) {
            InventoryUtil.switchTo(maceSlot);
            RotationUtil.apply(client, target.getEyePos());
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private void executeSwordAttack(MinecraftClient client, PlayerEntity target) {
        int sword = InventoryUtil.findItem(Items.NETHERITE_SWORD);
        if (sword != -1 && canSee(client, target.getEyePos())) {
            RotationUtil.apply(client, target.getEyePos());
            InventoryUtil.switchTo(sword);
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private BlockPos findBestAnchorPos(MinecraftClient client, PlayerEntity target, BlockPos predictedFeet) {
        return findBestPosGeneral(client, target, predictedFeet, Blocks.RESPAWN_ANCHOR.getDefaultState(), true);
    }

    private BlockPos findBestCrystalPos(MinecraftClient client, PlayerEntity target, BlockPos predictedFeet) {
        return findBestPosGeneral(client, target, predictedFeet, Blocks.OBSIDIAN.getDefaultState(), false);
    }

    /**
     * Scans surrounding blocks to find valid and optimal placement coordinates for crystals or anchors.
     */
    private BlockPos findBestPosGeneral(MinecraftClient client, PlayerEntity target, BlockPos predictedFeet, net.minecraft.block.BlockState stateToPlace, boolean isAnchor) {
        List<BlockPos> candidates = new ArrayList<>();

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = -1; y <= 2; y++) {
                    if (x == 0 && z == 0 && y >= 0 && y < 2) {
                        BlockPos p = predictedFeet.add(x, y, z);
                        if (client.world.getBlockState(p).isOf(stateToPlace.getBlock())) candidates.add(p);
                        continue;
                    }

                    BlockPos p = predictedFeet.add(x, y, z);
                    var state = client.world.getBlockState(p);

                    if (state.isOf(stateToPlace.getBlock())) {
                        candidates.add(p);
                    } else if (state.isReplaceable()) {
                        if (client.world.canPlace(stateToPlace, p, ShapeContext.absent())) {
                            if (hasNeighbor(client, p)) candidates.add(p);
                        }
                    }
                }
            }
        }

        return candidates.stream()
                .filter(p -> client.player.squaredDistanceTo(p.toCenterPos()) <= MELEE_RANGE * MELEE_RANGE)
                .filter(p -> isAnchor ? canSee(client, p.toCenterPos().add(0, 0.4, 0)) : canSee(client, p.toCenterPos()))
                .min(Comparator.comparingDouble(p -> {
                    double score = 0.0;

                    if (client.world.getBlockState(p).isOf(stateToPlace.getBlock())) {
                        score -= 1000.0;
                    }

                    if (isAnchor) {
                        if (p.getY() == predictedFeet.getY() + 1) score -= 5000.0;
                    } else {
                        if (p.getY() == predictedFeet.getY() - 1) score -= 4000.0;
                    }

                    score += p.getSquaredDistance(target.getBlockPos());
                    return score;
                })).orElse(null);
    }

    private void executeHitCrystalRefined(MinecraftClient client, BlockPos pos) {
        if (pos.getY() < client.player.getY()) return;
        if (pos.equals(lastPlacedPos) && System.currentTimeMillis() - lastPlacementTime < 10) return;

        int obi = InventoryUtil.findItem(Items.OBSIDIAN);
        int cry = InventoryUtil.findItem(Items.END_CRYSTAL);

        // Ensure support block exists
        var state = client.world.getBlockState(pos);
        if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) {
            if (obi != -1) {
                InventoryUtil.switchTo(obi);
                if (client.player.getInventory().getSelectedSlot() == obi) {
                    interactAirSafe(client, pos);
                }
            } else return;
        }

        if (cry != -1) {
            InventoryUtil.switchTo(cry);
            if (client.player.getInventory().getSelectedSlot() == cry) {
                interactAirSafe(client, pos);
                attemptInstantBreakRefined(client, pos);

                lastPlacedPos = pos;
                lastPlacementTime = System.currentTimeMillis();
            }
        }
    }

    /**
     * Finds a valid solid neighbor face to interact with for blind placements.
     */
    private void interactAirSafe(MinecraftClient client, BlockPos pos) {
        Direction side = null;
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.offset(d);
            if (!client.world.getBlockState(neighbor).isAir() && !client.world.getBlockState(neighbor).isReplaceable()) {
                side = d.getOpposite();
                break;
            }
        }
        if (side == null) side = Direction.UP;

        RotationUtil.apply(client, pos.toCenterPos());
        BlockHitResult hit = new BlockHitResult(pos.toCenterPos(), side, pos, false);
        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
        client.player.swingHand(Hand.MAIN_HAND);
    }

    private boolean hasNeighbor(MinecraftClient client, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.offset(d);
            if (!client.world.getBlockState(neighbor).isAir() && !client.world.getBlockState(neighbor).isReplaceable()) return true;
        }
        return false;
    }

    private BlockPos findPosBehindTarget(PlayerEntity target) {
        Direction facing = target.getHorizontalFacing();
        BlockPos behind = target.getBlockPos().offset(facing.getOpposite());
        if (!MinecraftClient.getInstance().world.getBlockState(behind).isReplaceable()) behind = behind.up();
        return MinecraftClient.getInstance().world.getBlockState(behind).isReplaceable() ? behind : null;
    }

    private void executeAxeDisable(MinecraftClient client, PlayerEntity target) {
        int axe = InventoryUtil.findItem(Items.NETHERITE_AXE);
        if (axe == -1) axe = InventoryUtil.findItem(Items.DIAMOND_AXE);
        if (axe != -1 && canSee(client, target.getEyePos())) {
            RotationUtil.apply(client, target.getEyePos());
            InventoryUtil.switchTo(axe);
            client.interactionManager.attackEntity(client.player, target);
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void breakNearbyCrystals(MinecraftClient client, PlayerEntity target) {
        for (var entity : client.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal && crystal.distanceTo(target) < 3.5) {
                if (client.player.distanceTo(crystal) <= MELEE_RANGE && canSee(client, crystal.getEntityPos())) {
                    RotationUtil.apply(client, crystal.getEntityPos());
                    client.interactionManager.attackEntity(client.player, crystal);
                    client.player.swingHand(Hand.MAIN_HAND);
                }
            }
        }
    }

    /**
     * Automatically pulls a totem into the offhand slot from inventory handlers when health drops.
     */
    private void manageTotems(MinecraftClient client) {
        if (client.currentScreen != null) return;

        boolean lowHealth = client.player.getHealth() <= 6.0F;
        if (client.player.hurtTime > 0 && !lowHealth) return;

        if (!client.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            for (int i = 9; i < 45; i++) {
                if (client.player.getInventory().getStack(i >= 36 ? i - 36 : i).isOf(Items.TOTEM_OF_UNDYING)) {
                    client.interactionManager.clickSlot(client.player.currentScreenHandler.syncId, i, 40, SlotActionType.SWAP, client.player);
                    break;
                }
            }
        }
    }

    private void attemptInstantBreakRefined(MinecraftClient client, BlockPos pos) {
        Vec3d expectedCrystalPos = pos.up().toCenterPos();
        for (var entity : client.world.getEntities()) {
            if (entity instanceof EndCrystalEntity crystal) {
                if (crystal.squaredDistanceTo(expectedCrystalPos) < 1.5 && canSee(client, crystal.getEntityPos())) {
                    RotationUtil.apply(client, crystal.getEntityPos());
                    client.interactionManager.attackEntity(client.player, crystal);
                    client.player.swingHand(Hand.MAIN_HAND);
                    break;
                }
            }
        }
    }

    /**
     * Line-of-sight check to ensure we can hit or place on the target vector.
     */
    private boolean canSee(MinecraftClient client, Vec3d targetPos) {
        Vec3d start = client.player.getEyePos();
        HitResult result = client.world.raycast(new RaycastContext(
                start, targetPos,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                client.player
        ));
        return result.getType() == HitResult.Type.MISS;
    }
}