package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.ItemHandlerHelper;

final class TransportTransfer {
    record Input(BlockPos pos, int slot) {
        boolean offer(ServerLevel level, ItemStack stack) {
            return level.getBlockEntity(pos) instanceof TransportBlockEntity target
                    && target.offer(stack, slot, level.getGameTime());
        }

        boolean hasSpace(ServerLevel level) {
            return level.getBlockEntity(pos) instanceof TransportBlockEntity target && target.canOffer(slot);
        }
    }

    private TransportTransfer() {}

    static Input inputAt(Level level, BlockPos nominal, Direction side) {
        if (!level.isLoaded(nominal)) {
            return null;
        }
        BlockState state = level.getBlockState(nominal);
        if (state.getBlock() instanceof SplitterBlock && SplitterBlock.port(state, side) == SplitterBlock.Port.INPUT) {
            return new Input(nominal, SplitterBlock.index(side));
        }
        return beltInputAt(level, nominal, side);
    }

    private static Input beltInputAt(Level level, BlockPos nominal, Direction side) {
        BlockState state = level.getBlockState(nominal);
        if (state.getBlock() instanceof BeltBlock && BeltBlock.inputPoint(nominal, state).equals(nominal)
                && BeltBlock.inputSide(state) == side) {
            return new Input(nominal, 0);
        }
        BlockPos lower = nominal.below();
        if (!level.isInWorldBounds(lower)) {
            return null;
        }
        BlockState lowerState = level.getBlockState(lower);
        if (lowerState.getBlock() instanceof BeltBlock && lowerState.getValue(BeltBlock.PROFILE) == BeltBlock.Profile.DOWN
                && BeltBlock.inputSide(lowerState) == side && BeltBlock.hasHeadroom(level, lower)) {
            return new Input(lower, 0);
        }
        return null;
    }

    static boolean hasPredecessor(Level level, BlockPos nominal, Direction side) {
        return hasDirectedPredecessor(level, nominal, side) || splitterCanFeed(level, nominal, side);
    }

    static boolean hasDirectedPredecessor(Level level, BlockPos nominal, Direction side) {
        BlockPos neighbor = nominal.relative(side);
        if (!level.isLoaded(neighbor)) {
            return false;
        }
        BlockState state = level.getBlockState(neighbor);
        if (state.is(BloodgrassIndustries.POWERED_FURNACE.get())
                && state.getValue(MachineBlock.FACING) == side.getOpposite()
                && level.getBlockEntity(neighbor) instanceof PoweredFurnace) {
            return true;
        }
        return hasBeltOutlet(level, neighbor, nominal);
    }

    static boolean splitterCanFeed(Level level, BlockPos nominal, Direction side) {
        BlockPos neighbor = nominal.relative(side);
        if (!level.isLoaded(neighbor) || !(level.getBlockState(neighbor).getBlock() instanceof SplitterBlock)) {
            return false;
        }
        SplitterBlock.Port override = splitterOverride(level, neighbor, side.getOpposite());
        return override == null || override == SplitterBlock.Port.OUTPUT;
    }

    static boolean hasBeltOutlet(Level level, BlockPos neighbor, BlockPos target) {
        return beltPointsTo(level, neighbor, target) || beltPointsTo(level, neighbor.below(), target);
    }

    private static boolean beltPointsTo(Level level, BlockPos pos, BlockPos target) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof BeltBlock && BeltBlock.outputPoint(pos, state).equals(target);
    }

    static SplitterBlock.Port splitterPort(ServerLevel level, BlockPos splitter, Direction side) {
        BlockPos neighbor = splitter.relative(side);
        Direction towardSplitter = side.getOpposite();
        BlockState state = level.getBlockState(neighbor);
        if (hasDirectedPredecessor(level, splitter, side)) {
            return SplitterBlock.Port.INPUT;
        }
        if (state.getBlock() instanceof SplitterBlock) {
            // Automatic faces have no preferred direction when both neighbours are splitters.
            SplitterBlock.Port other = splitterOverride(level, neighbor, towardSplitter);
            if (other == SplitterBlock.Port.INPUT) {
                return SplitterBlock.Port.OUTPUT;
            }
            return other == SplitterBlock.Port.OUTPUT ? SplitterBlock.Port.INPUT : SplitterBlock.Port.UNUSED;
        }
        if (beltInputAt(level, neighbor, towardSplitter) != null
                || (state.getBlock() instanceof MachineBlock machine
                    && machine.transportInput(level, neighbor, state, towardSplitter) != null)
                || level.getCapability(Capabilities.ItemHandler.BLOCK, neighbor, towardSplitter) != null) {
            return SplitterBlock.Port.OUTPUT;
        }
        return SplitterBlock.Port.UNUSED;
    }

    private static SplitterBlock.Port splitterOverride(Level level, BlockPos pos, Direction side) {
        return level.getBlockEntity(pos) instanceof TransportBlockEntity entity ? entity.portOverride(side) : null;
    }

    static boolean canSend(ServerLevel level, BlockPos nominal, Direction side, ItemStack item) {
        return transfer(level, nominal, side, item, true);
    }

    static boolean send(ServerLevel level, BlockPos nominal, Direction side, ItemStack item) {
        return transfer(level, nominal, side, item, false);
    }

    private static boolean transfer(ServerLevel level, BlockPos nominal, Direction side, ItemStack item, boolean simulate) {
        if (!level.isLoaded(nominal)) {
            return false;
        }
        Input transport = inputAt(level, nominal, side);
        if (transport != null) {
            return simulate ? transport.hasSpace(level) : transport.offer(level, item);
        }
        BlockState state = level.getBlockState(nominal);
        if (state.getBlock() instanceof MachineBlock machineBlock) {
            MachineBlockEntity machine = machineBlock.transportInput(level, nominal, state, side);
            return machine != null && machine.insertFromTransport(level, item, simulate);
        }
        var inventory = level.getCapability(Capabilities.ItemHandler.BLOCK, nominal, side);
        return inventory != null && ItemHandlerHelper.insertItem(inventory, item.copy(), simulate).isEmpty();
    }
}
