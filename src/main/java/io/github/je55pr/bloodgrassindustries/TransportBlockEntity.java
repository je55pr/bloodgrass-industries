package io.github.je55pr.bloodgrassindustries;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.items.IItemHandler;

final class TransportBlockEntity extends BlockEntity {
    static final int SLOT_COUNT = 4;
    private static final int MOVE_TICKS = 4;
    private static final int NO_ROUTE = -1;
    private static final int VISUAL_DELAY = 3;
    private static final int VISUAL_HISTORY = 8;
    private static final String VISUAL_TICK = "VisualTick";
    private static final String READY_AT_KEY = "ReadyAt";

    record VisualSlot(ItemStack cargo, long readyAt, int routeFrom) {}

    record VisualFrame(long tick, BlockState state, List<VisualSlot> slots) {}
    private final NonNullList<ItemStack> cargo = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
    private final long[] readyAt = new long[SLOT_COUNT];
    private final int[] routeFrom = new int[SLOT_COUNT];
    private Deque<VisualFrame> visualFrames;
    private long lastSimulatedTick = Long.MIN_VALUE;
    private long lastVisualChange = Long.MIN_VALUE;
    private int portOverrides;
    private int nextInput;
    private int nextOutput;

    TransportBlockEntity(BlockPos pos, BlockState state) {
        super(BloodgrassIndustries.TRANSPORT_ENTITY.get(), pos, state);
        Arrays.fill(routeFrom, NO_ROUTE);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        TransportBlock.scheduleConnections(level, worldPosition);
    }

    void tick(ServerLevel level) {
        long now = level.getGameTime();
        if (lastSimulatedTick == now) {
            return;
        }
        lastSimulatedTick = now;
        TransportBlockEntity next = waitingForBelt(level, now);
        if (next != null) {
            // Clear downstream slots first, even if Minecraft ticks this end of the line first.
            Deque<TransportBlockEntity> waiting = new ArrayDeque<>();
            while (next != null) {
                next.lastSimulatedTick = now;
                waiting.push(next);
                next = next.waitingForBelt(level, now);
            }
            while (!waiting.isEmpty()) {
                waiting.pop().moveBelt(level);
            }
        }
        if (getBlockState().getBlock() instanceof SplitterBlock) {
            routeSplitter(level);
        } else {
            moveBelt(level);
        }
    }

