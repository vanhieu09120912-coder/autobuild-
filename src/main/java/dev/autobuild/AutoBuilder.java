package dev.autobuild;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Moi lan toi luot: neu cam BlockItem tren tay, tim o trong trong tam voi ma schematic
 * (Litematica) co dung loai block do, tim goc click + huong nhin de block ra DUNG huong
 * (mo phong bang chinh logic dat block cua vanilla), roi dat. Co gioi han toc do.
 */
public final class AutoBuilder {

    public static boolean enabled = false;
    /** false = chi dat khi tia nhin that su trung mat block (an toan hon). true = xoay gia de ep huong. */
    public static boolean spoofRotation = false;
    public static double reach = 4.0;
    public static int delayTicks = 4;
    public static int placed = 0;

    private static final Random RNG = new Random();
    private static final double[] OFFSETS = {0.0, -0.3, 0.3};
    private static final int MAX_PLANS_PER_TICK = 10;
    private static final int MAX_TRIES = 4;

    private static final Property<?>[] ORIENTATION = {
        BlockStateProperties.FACING,
        BlockStateProperties.HORIZONTAL_FACING,
        BlockStateProperties.FACING_HOPPER,
        BlockStateProperties.AXIS,
        BlockStateProperties.HORIZONTAL_AXIS,
        BlockStateProperties.HALF,
        BlockStateProperties.ATTACH_FACE,
        BlockStateProperties.ORIENTATION
    };

    private static long tick = 0;
    private static int cooldown = 0;
    private static final Map<Long, Integer> tries = new HashMap<>();
    private static final Map<Long, Long> retryAt = new HashMap<>();

    private record Plan(BlockHitResult hit, float yaw, float pitch) {}

    private AutoBuilder() {}

    public static void reset() {
        tries.clear();
        retryAt.clear();
        cooldown = 0;
        placed = 0;
    }

