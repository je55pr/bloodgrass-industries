package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.block.state.BlockState;

final class PoweredFurnace extends MachineBlockEntity {
    private final RecipeManager.CachedCheck<SingleRecipeInput, SmeltingRecipe> recipes = RecipeManager.createCheck(RecipeType.SMELTING);
    private int progress;

    PoweredFurnace(BlockPos pos, BlockState state) {
        super(BloodgrassIndustries.furnaceType(), pos, state);
    }

    @Override
    void tick(ServerLevel level) {
        Direction facing = getBlockState().getValue(MachineBlock.FACING);
        TransportTransfer.Input belt = TransportTransfer.inputAt(level, worldPosition.relative(facing), facing.getOpposite());
        if (belt != null && !output.isEmpty() && belt.offer(level, output)) {
            output.shrink(1);
            setChanged();
        }
        setLit(level, (belt == null || output.isEmpty()) && smelt(level));
    }

    private boolean smelt(ServerLevel level) {
        if (input.isEmpty()) {
            return false;
        }
        SingleRecipeInput recipeInput = new SingleRecipeInput(input);
        var recipe = recipes.getRecipeFor(recipeInput, level);
        if (recipe.isEmpty()) {
            if (progress != 0) {
                progress = 0;
                setChanged();
            }
            return false;
        }
        SmeltingRecipe smelting = recipe.get().value();
        ItemStack result = smelting.assemble(recipeInput, level.registryAccess());
        if (result.isEmpty() || !canStoreOutput(result) || !PowerGrid.of(level).supply(level, this)) {
            return false;
        }
        if (++progress >= smelting.getCookingTime()) {
            input.shrink(1);
            storeOutput(result);
            progress = 0;
        }
        setChanged();
        return true;
    }

    @Override
    boolean accepts(ServerLevel level, ItemStack stack) {
        return recipes.getRecipeFor(new SingleRecipeInput(stack), level).isPresent();
    }

    @Override
    Component status(ServerLevel level) {
        return Component.translatable("message.bloodgrass_industries.furnace_status", coverage(level), input.getCount(), progress);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        progress = tag.getInt("Progress");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("Progress", progress);
    }
}
