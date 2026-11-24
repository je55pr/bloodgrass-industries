package io.github.je55pr.bloodgrassindustries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.phys.Vec3;

final class QuarryMaterials {
    private static final List<ItemStack> TOOLS = List.of(Items.NETHERITE_PICKAXE.getDefaultInstance(),
            Items.NETHERITE_SHOVEL.getDefaultInstance(), Items.NETHERITE_AXE.getDefaultInstance(), Items.NETHERITE_HOE.getDefaultInstance());

    record Finish(Block road, Block wall) {
        static final Finish NATURAL = new Finish(Blocks.AIR, Blocks.AIR);

        boolean isNatural() {
            return road == Blocks.AIR && wall == Blocks.AIR;
        }

        Block material(QuarryPlan.Surface surface) {
            return surface == QuarryPlan.Surface.WALL ? wall : road;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("Road", blockId(road));
            tag.putString("Wall", blockId(wall));
            return tag;
        }

        static Finish load(CompoundTag tag) {
            return new Finish(readBlock(tag.getString("Road")), readBlock(tag.getString("Wall")));
        }
    }

    static final class Counts {
        private long road;
        private long wall;
        private long roadSupports;
        private long wallSupports;
        private final Map<Block, Long> existingRoad = new HashMap<>();
        private final Map<Block, Long> existingWall = new HashMap<>();

        void add(boolean edge, boolean support, Block existing) {
            if (edge) {
                wall++;
                wallSupports += support ? 1 : 0;
                existingWall.merge(existing, 1L, Long::sum);
            } else {
                road++;
                roadSupports += support ? 1 : 0;
                existingRoad.merge(existing, 1L, Long::sum);
            }
        }

        long cost(boolean edge, Block material) {
            return edge ? wall - existingWall.getOrDefault(material, 0L) : road - existingRoad.getOrDefault(material, 0L);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Road", road);
            tag.putLong("Wall", wall);
            tag.putLong("RoadSupports", roadSupports);
            tag.putLong("WallSupports", wallSupports);
            tag.put("ExistingRoad", saveBlocks(existingRoad));
            tag.put("ExistingWall", saveBlocks(existingWall));
            return tag;
        }

        static Counts load(CompoundTag tag) {
            Counts counts = new Counts();
            counts.road = tag.getLong("Road");
            counts.wall = tag.getLong("Wall");
            counts.roadSupports = tag.getLong("RoadSupports");
            counts.wallSupports = tag.getLong("WallSupports");
            loadBlocks(tag.getCompound("ExistingRoad"), counts.existingRoad);
            loadBlocks(tag.getCompound("ExistingWall"), counts.existingWall);
            return counts;
        }

        private static CompoundTag saveBlocks(Map<Block, Long> blocks) {
            CompoundTag tag = new CompoundTag();
            blocks.forEach((block, count) -> tag.putLong(blockId(block), count));
            return tag;
        }

        private static void loadBlocks(CompoundTag tag, Map<Block, Long> blocks) {
            for (String id : tag.getAllKeys()) {
                Block block = readBlock(id);
                if (block != Blocks.AIR && tag.getLong(id) > 0) {
                    blocks.put(block, tag.getLong(id));
                }
            }
        }
    }

    record Allocation(Block support, Map<Item, Long> output, long supportCount) {}

    record Report(QuarryMaterials materials, Counts surfaces) {
        Allocation allocate(Finish finish) {
            return materials.allocate(finish, surfaces);
        }

        CompoundTag save() {
            CompoundTag tag = materials.save();
            tag.put("Surfaces", surfaces.save());
            return tag;
        }

        static Report load(CompoundTag tag) {
            return new Report(QuarryMaterials.load(tag), Counts.load(tag.getCompound("Surfaces")));
        }
    }

    private record Yield(Block source, Map<Item, Long> items) {}

    private final Map<Yield, Long> batches = new LinkedHashMap<>();
    private final Map<Item, Long> output = new HashMap<>();

