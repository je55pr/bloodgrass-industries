package io.github.je55pr.bloodgrassindustries;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

abstract class TransportBlock extends Block implements EntityBlock {
    TransportBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new TransportBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!(level instanceof ServerLevel server) || type != BloodgrassIndustries.TRANSPORT_ENTITY.get()) {
            return null;
        }
        return (world, pos, current, entity) -> ((TransportBlockEntity) entity).tick(server);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hit) {
        if (stack.isEmpty()) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof BlockItem) {
            return ItemInteractionResult.SKIP_DEFAULT_BLOCK_INTERACTION;
        }
        if (level instanceof ServerLevel server && level.getBlockEntity(pos) instanceof TransportBlockEntity transport
                && transport.offer(stack, manualInput(state, pos, hit), server.getGameTime())) {
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    abstract int manualInput(BlockState state, BlockPos pos, BlockHitResult hit);

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip, TooltipFlag flags) {
        tooltip.add(Component.translatable(getDescriptionId() + ".controls").withStyle(ChatFormatting.GRAY));
    }

    static void scheduleConnections(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        scheduleAt(server, pos);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(direction);
            scheduleAt(server, side);
            scheduleAt(server, side.above());
            scheduleAt(server, side.below());
        }
        scheduleAt(server, pos.above());
        scheduleAt(server, pos.below());
    }

    private static void scheduleAt(ServerLevel level, BlockPos pos) {
        if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof TransportBlock transport) {
            level.scheduleTick(pos, transport, 1);
        }
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState previous, boolean moved) {
        super.onPlace(state, level, pos, previous, moved);
        if (!previous.is(this)) {
            scheduleConnections(level, pos);
        }
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbor, BlockPos neighborPos,
                                   boolean moved) {
        if (level instanceof ServerLevel server) {
            server.scheduleTick(pos, this, 1);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState updated = resolveConnections(state, level, pos);
        if (updated != state) {
            level.setBlockAndUpdate(pos, updated);
            scheduleConnections(level, pos);
        }
    }

    abstract BlockState resolveConnections(BlockState state, ServerLevel level, BlockPos pos);

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!level.isClientSide && !replacement.is(this)
                && level.getBlockEntity(pos) instanceof TransportBlockEntity transport) {
            transport.dropCargo();
        }
        super.onRemove(state, level, pos, replacement, moved);
        if (!replacement.is(this)) {
            scheduleConnections(level, pos);
        }
    }
}
