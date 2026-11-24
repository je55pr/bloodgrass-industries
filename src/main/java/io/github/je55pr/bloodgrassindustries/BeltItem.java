package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class BeltItem extends BlockItem {
    record Preview(BlockPos pos, BlockState state, boolean valid) {}

    BeltItem(BeltBlock belt, Properties properties) {
        super(belt, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context instanceof ExtensionContext extension) {
            return place(extension);
        }
        if (context.getPlayer() == null) {
            return super.useOn(context);
        }
        var hit = new BlockHitResult(context.getClickLocation(), context.getClickedFace(), context.getClickedPos(), context.isInside());
        BlockPlaceContext placement = resolve(context.getPlayer(), context.getHand(), hit);
        return placement == null ? InteractionResult.FAIL : place(placement);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(player.blockInteractionRange()));
        BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        ExtensionContext extension = findExtension(player, hand, eye, hit.getLocation());
        if (extension == null) {
            return InteractionResultHolder.pass(stack);
        }
        return new InteractionResultHolder<>(stack.useOn(extension), player.getItemInHand(hand));
    }

    Preview preview(Player player, InteractionHand hand, BlockHitResult hit) {
        BlockPlaceContext context = resolve(player, hand, hit);
        if (context == null || !available(context.getLevel(), context.getClickedPos())) {
            return null;
        }
        BlockState state = getBlock().getStateForPlacement(context);
        return new Preview(context.getClickedPos(), state, context.canPlace() && canPlace(context, state));
    }

    private static BlockPlaceContext resolve(Player player, InteractionHand hand, BlockHitResult hit) {
        Level level = player.level();
        if (hit != null && hit.getType() == HitResult.Type.BLOCK && level.isLoaded(hit.getBlockPos())
                && !player.isShiftKeyDown()) {
            BlockPos clicked = hit.getBlockPos();
            BlockState state = level.getBlockState(clicked);
            if (state.getBlock() instanceof BeltBlock) {
                BlockPos outlet = BeltBlock.outputPoint(clicked, state);
                return available(level, outlet)
                        ? new ExtensionContext(player, hand, outlet, state.getValue(BeltBlock.FACING), null) : null;
            }
        }

        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        Vec3 end = hit == null ? eye.add(player.getLookAngle().scale(reach)) : hit.getLocation();
        Vec3 offset = end.subtract(eye);
        if (offset.lengthSqr() > reach * reach) {
            end = eye.add(offset.normalize().scale(reach));
        }
        ExtensionContext extension = findExtension(player, hand, eye, end);
        if (extension != null) {
            return extension;
        }
        return hit == null || hit.getType() != HitResult.Type.BLOCK
                ? null : new BlockPlaceContext(player, hand, player.getItemInHand(hand), hit);
    }

    private static ExtensionContext findExtension(Player player, InteractionHand hand, Vec3 eye, Vec3 end) {
        if (!player.getAbilities().mayBuild || player.isSpectator()) {
            return null;
        }
        // Only examine cells the crosshair ray reaches before it hits solid geometry.
        return BlockGetter.traverseBlocks(eye, end, player, (builder, aimedCell) -> {
            Level level = builder.level();
            AABB aimArea = new AABB(aimedCell).deflate(0.12);
            if (aimArea.contains(eye) || aimArea.clip(eye, end).isEmpty()
                    || !available(level, aimedCell) || !level.getBlockState(aimedCell).canBeReplaced()) {
                return null;
            }
            if (builder.isShiftKeyDown()) {
                BlockPos lower = aimedCell.below();
                Direction climb = outletAt(level, lower);
                Direction descent = outletAt(level, aimedCell.above());
                if (climb == null && descent != null) {
                    return new ExtensionContext(builder, hand, aimedCell, descent, BeltBlock.Profile.DOWN);
                }
                if (climb != null && descent == null) {
                    return new ExtensionContext(builder, hand, lower, climb, BeltBlock.Profile.UP);
                }
            } else {
                Direction outlet = outletAt(level, aimedCell);
                if (outlet != null && !isRampHeadroom(level, aimedCell)) {
                    Direction look = builder.getDirection();
                    Direction facing = look == outlet.getOpposite() ? outlet : look;
                    return new ExtensionContext(builder, hand, aimedCell, facing, null);
                }
            }
            return null;
        }, ignored -> null);
    }

    private static Direction outletAt(Level level, BlockPos target) {
        if (!available(level, target) || !level.getBlockState(target).canBeReplaced()) {
            return null;
        }
        Direction facing = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = target.relative(side);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            if (TransportTransfer.hasBeltOutlet(level, neighbor, target)) {
                if (facing != null) {
                    return null;
                }
                facing = side.getOpposite();
            }
        }
        return facing;
    }

    @Override
    protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        Player player = context.getPlayer();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!available(level, pos) || (player != null && !level.mayInteract(player, pos)) || isRampHeadroom(level, pos)) {
            return false;
        }
        if (context instanceof ExtensionContext && (player == null || !player.getAbilities().mayBuild
                || !player.canInteractWithBlock(pos, 0))) {
            return false;
        }
        return (!state.getValue(BeltBlock.PROFILE).ramp() || BeltBlock.hasHeadroom(level, pos))
                && super.canPlace(context, state);
    }

    private static boolean available(Level level, BlockPos pos) {
        return level.isInWorldBounds(pos) && level.getWorldBorder().isWithinBounds(pos) && level.isLoaded(pos);
    }

    private static boolean isRampHeadroom(Level level, BlockPos pos) {
        BlockState lower = level.getBlockState(pos.below());
        return lower.getBlock() instanceof BeltBlock && lower.getValue(BeltBlock.PROFILE).ramp();
    }

    static final class ExtensionContext extends BlockPlaceContext {
        private final Direction facing;
        private final BeltBlock.Profile profile;

        private ExtensionContext(Player player, InteractionHand hand, BlockPos target, Direction facing, BeltBlock.Profile profile) {
            super(player, hand, player.getItemInHand(hand), new BlockHitResult(Vec3.atCenterOf(target), Direction.UP, target, false));
            this.facing = facing;
            this.profile = profile;
            replaceClicked = true;
        }

        @Override
        public boolean canPlace() {
            return getLevel().getBlockState(getClickedPos()).canBeReplaced(this);
        }

        @Override
        public Direction getHorizontalDirection() {
            return facing;
        }

        BeltBlock.Profile profile() {
            return profile;
        }
    }
}