    static Map<Item, Long> drops(ServerLevel level, BlockPos pos, BlockState state) {
        ItemStack tool = ItemStack.EMPTY;
        for (ItemStack candidate : TOOLS) {
            if (candidate.isCorrectToolForDrops(state)) {
                tool = candidate;
                break;
            }
        }
        if (state.requiresCorrectToolForDrops() && tool.isEmpty()) {
            return Map.of();
        }
        var params = new LootParams.Builder(level)
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(pos))
                .withParameter(LootContextParams.TOOL, tool)
                .create(LootContextParamSets.BLOCK);
        var table = level.getServer().reloadableRegistries().getLootTable(state.getBlock().getLootTable());
        Map<Item, Long> items = new HashMap<>();
        long seed = pos.asLong() ^ 0x63b91cbe5dL;
        for (ItemStack stack : table.getRandomItems(params, seed == 0 ? 1 : seed)) {
            add(items, stack.getItem(), stack.getCount());
        }
        return items;
    }

    void record(BlockState state, Map<Item, Long> drops) {
        Block block = state.getBlock();
        if (usable(block)) {
            batches.merge(new Yield(block, Map.copyOf(drops)), 1L, Long::sum);
        }
        drops.forEach((item, count) -> add(output, item, count));
    }

    Map<Item, Long> output() {
        return Map.copyOf(output);
    }

    Map<Block, Long> candidates() {
        Map<Block, Long> counts = new HashMap<>();
        output.forEach((item, count) -> {
            if (item instanceof BlockItem block && usable(block.getBlock())) {
                counts.merge(block.getBlock(), count, Long::sum);
            }
        });
        batches.forEach((yield, count) -> {
            if (!yield.items().containsKey(yield.source().asItem())) {
                counts.merge(yield.source(), count, Long::sum);
            }
        });
        return counts;
    }

    Allocation allocate(Finish finish, Counts surfaces) {
        if ((finish.road() != Blocks.AIR && !usable(finish.road())) || (finish.wall() != Blocks.AIR && !usable(finish.wall()))) {
            return null;
        }
        Map<Item, Long> demand = new LinkedHashMap<>();
        if (finish.road() != Blocks.AIR) {
            add(demand, finish.road().asItem(), surfaces.cost(false, finish.road()));
        }
        if (finish.wall() != Blocks.AIR) {
            add(demand, finish.wall().asItem(), surfaces.cost(true, finish.wall()));
        }
        long supports = (finish.road() instanceof FallingBlock ? surfaces.roadSupports : 0)
                + (finish.wall() instanceof FallingBlock ? surfaces.wallSupports : 0);
        if (supports == 0) {
            return allocate(demand, Blocks.AIR, 0);
        }
        Map<Block, Long> candidates = candidates();
        List<Block> supportsInOrder = candidates.keySet().stream().filter(block -> !(block instanceof FallingBlock))
                .sorted(Comparator.comparing((Block block) -> block != Blocks.COBBLESTONE)
                        .thenComparing(Comparator.comparingLong((Block block) -> candidates.get(block)).reversed())
                        .thenComparing(QuarryMaterials::blockId)).toList();
        for (Block support : supportsInOrder) {
            if (candidates.get(support) < supports) {
                continue;
            }
            Allocation allocation = allocate(demand, support, supports);
            if (allocation != null) {
                return allocation;
            }
        }
        return null;
    }

    Allocation allocate(Map<Item, Long> finishes, Block support, long supports) {
        Map<Item, Long> demand = new LinkedHashMap<>(finishes);
        if (supports != 0) {
            add(demand, support.asItem(), supports);
        }
        Map<Item, Long> stock = new HashMap<>(output);
        Map<Yield, Long> unreserved = new LinkedHashMap<>(batches);
        List<Item> required = new ArrayList<>(demand.keySet());
        // A source block can contribute its regular drops or its intact block.
        for (;;) {
            Item shortfall = required.stream().filter(item -> stock.getOrDefault(item, 0L) < demand.get(item)).findFirst().orElse(null);
            if (shortfall == null) {
                demand.forEach((item, count) -> add(stock, item, -count));
                return new Allocation(support, Map.copyOf(stock), supports);
            }
            long missing = demand.get(shortfall) - stock.getOrDefault(shortfall, 0L);
            var group = unreserved.entrySet().stream()
                    .filter(entry -> entry.getValue() > 0 && entry.getKey().source().asItem() == shortfall
                            && !entry.getKey().items().containsKey(shortfall))
                    .min(Comparator.comparingLong(entry -> entry.getKey().items().entrySet().stream()
                            .filter(drop -> demand.containsKey(drop.getKey())).mapToLong(Map.Entry::getValue).sum())).orElse(null);
            if (group == null) {
                return null;
            }
            long take = Math.min(missing, group.getValue());
            group.setValue(group.getValue() - take);
            group.getKey().items().forEach((item, count) -> add(stock, item, -take * count));
            add(stock, shortfall, take);
        }
    }

    private static void add(Map<Item, Long> amounts, Item item, long amount) {
        if (amount != 0 && amounts.merge(item, amount, Long::sum) == 0) {
            amounts.remove(item);
        }
    }

    private static boolean usable(Block block) {
        BlockState state = block.defaultBlockState();
        return block.asItem() instanceof BlockItem item && item.getBlock() == block
                && QuarryPlan.canExcavate(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, state)
                && state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    static String blockId(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static Block readBlock(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? Blocks.AIR : BuiltInRegistries.BLOCK.get(key);
    }

    static CompoundTag saveOutput(Map<Item, Long> counts) {
        CompoundTag tag = new CompoundTag();
        counts.forEach((item, count) -> tag.putLong(BuiltInRegistries.ITEM.getKey(item).toString(), count));
        return tag;
    }

    static Map<Item, Long> loadOutput(CompoundTag tag) {
        Map<Item, Long> counts = new HashMap<>();
        for (String id : tag.getAllKeys()) {
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (key != null && BuiltInRegistries.ITEM.containsKey(key) && tag.getLong(id) > 0) {
                counts.put(BuiltInRegistries.ITEM.get(key), tag.getLong(id));
            }
        }
        return counts;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.put("Output", saveOutput(output));
        ListTag yields = new ListTag();
        batches.forEach((yield, count) -> {
            CompoundTag row = new CompoundTag();
            row.putString("Source", blockId(yield.source()));
            row.putLong("Count", count);
            row.put("Items", saveOutput(yield.items()));
            yields.add(row);
        });
        tag.put("Yields", yields);
        return tag;
    }

    static QuarryMaterials load(CompoundTag tag) {
        QuarryMaterials materials = new QuarryMaterials();
        materials.output.putAll(loadOutput(tag.getCompound("Output")));
        for (Tag entry : tag.getList("Yields", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) entry;
            Block source = readBlock(row.getString("Source"));
            if (source != Blocks.AIR && row.getLong("Count") > 0) {
                materials.batches.put(new Yield(source, Map.copyOf(loadOutput(row.getCompound("Items")))), row.getLong("Count"));
            }
        }
        return materials;
    }
}
