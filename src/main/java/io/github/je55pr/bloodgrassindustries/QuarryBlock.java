package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

final class QuarryBlock extends Block implements EntityBlock {
    QuarryBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new QuarryBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != BloodgrassIndustries.QUARRY_ENTITY.get()) {
            return null;
        }
        return (world, pos, current, entity) -> QuarryBlockEntity.tick(world, pos, current, (QuarryBlockEntity) entity);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof QuarryBlockEntity quarry) {
            serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, viewer) -> new QuarryMenu(id, quarry), getName()), buffer -> {
                buffer.writeBlockPos(pos);
                QuarryPayload.writeSettings(buffer, quarry.settings());
                buffer.writeNbt(quarry.finish().save());
                buffer.writeBoolean(quarry.hasLastOutput());
                buffer.writeNbt(QuarryMaterials.saveOutput(quarry.lastOutput()));
            }).ifPresent(id -> quarry.sendMaterialSurvey(serverPlayer, id));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
