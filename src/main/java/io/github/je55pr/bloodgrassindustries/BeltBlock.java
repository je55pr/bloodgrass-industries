package io.github.je55pr.bloodgrassindustries;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

final class BeltBlock extends TransportBlock {
    static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    static final EnumProperty<Profile> PROFILE = EnumProperty.create("profile", Profile.class);
    static final double SURFACE = 2 / 16.0;
    private static final VoxelShape FLAT = box(0, 0, 0, 16, 2, 16);
    private static final VoxelShape[] RAMPS = makeRamps();

    enum Profile implements StringRepresentable {
        FLAT("flat"), LEFT("left"), RIGHT("right"), UP("up"), DOWN("down");

        private final String name;

        Profile(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        boolean ramp() {
            return this == UP || this == DOWN;
        }
    }

    BeltBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PROFILE, Profile.FLAT));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PROFILE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState flat = defaultBlockState().setValue(FACING, context.getHorizontalDirection());
        if (context instanceof BeltItem.ExtensionContext extension && extension.profile() != null) {
            return flat.setValue(PROFILE, extension.profile());
        }
        BlockState joined = matchCorner(flat, level, pos);
        if (hasPredecessor(level, pos, joined) && hasSuccessor(level, pos, joined)) {
            return joined;
        }

        if (hasHeadroom(level, pos)) {
            BlockState uphill = flat.setValue(PROFILE, Profile.UP);
            BlockState downhill = flat.setValue(PROFILE, Profile.DOWN);
            boolean connectsUp = hasPredecessor(level, pos, uphill) && hasSuccessor(level, pos, uphill);
            boolean connectsDown = hasPredecessor(level, pos, downhill) && hasSuccessor(level, pos, downhill);
            if (connectsUp != connectsDown) {
                return connectsUp ? uphill : downhill;
            }
        }
        return joined;
    }

    @Override
    BlockState resolveConnections(BlockState state, ServerLevel level, BlockPos pos) {
        return state.getValue(PROFILE).ramp() ? state : matchCorner(state, level, pos);
    }

    private static BlockState matchCorner(BlockState state, Level level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        Direction rear = facing.getOpposite();
        Direction left = facing.getCounterClockWise();
        Direction right = facing.getClockWise();
        Profile current = state.getValue(PROFILE);
        if (!level.isLoaded(pos.relative(inputSide(state)))) {
            return state;
        }
        if (TransportTransfer.hasDirectedPredecessor(level, pos, rear)) {
            return state.setValue(PROFILE, Profile.FLAT);
        }
        boolean fromLeft = TransportTransfer.hasDirectedPredecessor(level, pos, left);
        boolean fromRight = TransportTransfer.hasDirectedPredecessor(level, pos, right);
        if (!fromLeft && !fromRight && !TransportTransfer.splitterCanFeed(level, pos, rear)) {
            fromLeft = TransportTransfer.splitterCanFeed(level, pos, left);
            fromRight = TransportTransfer.splitterCanFeed(level, pos, right);
        }
        if (fromLeft && fromRight) {
            return current == Profile.RIGHT ? state : state.setValue(PROFILE, Profile.LEFT);
        }
        return state.setValue(PROFILE, fromLeft ? Profile.LEFT : fromRight ? Profile.RIGHT : Profile.FLAT);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flags) {
        super.appendHoverText(stack, context, tooltip, flags);
        tooltip.add(Component.translatable(getDescriptionId() + ".adjust").withStyle(ChatFormatting.GRAY));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof TransportBlockEntity transport) {
            if (transport.hasCargo()) {
                player.displayClientMessage(Component.translatable("message.bloodgrass_industries.belt_loaded"), true);
            } else {
                Profile next = switch (state.getValue(PROFILE)) {
                    case UP -> Profile.DOWN;
                    case DOWN -> Profile.FLAT;
                    default -> Profile.UP;
                };
                if (next.ramp() && !hasHeadroom(level, pos)) {
                    player.displayClientMessage(Component.translatable("message.bloodgrass_industries.belt_headroom"), true);
                } else {
                    BlockState changed = state.setValue(PROFILE, next);
                    level.setBlockAndUpdate(pos, next.ramp() ? changed : matchCorner(changed, level, pos));
                    scheduleConnections(level, pos);
                    player.displayClientMessage(Component.translatable("message.bloodgrass_industries.belt_profile",
                            Component.translatable("message.bloodgrass_industries.profile." + next.getSerializedName())), true);
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    int manualInput(BlockState state, BlockPos pos, BlockHitResult hit) {
        return 0;
    }

    static Direction inputSide(BlockState state) {
        Direction facing = state.getValue(FACING);
        return switch (state.getValue(PROFILE)) {
            case LEFT -> facing.getCounterClockWise();
            case RIGHT -> facing.getClockWise();
            default -> facing.getOpposite();
        };
    }

    static BlockPos inputPoint(BlockPos pos, BlockState state) {
        return state.getValue(PROFILE) == Profile.DOWN ? pos.above() : pos;
    }

    static BlockPos outputPoint(BlockPos pos, BlockState state) {
        BlockPos next = pos.relative(state.getValue(FACING));
        return state.getValue(PROFILE) == Profile.UP ? next.above() : next;
    }

    private static boolean hasPredecessor(Level level, BlockPos pos, BlockState state) {
        return TransportTransfer.hasPredecessor(level, inputPoint(pos, state), inputSide(state));
    }

    private static boolean hasSuccessor(Level level, BlockPos pos, BlockState state) {
        return TransportTransfer.inputAt(level, outputPoint(pos, state), state.getValue(FACING).getOpposite()) != null;
    }

    static boolean hasHeadroom(Level level, BlockPos pos) {
        BlockPos above = pos.above();
        return level.isInWorldBounds(above) && level.getBlockState(above).getCollisionShape(level, above).isEmpty();
    }

    static Vec3 cargoPosition(BlockState state, double progress) {
        Direction in = inputSide(state);
        Direction out = state.getValue(FACING);
        Profile profile = state.getValue(PROFILE);
        double entryWeight = 1 - progress;
        double exitWeight = progress;
        if (profile == Profile.LEFT || profile == Profile.RIGHT) {
            double angle = progress * Math.PI / 2;
            entryWeight = 1 - Math.sin(angle);
            exitWeight = 1 - Math.cos(angle);
        }
        double rise = switch (profile) {
            case UP -> progress;
            case DOWN -> 1 - progress;
            default -> 0;
        };
        return new Vec3(0.5 + (in.getStepX() * entryWeight + out.getStepX() * exitWeight) / 2,
                SURFACE + rise, 0.5 + (in.getStepZ() * entryWeight + out.getStepZ() * exitWeight) / 2);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Profile profile = state.getValue(PROFILE);
        if (!profile.ramp()) {
            return FLAT;
        }
        Direction high = state.getValue(FACING);
        if (profile == Profile.DOWN) {
            high = high.getOpposite();
        }
        return RAMPS[SplitterBlock.index(high)];
    }

    private static VoxelShape[] makeRamps() {
        VoxelShape[] ramps = new VoxelShape[4];
        for (Direction high : Direction.Plane.HORIZONTAL) {
            VoxelShape ramp = Shapes.empty();
            for (int step = 0; step < 8; step++) {
                int start = step * 2;
                int height = Math.min(16, start + 3);
                int bottom = Math.max(0, height - 3);
                VoxelShape slice = switch (high) {
                    case NORTH -> box(0, bottom, 14 - start, 16, height, 16 - start);
                    case SOUTH -> box(0, bottom, start, 16, height, start + 2);
                    case WEST -> box(14 - start, bottom, 0, 16 - start, height, 16);
                    case EAST -> box(start, bottom, 0, start + 2, height, 16);
                    default -> throw new IllegalStateException("Ramp direction must be horizontal");
                };
                ramp = Shapes.or(ramp, slice);
            }
            ramps[SplitterBlock.index(high)] = ramp.optimize();
        }
        return ramps;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        Profile profile = state.getValue(PROFILE);
        if (mirror != Mirror.NONE && (profile == Profile.LEFT || profile == Profile.RIGHT)) {
            state = state.setValue(PROFILE, profile == Profile.LEFT ? Profile.RIGHT : Profile.LEFT);
        }
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
}
