package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;

final class BurnerGenerator extends MachineBlockEntity {
    private static final int POWER_PER_TICK = 4;
    private int fuelTicks;
    private int availablePower;
    private long lastSupplyTick = Long.MIN_VALUE;

    BurnerGenerator(BlockPos pos, BlockState state) {
        super(BloodgrassIndustries.burnerType(), pos, state);
    }

    @Override
    void tick(ServerLevel level) {
        setLit(level, lastSupplyTick >= level.getGameTime() - 1);
    }

    boolean supply(long gameTick) {
        if (lastSupplyTick != gameTick) {
            if (fuelTicks == 0 && !startBurn()) {
                return false;
            }
            lastSupplyTick = gameTick;
            fuelTicks--;
            availablePower = POWER_PER_TICK;
            setChanged();
        }
        if (availablePower == 0) {
            return false;
        }
        availablePower--;
        return true;
    }

    private boolean startBurn() {
        if (input.isEmpty()) {
            return false;
        }
        int duration = input.getBurnTime(RecipeType.SMELTING);
        ItemStack remainder = input.getCraftingRemainingItem();
        if (duration <= 0 || !canStoreOutput(remainder)) {
            return false;
        }
        input.shrink(1);
        storeOutput(remainder);
        fuelTicks = duration;
        return true;
    }

    @Override
    boolean accepts(ServerLevel level, ItemStack stack) {
        return stack.getBurnTime(RecipeType.SMELTING) > 0;
    }

    @Override
    Component status(ServerLevel level) {
        return Component.translatable("message.bloodgrass_industries.generator_status", coverage(level), input.getCount(), fuelTicks);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel server) {
            PowerGrid.of(server).addGenerator(worldPosition, bounds());
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
            PowerGrid.of(server).removeGenerator(worldPosition);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        fuelTicks = tag.getInt("FuelTicks");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("FuelTicks", fuelTicks);
    }
}
