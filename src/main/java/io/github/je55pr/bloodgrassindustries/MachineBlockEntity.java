package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

abstract class MachineBlockEntity extends BlockEntity {
    protected ItemStack input = ItemStack.EMPTY;
    protected ItemStack output = ItemStack.EMPTY;

    MachineBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    abstract void tick(ServerLevel level);

    abstract boolean accepts(ServerLevel level, ItemStack stack);

    abstract Component status(ServerLevel level);

    boolean insert(ServerLevel level, ItemStack stack, boolean creative) {
        if (!accepts(level, stack) || (!input.isEmpty() && !ItemStack.isSameItemSameComponents(input, stack))) {
            return false;
        }
        int count = Math.min(stack.getCount(), stack.getMaxStackSize() - input.getCount());
        if (count <= 0) {
            return false;
        }
        if (input.isEmpty()) {
            input = stack.copyWithCount(count);
        } else {
            input.grow(count);
        }
        if (!creative) {
            stack.shrink(count);
        }
        setChanged();
        return true;
    }

    ItemStack takeOutput() {
        ItemStack result = output;
        if (!result.isEmpty()) {
            output = ItemStack.EMPTY;
            setChanged();
        }
        return result;
    }

    void dropContents(Level level) {
        Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), input);
        Containers.dropItemStack(level, worldPosition.getX(), worldPosition.getY(), worldPosition.getZ(), output);
    }

    boolean canStoreOutput(ItemStack result) {
        return result.isEmpty() || output.isEmpty() || (ItemStack.isSameItemSameComponents(output, result)
                && output.getCount() + result.getCount() <= output.getMaxStackSize());
    }

    void storeOutput(ItemStack result) {
        if (output.isEmpty()) {
            output = result;
        } else {
            output.grow(result.getCount());
        }
    }

    void setLit(ServerLevel level, boolean lit) {
        BlockState state = getBlockState();
        if (state.getValue(MachineBlock.LIT) != lit) {
            ((MachineBlock) state.getBlock()).updateLit(level, worldPosition, state, lit);
        }
    }

    BoundingBox bounds() {
        BlockState state = getBlockState();
        return ((MachineBlock) state.getBlock()).bounds(worldPosition, state);
    }

    Component coverage(ServerLevel level) {
        return Component.translatable(PowerGrid.of(level).hasCoverage(bounds())
                ? "message.bloodgrass_industries.covered" : "message.bloodgrass_industries.uncovered");
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        input = ItemStack.parseOptional(registries, tag.getCompound("Input"));
        output = ItemStack.parseOptional(registries, tag.getCompound("Output"));
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Input", input.saveOptional(registries));
        tag.put("Output", output.saveOptional(registries));
    }
}