    public static void onTick(Minecraft mc) {
        tick++;
        if (!enabled) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.gameMode == null || mc.screen != null) return;
        if (cooldown > 0) {
            cooldown--;
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof BlockItem item)) return;

        Level schem = SchematicWorldHandler.getSchematicWorld();
        if (schem == null) return;

        int plans = 0;
        for (BlockPos target : findTargets(player, level, schem, stack)) {
            long key = target.asLong();
            if (retryAt.getOrDefault(key, 0L) > tick) continue;
            if (plans++ >= MAX_PLANS_PER_TICK) break;

            BlockState want = schem.getBlockState(target);
            Plan plan = plan(player, level, item, stack, target, want);
            if (plan == null) {
                retryAt.put(key, tick + 40);
                continue;
            }

            place(mc, player, plan);
            placed++;
            int n = tries.merge(key, 1, Integer::sum);
            retryAt.put(key, n >= MAX_TRIES ? Long.MAX_VALUE : tick + 10L * n);
            cooldown = delayTicks + RNG.nextInt(3);
            return;
        }
    }

    // ---------------------------------------------------------------- tim muc tieu

    private static List<BlockPos> findTargets(LocalPlayer player, ClientLevel level, Level schem, ItemStack stack) {
        Vec3 eye = player.getEyePosition();
        int r = (int) Math.ceil(reach);
        BlockPos base = player.blockPosition();
        double max2 = reach * reach;
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r + 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(base.getX() + dx, base.getY() + dy, base.getZ() + dz);
                    if (Vec3.atCenterOf(m).distanceToSqr(eye) > max2) continue;

                    BlockState want = schem.getBlockState(m);
                    if (want.isAir() || want.getBlock().asItem() != stack.getItem()) continue;

                    BlockState have = level.getBlockState(m);
                    if (!have.isAir() && !have.canBeReplaced()) continue;

                    if (!inLayerRange(m)) continue;
                    out.add(m.immutable());
                }
            }
        }

        out.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
            .thenComparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(eye)));
        return out;
    }

    /** Ton trong layer ma Litematica dang hien (neu ban dung Layer mode). */
    private static boolean inLayerRange(BlockPos pos) {
        return DataManager.getRenderLayerRange().isPositionWithinRange(pos);
    }

    // ---------------------------------------------------------------- len ke hoach dat

    private static Plan plan(LocalPlayer player, ClientLevel level, BlockItem item, ItemStack stack,
                             BlockPos target, BlockState want) {
        Vec3 eye = player.getEyePosition();
        float oy = player.getYRot();
        float ox = player.getXRot();
        try {
            // Luot 1: tia nhin that su trung mat block de click (giong nguoi that)
            for (Direction d : Direction.values()) {
                BlockPos support = target.relative(d);
                Direction face = d.getOpposite();
                if (!usableSupport(level, support)) continue;
                Vec3 center = Vec3.atCenterOf(support);
                for (double a : OFFSETS) {
                    for (double b : OFFSETS) {
                        Vec3 point = facePoint(center, face, a, b);
                        double dist = eye.distanceTo(point);
                        if (dist > reach) continue;
                        Vec3 end = eye.add(point.subtract(eye).normalize().scale(dist + 0.6));
                        BlockHitResult hit = level.clip(new ClipContext(
                            eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
                        if (hit.getType() != HitResult.Type.BLOCK
                            || !hit.getBlockPos().equals(support)
                            || hit.getDirection() != face) continue;
                        float[] rot = rotationTo(eye, hit.getLocation());
                        if (simulate(player, item, stack, hit, rot[0], rot[1], want)) {
                            return new Plan(hit, rot[0], rot[1]);
                        }
                    }
                }
            }

            // Luot 2 (tuy chon): xoay gia de ep huong. De bi anticheat bat hon.
            if (spoofRotation) {
                float[] yaws = {0f, 90f, 180f, -90f};
                float[] pitches = {0f, 90f, -90f};
                for (Direction d : Direction.values()) {
                    BlockPos support = target.relative(d);
                    Direction face = d.getOpposite();
                    if (!usableSupport(level, support)) continue;
                    Vec3 point = Vec3.atCenterOf(support)
                        .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                    if (eye.distanceTo(point) > reach) continue;
                    BlockHitResult hit = new BlockHitResult(point, face, support, false);
                    for (float yaw : yaws) {
                        for (float pitch : pitches) {
                            if (simulate(player, item, stack, hit, yaw, pitch, want)) {
                                return new Plan(hit, yaw, pitch);
                            }
                        }
                    }
                }
            }
        } finally {
            player.setYRot(oy);
            player.setXRot(ox);
        }
        return null;
    }

    /** Chay dung logic dat block cua vanilla voi huong nhin gia dinh, xem ra state nao. */
    private static boolean simulate(LocalPlayer player, BlockItem item, ItemStack stack,
                                    BlockHitResult hit, float yaw, float pitch, BlockState want) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
        BlockState got = item.getPlacementState(ctx);
        return got != null && got.getBlock() == want.getBlock() && orientationMatches(want, got);
    }

    private static boolean orientationMatches(BlockState want, BlockState got) {
        for (Property<?> p : ORIENTATION) {
            if (want.hasProperty(p)) {
                if (!got.hasProperty(p) || !want.getValue(p).equals(got.getValue(p))) return false;
            }
        }
        return true;
    }

    private static boolean usableSupport(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.isAir() && !s.canBeReplaced() && !isInteractive(s.getBlock());
    }

    /** Click vao may khoi nay se mo/bam/doi trang thai thay vi dat block. */
    private static boolean isInteractive(Block b) {
        return b instanceof EntityBlock
            || b instanceof DiodeBlock
            || b instanceof ButtonBlock
            || b instanceof LeverBlock
            || b instanceof DoorBlock
            || b instanceof TrapDoorBlock
            || b instanceof FenceGateBlock
            || b instanceof NoteBlock
            || b instanceof CraftingTableBlock
            || b instanceof AnvilBlock
            || b instanceof RedStoneWireBlock
            || b instanceof ComposterBlock;
    }

    private static Vec3 facePoint(Vec3 center, Direction face, double a, double b) {
        double x = center.x + face.getStepX() * 0.5;
        double y = center.y + face.getStepY() * 0.5;
        double z = center.z + face.getStepZ() * 0.5;
        switch (face.getAxis()) {
            case X -> { y += a; z += b; }
            case Y -> { x += a; z += b; }
            default -> { x += a; y += b; }
        }
        return new Vec3(x, y, z);
    }

    private static float[] rotationTo(Vec3 eye, Vec3 target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horiz));
        return new float[] {Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
    }

    // ---------------------------------------------------------------- dat block

    private static void place(Minecraft mc, LocalPlayer player, Plan plan) {
        float oy = player.getYRot();
        float ox = player.getXRot();
        sendRot(player, plan.yaw(), plan.pitch());
        player.setYRot(plan.yaw());
        player.setXRot(plan.pitch());
        try {
            InteractionResult r = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, plan.hit());
            if (r.consumesAction()) player.swing(InteractionHand.MAIN_HAND);
        } finally {
            player.setYRot(oy);
            player.setXRot(ox);
            sendRot(player, oy, ox);
        }
    }

    private static void sendRot(LocalPlayer player, float yaw, float pitch) {
        player.connection.send(new ServerboundMovePlayerPacket.Rot(
            yaw, pitch, player.onGround(), player.horizontalCollision));
    }
}