    private TransportBlockEntity waitingForBelt(ServerLevel level, long now) {
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof BeltBlock) || !ready(SLOT_COUNT - 1, now)
                || (state.getValue(BeltBlock.PROFILE).ramp() && !BeltBlock.hasHeadroom(level, worldPosition))) {
            return null;
        }
        TransportTransfer.Input inlet = TransportTransfer.inputAt(level, BeltBlock.outputPoint(worldPosition, state),
                state.getValue(BeltBlock.FACING).getOpposite());
        if (inlet != null && level.shouldTickBlocksAt(inlet.pos())
                && level.getBlockEntity(inlet.pos()) instanceof TransportBlockEntity downstream
                && downstream.lastSimulatedTick != now && downstream.getBlockState().getBlock() instanceof BeltBlock
                && downstream.ready(inlet.slot(), now)) {
            return downstream;
        }
        return null;
    }

    private void moveBelt(ServerLevel level) {
        BlockState state = getBlockState();
        if (state.getValue(BeltBlock.PROFILE).ramp() && !BeltBlock.hasHeadroom(level, worldPosition)) {
            return;
        }
        long now = level.getGameTime();
        int front = SLOT_COUNT - 1;
        boolean changed = false;
        if (ready(front, now) && TransportTransfer.send(level, BeltBlock.outputPoint(worldPosition, state),
                state.getValue(BeltBlock.FACING).getOpposite(), cargo.get(front))) {
            cargo.set(front, ItemStack.EMPTY);
            changed = true;
        }
        for (int slot = front; slot > 0; slot--) {
            if (cargo.get(slot).isEmpty() && ready(slot - 1, now)) {
                cargo.set(slot, cargo.get(slot - 1));
                cargo.set(slot - 1, ItemStack.EMPTY);
                readyAt[slot] = now + MOVE_TICKS;
                changed = true;
            }
        }
        if (changed) {
            sync();
        }
    }

    private void routeSplitter(ServerLevel level) {
        BlockState state = getBlockState();
        long now = level.getGameTime();
        boolean changed = false;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            int output = SplitterBlock.index(side);
            if (ready(output, now) && isOutput(state, side)
                    && TransportTransfer.send(level, worldPosition.relative(side), side.getOpposite(), cargo.get(output))) {
                cargo.set(output, ItemStack.EMPTY);
                routeFrom[output] = NO_ROUTE;
                changed = true;
            }
        }
        if (startRoute(level, state, now) || changed) {
            sync();
        }
    }

    private boolean startRoute(ServerLevel level, BlockState state, long now) {
        for (int offset = 0; offset < SLOT_COUNT; offset++) {
            int input = (nextInput + offset) % SLOT_COUNT;
            if (!ready(input, now)) {
                continue;
            }
            ItemStack stack = cargo.get(input);
            for (int candidate = 0; candidate < SLOT_COUNT; candidate++) {
                int output = (nextOutput + candidate) % SLOT_COUNT;
                Direction side = SplitterBlock.side(output);
                if (cargo.get(output).isEmpty() && isOutput(state, side)
                        && TransportTransfer.canSend(level, worldPosition.relative(side), side.getOpposite(), stack)) {
                    // A port changed while this item was travelling. Start its next route at that port's edge.
                    routeFrom[output] = routeFrom[input] == NO_ROUTE ? input : input + SLOT_COUNT;
                    readyAt[output] = now + MOVE_TICKS;
                    cargo.set(output, stack);
                    cargo.set(input, ItemStack.EMPTY);
                    routeFrom[input] = NO_ROUTE;
                    nextInput = (input + 1) % SLOT_COUNT;
                    nextOutput = (output + 1) % SLOT_COUNT;
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isOutput(BlockState state, Direction side) {
        return SplitterBlock.port(state, side) == SplitterBlock.Port.OUTPUT;
    }

    private boolean ready(int slot, long now) {
        return !cargo.get(slot).isEmpty() && readyAt[slot] <= now;
    }

    boolean offer(ItemStack stack, int slot, long now) {
        if (stack.isEmpty() || !canOffer(slot)) {
            return false;
        }
        cargo.set(slot, stack.copyWithCount(1));
        readyAt[slot] = now + MOVE_TICKS;
        sync();
        return true;
    }

    boolean canOffer(int slot) {
        if (slot < 0 || slot >= SLOT_COUNT || !cargo.get(slot).isEmpty() || !(level instanceof ServerLevel) || isRemoved()) {
            return false;
        }
        BlockState state = getBlockState();
        if (state.getBlock() instanceof SplitterBlock) {
            return SplitterBlock.port(state, SplitterBlock.side(slot)) == SplitterBlock.Port.INPUT;
        }
        return slot == 0 && (!state.getValue(BeltBlock.PROFILE).ramp() || BeltBlock.hasHeadroom(level, worldPosition));
    }

    boolean hasCargo() {
        return cargo.stream().anyMatch(stack -> !stack.isEmpty());
    }

    ItemStack cargo(int slot) {
        return cargo.get(slot);
    }

    SplitterBlock.Port portOverride(Direction side) {
        return switch ((portOverrides >> (SplitterBlock.index(side) * 2)) & 3) {
            case 1 -> SplitterBlock.Port.INPUT;
            case 2 -> SplitterBlock.Port.OUTPUT;
            case 3 -> SplitterBlock.Port.UNUSED;
            default -> null;
        };
    }

    SplitterBlock.Port cyclePortOverride(Direction side) {
        int shift = SplitterBlock.index(side) * 2;
        int next = ((portOverrides >> shift) + 1) & 3;
        portOverrides = (portOverrides & ~(3 << shift)) | (next << shift);
        sync();
        return portOverride(side);
    }

    double visualTime(float partialTick) {
        return level.getGameTime() + (double) partialTick - VISUAL_DELAY;
    }

    VisualFrame visualFrame(double time) {
        if (visualFrames == null || visualFrames.getLast().state() != getBlockState()) {
            // An older frame follows the old belt shape. Start a new history for the current state.
            visualFrames = null;
            saveVisualFrame(Long.MIN_VALUE);
        }
        for (var frames = visualFrames.descendingIterator(); frames.hasNext();) {
            VisualFrame frame = frames.next();
            if (frame.tick <= time) {
                return frame;
            }
        }
        return visualFrames.getFirst();
    }

    Vec3 renderPosition(VisualFrame frame, int slot, double time) {
        BlockState state = frame.state();
        VisualSlot visual = frame.slots().get(slot);
        double moved = Mth.clamp(1 - (visual.readyAt() - time) / MOVE_TICKS, 0, 1);
        if (state.getBlock() instanceof SplitterBlock) {
            Direction side = SplitterBlock.side(slot);
            int from = visual.routeFrom();
            if (from == NO_ROUTE) {
                return splitterPosition(side, Mth.lerp(moved, 0.5, 0.25));
            }
            Vec3 start = splitterPosition(SplitterBlock.side(from % SLOT_COUNT), from < SLOT_COUNT ? 0.25 : 0.5);
            Vec3 finish = splitterPosition(side, 0.5);
            Vec3 center = new Vec3(0.5, BeltBlock.SURFACE, 0.5);
            return start.lerp(center, moved).lerp(center.lerp(finish, moved), moved);
        }
        return BeltBlock.cargoPosition(state, (slot + moved) / SLOT_COUNT);
    }

    private static Vec3 splitterPosition(Direction side, double offset) {
        return new Vec3(0.5 + side.getStepX() * offset, BeltBlock.SURFACE, 0.5 + side.getStepZ() * offset);
    }

    private void saveVisualFrame(long tick) {
        BlockState state = getBlockState();
        if (visualFrames == null || visualFrames.getLast().state() != state) {
            visualFrames = new ArrayDeque<>(VISUAL_HISTORY);
        } else if (visualFrames.getLast().tick == tick) {
            visualFrames.removeLast();
        }
        VisualSlot[] slots = new VisualSlot[SLOT_COUNT];
        for (int slot = 0; slot < SLOT_COUNT; slot++) {
            slots[slot] = new VisualSlot(cargo.get(slot).copy(), readyAt[slot], routeFrom[slot]);
        }
        visualFrames.addLast(new VisualFrame(tick, state, List.of(slots)));
        if (visualFrames.size() > VISUAL_HISTORY) {
            visualFrames.removeFirst();
        }
    }

    void dropCargo() {
        Containers.dropContents(level, worldPosition, cargo);
    }

    private void sync() {
        lastVisualChange = level.getGameTime();
        setChanged();
        BlockState state = getBlockState();
        level.sendBlockUpdated(worldPosition, state, state, Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        BlockState state = getBlockState();
        if (state.getBlock() instanceof SplitterBlock) {
            saveSplitter(tag, registries, state);
        } else {
            ContainerHelper.saveAllItems(tag, cargo, registries);
            tag.putLongArray(READY_AT_KEY, readyAt);
        }
    }

    private void saveSplitter(CompoundTag tag, HolderLookup.Provider registries, BlockState state) {
        NonNullList<ItemStack> localCargo = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        long[] localReadyAt = new long[SLOT_COUNT];
        int[] localRoutes = new int[SLOT_COUNT];
        int localOverrides = 0;
        for (int local = 0; local < SLOT_COUNT; local++) {
            int world = SplitterBlock.worldPort(state, local);
            localCargo.set(local, cargo.get(world));
            localReadyAt[local] = readyAt[world];
            int from = routeFrom[world];
            localRoutes[local] = from == NO_ROUTE ? NO_ROUTE
                    : SplitterBlock.localPort(state, from % SLOT_COUNT) + routeStage(from);
            localOverrides |= ((portOverrides >> (world * 2)) & 3) << (local * 2);
        }
        ContainerHelper.saveAllItems(tag, localCargo, registries);
        tag.putLongArray(READY_AT_KEY, localReadyAt);
        tag.putIntArray("RouteFrom", localRoutes);
        tag.putInt("PortOverrides", localOverrides);
        tag.putInt("NextInput", SplitterBlock.localPort(state, nextInput));
        tag.putInt("NextOutput", SplitterBlock.localPort(state, nextOutput));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        cargo.clear();
        BlockState state = getBlockState();
        long[] savedReadyAt = tag.getLongArray(READY_AT_KEY);
        if (state.getBlock() instanceof SplitterBlock) {
            loadSplitter(tag, registries, state, savedReadyAt);
        } else {
            ContainerHelper.loadAllItems(tag, cargo, registries);
            Arrays.fill(readyAt, 0);
            System.arraycopy(savedReadyAt, 0, readyAt, 0, Math.min(savedReadyAt.length, SLOT_COUNT));
        }
        if (tag.contains(VISUAL_TICK, Tag.TAG_LONG)) {
            saveVisualFrame(tag.getLong(VISUAL_TICK));
        }
    }

    private void loadSplitter(CompoundTag tag, HolderLookup.Provider registries, BlockState state, long[] savedReadyAt) {
        NonNullList<ItemStack> localCargo = NonNullList.withSize(SLOT_COUNT, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, localCargo, registries);
        int[] localRoutes = tag.getIntArray("RouteFrom");
        int localOverrides = tag.getInt("PortOverrides");
        portOverrides = 0;
        for (int local = 0; local < SLOT_COUNT; local++) {
            int world = SplitterBlock.worldPort(state, local);
            cargo.set(world, localCargo.get(local));
            readyAt[world] = local < savedReadyAt.length ? savedReadyAt[local] : 0;
            int from = local < localRoutes.length ? localRoutes[local] : NO_ROUTE;
            routeFrom[world] = from >= 0 && from < 2 * SLOT_COUNT
                    ? SplitterBlock.worldPort(state, from % SLOT_COUNT) + routeStage(from) : NO_ROUTE;
            portOverrides |= ((localOverrides >> (local * 2)) & 3) << (world * 2);
        }
        nextInput = SplitterBlock.worldPort(state, Math.floorMod(tag.getInt("NextInput"), SLOT_COUNT));
        nextOutput = SplitterBlock.worldPort(state, Math.floorMod(tag.getInt("NextOutput"), SLOT_COUNT));
    }

    private static int routeStage(int route) {
        return route >= SLOT_COUNT ? SLOT_COUNT : 0;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = saveWithoutMetadata(registries);
        // Chunk updates can be sent on a later tick than the move they describe.
        if (level != null) {
            tag.putLong(VISUAL_TICK, lastVisualChange == Long.MIN_VALUE ? level.getGameTime() : lastVisualChange);
        }
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private final IItemHandler hatch = new IItemHandler() {
        @Override
        public int getSlots() {
            return 1;
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            return slot == 0 ? cargo.get(0).copy() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty() || !isItemValid(slot, stack) || !canOffer(0)) {
                return stack;
            }
            if (!simulate && !offer(stack, 0, level.getGameTime())) {
                return stack;
            }
            return stack.copyWithCount(stack.getCount() - 1);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            return ItemStack.EMPTY;
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            return slot == 0;
        }
    };

    IItemHandler hopperInput(Direction side) {
        return side == Direction.UP && getBlockState().getBlock() instanceof BeltBlock ? hatch : null;
    }
}
