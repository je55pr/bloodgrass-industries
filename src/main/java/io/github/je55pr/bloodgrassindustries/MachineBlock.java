package io.github.je55pr.bloodgrassindustries;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

final class MachineBlock extends Block implements EntityBlock {
    static final BooleanProperty LIT = BlockStateProperties.LIT;
    static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final int WIDTH = 2;
    private static final int PART_COUNT = 12;
    // Existing single-block machines load with part 0. New machines start at 1.
    private static final int LEGACY = 0;
    private static final int ANCHOR = 1;
    private static final IntegerProperty PART = IntegerProperty.create("part", LEGACY, PART_COUNT);

    private final int depth;
    private final int height;
    private final Supplier<? extends BlockEntityType<? extends MachineBlockEntity>> entityType;

    MachineBlock(Properties properties, int depth, int height,
                 Supplier<? extends BlockEntityType<? extends MachineBlockEntity>> entityType) {
        super(properties);
        this.depth = depth;
        this.height = height;
        this.entityType = entityType;
        registerDefaultState(stateDefinition.any().setValue(LIT, false).setValue(FACING, Direction.NORTH).setValue(PART, LEGACY));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT, FACING, PART);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockState anchor = defaultBlockState().setValue(FACING, facing).setValue(PART, ANCHOR);
        BlockPos base = context.getClickedPos();
        Level level = context.getLevel();
        Player player = context.getPlayer();
        CollisionContext collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        for (int part = ANCHOR + 1; part <= volume(); part++) {
            BlockPos pos = partPosition(base, facing, part);
            if (!level.isInWorldBounds(pos) || !level.getBlockState(pos).canBeReplaced()
                    || !level.isUnobstructed(anchor.setValue(PART, part), pos, collision)) {
                return null;
            }
        }
        return anchor;
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        Direction facing = state.getValue(FACING);
        for (int part = ANCHOR + 1; part <= volume(); part++) {
            level.setBlock(partPosition(pos, facing, part), state.setValue(PART, part), Block.UPDATE_ALL);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isAnchor(state) ? entityType.get().create(pos, state) : null;
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!(level instanceof ServerLevel server) || !isAnchor(state) || type != entityType.get()) {
            return null;
        }
        return (world, pos, current, entity) -> ((MachineBlockEntity) entity).tick(server);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level instanceof ServerLevel server && level.getBlockEntity(anchorPosition(pos, state)) instanceof MachineBlockEntity machine
                && !machine.insert(server, stack, player.getAbilities().instabuild)) {
            player.displayClientMessage(Component.translatable("message.bloodgrass_industries.input_rejected"), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server && level.getBlockEntity(anchorPosition(pos, state)) instanceof MachineBlockEntity machine) {
            ItemStack output = machine.takeOutput();
            if (output.isEmpty()) {
                player.displayClientMessage(machine.status(server), true);
            } else {
                player.getInventory().placeItemBackInInventory(output);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        boolean removed = !level.isClientSide && !replacement.is(this);
        if (removed && isAnchor(state) && level.getBlockEntity(pos) instanceof MachineBlockEntity machine) {
            machine.dropContents(level);
        }
        super.onRemove(state, level, pos, replacement, moved);
        if (removed && state.getValue(PART) != LEGACY) {
            Direction facing = state.getValue(FACING);
            BlockPos anchor = anchorPosition(pos, state);
            for (int part = ANCHOR; part <= volume(); part++) {
                BlockPos partPos = partPosition(anchor, facing, part);
                if (isPart(level.getBlockState(partPos), facing, part)) {
                    level.setBlock(partPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, BlockEntity entity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, entity, tool);
        popResource(level, pos, new ItemStack(this));
    }

    MachineBlockEntity transportInput(ServerLevel level, BlockPos pos, BlockState state, Direction side) {
        Direction rear = state.getValue(FACING).getOpposite();
        if (side != rear) {
            return null;
        }
        BlockPos anchor = anchorPosition(pos, state);
        BlockPos inlet = state.getValue(PART) == LEGACY ? anchor : anchor.relative(rear, depth - 1);
        return pos.equals(inlet) && level.isLoaded(anchor) && level.getBlockEntity(anchor) instanceof MachineBlockEntity machine
                ? machine : null;
    }

    BoundingBox bounds(BlockPos anchor, BlockState state) {
        BlockPos farCorner = state.getValue(PART) == LEGACY ? anchor : partPosition(anchor, state.getValue(FACING), volume());
        return BoundingBox.fromCorners(anchor, farCorner);
    }

    void updateLit(ServerLevel level, BlockPos anchor, BlockState state, boolean lit) {
        if (state.getValue(PART) == LEGACY) {
            level.setBlock(anchor, state.setValue(LIT, lit), Block.UPDATE_CLIENTS);
            return;
        }
        Direction facing = state.getValue(FACING);
        for (int part = ANCHOR; part <= volume(); part++) {
            BlockPos pos = partPosition(anchor, facing, part);
            if (level.isLoaded(pos)) {
                BlockState current = level.getBlockState(pos);
                if (isPart(current, facing, part) && current.getValue(LIT) != lit) {
                    level.setBlock(pos, current.setValue(LIT, lit), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    private BlockPos anchorPosition(BlockPos pos, BlockState state) {
        int part = state.getValue(PART);
        return part <= ANCHOR ? pos : pos.subtract(partPosition(BlockPos.ZERO, state.getValue(FACING), part));
    }

    private BlockPos partPosition(BlockPos anchor, Direction facing, int part) {
        int index = part - ANCHOR;
        return anchor.relative(facing.getCounterClockWise(), index % WIDTH)
                .relative(facing.getOpposite(), index / WIDTH % depth)
                .above(index / (WIDTH * depth));
    }

    private int volume() {
        return WIDTH * depth * height;
    }

    private static boolean isAnchor(BlockState state) {
        return state.getValue(PART) <= ANCHOR;
    }

    private boolean isPart(BlockState state, Direction facing, int part) {
        return state.is(this) && state.getValue(FACING) == facing && state.getValue(PART) == part;
    }
}
