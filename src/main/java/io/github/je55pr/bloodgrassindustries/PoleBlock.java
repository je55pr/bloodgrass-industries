package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

final class PoleBlock extends Block implements EntityBlock {
    static final int HEIGHT = 4;
    static final IntegerProperty SECTION = IntegerProperty.create("section", 0, HEIGHT - 1);
    private static final VoxelShape BASE = Block.box(3, 0, 3, 13, 16, 13);
    private static final VoxelShape POST = Block.box(6, 0, 6, 10, 16, 10);

    PoleBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(SECTION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SECTION);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(SECTION) == 0 ? BASE : POST;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos base = context.getClickedPos();
        Level level = context.getLevel();
        Player player = context.getPlayer();
        CollisionContext collision = player == null ? CollisionContext.empty() : CollisionContext.of(player);
        for (int section = 1; section < HEIGHT; section++) {
            BlockPos part = base.above(section);
            BlockState partState = defaultBlockState().setValue(SECTION, section);
            if (!level.isInWorldBounds(part) || !level.getBlockState(part).canBeReplaced()
                    || !level.isUnobstructed(partState, part, collision)) {
                return null;
            }
        }
        return defaultBlockState();
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        for (int section = 1; section < HEIGHT; section++) {
            level.setBlock(pos.above(section), state.setValue(SECTION, section), Block.UPDATE_ALL);
        }
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(SECTION) == 0 ? new PoleBlockEntity(pos, state) : null;
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        super.onRemove(state, level, pos, replacement, moved);
        if (level.isClientSide || replacement.is(this)) {
            return;
        }

        BlockPos base = pos.below(state.getValue(SECTION));
        for (int section = 0; section < HEIGHT; section++) {
            BlockPos part = base.above(section);
            BlockState partState = level.getBlockState(part);
            if (partState.is(this) && partState.getValue(SECTION) == section) {
                level.setBlock(part, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        }
    }

    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state, BlockEntity entity, ItemStack tool) {
        super.playerDestroy(level, player, pos, state, entity, tool);
        player.getInventory().placeItemBackInInventory(new ItemStack(this));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level instanceof ServerLevel server) {
            BlockPos base = pos.below(state.getValue(SECTION));
            int links = PowerGrid.of(server).showLinks(server, base);
            player.displayClientMessage(Component.translatable("message.bloodgrass_industries.pole_links", links), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
