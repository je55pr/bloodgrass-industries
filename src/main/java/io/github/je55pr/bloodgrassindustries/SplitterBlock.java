package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class SplitterBlock extends TransportBlock {
    private static final EnumProperty<Port> NORTH = EnumProperty.create("north", Port.class);
    private static final EnumProperty<Port> EAST = EnumProperty.create("east", Port.class);
    private static final EnumProperty<Port> SOUTH = EnumProperty.create("south", Port.class);
    private static final EnumProperty<Port> WEST = EnumProperty.create("west", Port.class);
    private static final VoxelShape SHAPE = box(0, 0, 0, 16, 2, 16);

    enum Port implements StringRepresentable {
        UNUSED("unused"), INPUT("input"), OUTPUT("output");

        private final String name;

        Port(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    SplitterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any());
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST);
    }

    @Override
    BlockState resolveConnections(BlockState state, ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof TransportBlockEntity splitter)) {
            return state;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            Port override = splitter.portOverride(side);
            if (override != null) {
                state = withPort(state, side, override);
            } else if (level.isLoaded(pos.relative(side))) {
                state = withPort(state, side, TransportTransfer.splitterPort(level, pos, side));
            }
        }
        return state;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof TransportBlockEntity splitter) {
            Direction side = clickedPort(pos, hit);
            Port override = splitter.cyclePortOverride(side);
            BlockState next = resolveConnections(state, server, pos);
            if (next != state) {
                level.setBlockAndUpdate(pos, next);
            }
            scheduleConnections(level, pos);
            Component role = Component.translatable("message.bloodgrass_industries.port." + port(next, side).getSerializedName());
            if (override == null) {
                role = Component.translatable("message.bloodgrass_industries.port.auto", role);
            }
            player.displayClientMessage(Component.translatable("message.bloodgrass_industries.splitter_port",
                    Component.translatable("message.bloodgrass_industries.direction." + side.getName()), role), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    int manualInput(BlockState state, BlockPos pos, BlockHitResult hit) {
        Direction side = clickedPort(pos, hit);
        return port(state, side) == Port.INPUT ? index(side) : -1;
    }

    static int index(Direction side) {
        return switch (side) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("Port direction must be horizontal");
        };
    }

    static Direction side(int index) {
        return switch (index) {
            case 0 -> Direction.NORTH;
            case 1 -> Direction.EAST;
            case 2 -> Direction.SOUTH;
            case 3 -> Direction.WEST;
            default -> throw new IllegalArgumentException("Invalid port index");
        };
    }

    static Port port(BlockState state, Direction side) {
        return state.getValue(property(side));
    }

    static BlockState withPort(BlockState state, Direction side, Port port) {
        return state.setValue(property(side), port);
    }

    private static EnumProperty<Port> property(Direction side) {
        return switch (side) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> throw new IllegalArgumentException("Port direction must be horizontal");
        };
    }

    private static Direction clickedPort(BlockPos pos, BlockHitResult hit) {
        if (hit.getDirection().getAxis().isHorizontal()) {
            return hit.getDirection();
        }
        Vec3 point = hit.getLocation().subtract(Vec3.atCenterOf(pos));
        if (Math.abs(point.x) > Math.abs(point.z)) {
            return point.x > 0 ? Direction.EAST : Direction.WEST;
        }
        return point.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState rotated = state;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            rotated = withPort(rotated, rotation.rotate(side), port(state, side));
        }
        return rotated;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState mirrored = state;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            mirrored = withPort(mirrored, mirror.mirror(side), port(state, side));
        }
        return mirrored;
    }
}
