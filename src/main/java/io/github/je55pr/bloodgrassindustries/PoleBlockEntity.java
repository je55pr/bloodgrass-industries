package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

final class PoleBlockEntity extends BlockEntity {
    PoleBlockEntity(BlockPos pos, BlockState state) {
        super(BloodgrassIndustries.POLE_ENTITY.get(), pos, state);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            PowerGrid.of(server).addPole(worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        unregister();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        unregister();
        super.setRemoved();
    }

    private void unregister() {
        if (level instanceof ServerLevel server) {
            PowerGrid.of(server).removePole(worldPosition);
        }
    }
}
