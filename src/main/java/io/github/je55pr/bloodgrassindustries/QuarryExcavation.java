package io.github.je55pr.bloodgrassindustries;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

final class QuarryExcavation {
    private static final int BLOCKS_PER_TICK = 1_024;
    private static final int SURVEY_BLOCKS_PER_TICK = 4_096;
    private static final long BATCH_TIME_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final int UNSTARTED = Integer.MAX_VALUE;
    private static final String OWNER_KEY = "Owner";
    private static final String NEXT_Y_KEY = "NextY";
    private static final TicketType<BlockPos> TICKET = TicketType.create("bloodgrass_quarry", Comparator.<BlockPos>naturalOrder(), 80);

    private final UUID owner;
    private enum Phase { SURVEY, CHECK, CUT, MEASURE, FINISH }

    private QuarryPlan plan;
    private Phase phase;
    private QuarryMaterials materials = new QuarryMaterials();
    private QuarryMaterials.Counts surfaces = new QuarryMaterials.Counts();
    private QuarryMaterials.Allocation allocation;
    private long spentRoad;
    private long spentWall;
    private long spentSupports;
    private boolean finishUnavailable;
    private int chunkIndex;
    private int column;
    private int nextY = UNSTARTED;
    private long removed;
    private ChunkPos loaded;

    private QuarryExcavation(UUID owner, QuarryPlan plan, Phase phase) {
        this.owner = owner;
        this.plan = plan;
        this.phase = phase;
    }

    static QuarryExcavation survey(UUID owner, QuarryPlan plan) {
        return new QuarryExcavation(owner, plan, Phase.SURVEY);
    }

    static QuarryExcavation start(UUID owner, QuarryPlan plan, QuarryMaterials.Finish finish) {
        return new QuarryExcavation(owner, plan, finish.isNatural() ? Phase.CUT : Phase.CHECK);
    }

    boolean checkingFinish() {
        return phase == Phase.CHECK;
    }

    static void validateSite(ServerLevel level, ServerPlayer player, BlockPos origin, QuarryPlan plan) {
        QuarryPlan.Bounds bounds = plan.bounds();
        BlockPos min = origin.offset(bounds.minX(), 0, bounds.minZ());
        BlockPos max = origin.offset(bounds.maxX(), 0, bounds.maxZ());
        if (!level.isInWorldBounds(min) || !level.isInWorldBounds(max)
                || !level.getWorldBorder().isWithinBounds(min) || !level.getWorldBorder().isWithinBounds(max)) {
            throw new IllegalArgumentException("Keep the entire quarry inside the world border.");
        }
        if (!player.hasPermissions(2)) {
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    if (plan.floorAt(x, z) != QuarryPlan.OUTSIDE
                            && !level.mayInteract(player, probe.set(origin.getX() + x, origin.getY(), origin.getZ() + z))) {
                        throw new IllegalArgumentException("Part of the quarry is in a protected area.");
                    }
                }
            }
        }
    }

    ServerPlayer player(ServerLevel level) {
        return level.getServer().getPlayerList().getPlayer(owner);
    }

    long removed() {
        return removed;
    }

    QuarryMaterials.Report report() {
        return new QuarryMaterials.Report(materials, surfaces);
    }

    boolean finishUnavailable() {
        return finishUnavailable;
    }

    Map<Item, Long> output(QuarryMaterials.Finish finish) {
        if (allocation == null) {
            return materials.output();
        }
        Map<Item, Long> used = new HashMap<>();
        if (spentRoad > 0) {
            used.merge(finish.road().asItem(), spentRoad, Long::sum);
        }
        if (spentWall > 0) {
            used.merge(finish.wall().asItem(), spentWall, Long::sum);
        }
        var remainder = materials.allocate(used, allocation.support(), spentSupports);
        return remainder == null ? allocation.output() : remainder.output();
    }

    boolean tick(ServerLevel level, BlockPos origin, QuarryPlan.Settings settings, ServerPlayer player, QuarryMaterials.Finish finish) {
        if (plan == null) {
            plan = QuarryPlan.create(settings, level.getMinBuildHeight(), level.getMaxBuildHeight());
        }
        QuarryPlan.Bounds bounds = plan.bounds();
        int firstX = SectionPos.blockToSectionCoord(origin.getX() + bounds.minX());
        int firstZ = SectionPos.blockToSectionCoord(origin.getZ() + bounds.minZ());
        int width = SectionPos.blockToSectionCoord(origin.getX() + bounds.maxX()) - firstX + 1;
        int depth = SectionPos.blockToSectionCoord(origin.getZ() + bounds.maxZ()) - firstZ + 1;
        int totalChunks = width * depth;
        if (!prepareFinish(finish)) {
            return true;
        }
        int budget = scanningOnly() ? SURVEY_BLOCKS_PER_TICK : BLOCKS_PER_TICK;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ChunkPos renewed = null;
        LevelChunk chunk = null;
        long deadline = System.nanoTime() + BATCH_TIME_NANOS;
        while (chunkIndex < totalChunks && budget > 0 && System.nanoTime() - deadline < 0) {
            ChunkPos target = new ChunkPos(firstX + chunkIndex % width, firstZ + chunkIndex / width);
            int x = target.getMinBlockX() + (column & 15);
            int z = target.getMinBlockZ() + (column >> 4);
            int floor = plan.floorAt(x - origin.getX(), z - origin.getZ());
            if (floor == QuarryPlan.OUTSIDE) {
                advanceColumn(level, origin);
                budget--;
                continue;
            }
            if (!target.equals(renewed)) {
                renewed = target;
                chunk = loadChunk(level, origin, target);
                if (chunk == null) {
                    return false;
                }
            }
            pos.set(x, floor, z);
            if (!level.mayInteract(player, pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                throw new IllegalArgumentException("Excavation stopped at a protected column.");
            }
            budget = processColumn(level, origin, chunk, pos, finish, budget, deadline);
        }
        return chunkIndex >= totalChunks && completePhase(finish);
    }

    private boolean scanningOnly() {
        return phase == Phase.SURVEY || checkingFinish();
    }

    private boolean prepareFinish(QuarryMaterials.Finish finish) {
        if (phase != Phase.FINISH || allocation != null) {
            return true;
        }
        allocation = report().allocate(finish);
        if (allocation == null) {
            finishUnavailable = true;
            return false;
        }
        return true;
    }

    private LevelChunk loadChunk(ServerLevel level, BlockPos origin, ChunkPos target) {
        if (!target.equals(loaded)) {
            release(level, origin);
            loaded = target;
        }
        level.getChunkSource().addRegionTicket(TICKET, loaded, 1, origin);
        return level.getChunkSource().getChunkNow(loaded.x, loaded.z);
    }

    private int processColumn(ServerLevel level, BlockPos origin, LevelChunk chunk, BlockPos.MutableBlockPos pos,
                              QuarryMaterials.Finish finish, int budget, long deadline) {
        int x = pos.getX();
        int z = pos.getZ();
        int floor = pos.getY();
        if (phase == Phase.MEASURE || phase == Phase.FINISH) {
            budget -= surfaceColumn(level, origin, pos, x, z, floor, finish);
            advanceColumn(level, origin);
            return budget;
        }
        if (nextY == UNSTARTED) {
            nextY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
            budget--;
        }
        boolean surveying = scanningOnly();
        while (nextY > floor && budget > 0 && (budget % 32 != 0 || System.nanoTime() - deadline < 0)) {
            pos.set(x, nextY--, z);
            budget--;
            BlockState state = chunk.getBlockState(pos);
            if (QuarryPlan.canExcavate(level, pos, state)) {
                Map<Item, Long> drops = QuarryMaterials.drops(level, pos, state);
                if (surveying || level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS)) {
                    materials.recordDrops(state, drops);
                    removed++;
                }
            }
        }
        if (nextY <= floor) {
            if (surveying) {
                budget -= surfaceColumn(level, origin, pos, x, z, floor, finish);
            }
            advanceColumn(level, origin);
        }
        return budget;
    }

    private boolean completePhase(QuarryMaterials.Finish finish) {
        switch (phase) {
            case SURVEY, FINISH -> {
                return true;
            }
            case CHECK -> {
                if (report().allocate(finish) == null) {
                    finishUnavailable = true;
                    return true;
                }
                materials = new QuarryMaterials();
                surfaces = new QuarryMaterials.Counts();
                removed = 0;
                phase = Phase.CUT;
            }
            case CUT -> {
                if (finish.isNatural()) {
                    return true;
                }
                phase = Phase.MEASURE;
            }
            case MEASURE -> {
                allocation = report().allocate(finish);
                if (allocation == null) {
                    finishUnavailable = true;
                    return true;
                }
                phase = Phase.FINISH;
            }
        }
        chunkIndex = 0;
        return false;
    }

    private int surfaceColumn(ServerLevel level, BlockPos origin, BlockPos.MutableBlockPos pos, int x, int z, int floor,
                              QuarryMaterials.Finish finish) {
        int wallBottom = plan.wallBottom(x - origin.getX(), z - origin.getZ(), floor);
        int start = Math.min(floor, wallBottom + 1);
        for (int y = start; y <= floor; y++) {
            pos.set(x, y, z);
            BlockState existing = level.getBlockState(pos);
            if (!QuarryPlan.canFinish(level, pos, existing)) {
                continue;
            }
            QuarryPlan.Surface surface = QuarryPlan.surfaceAt(y, floor, wallBottom);
            BlockPos below = pos.below();
            QuarryPlan.Support support = QuarryPlan.supportAt(level, below, level.getBlockState(below));
            if (phase != Phase.FINISH) {
                surfaces.add(surface == QuarryPlan.Surface.WALL, support, existing.getBlock());
            } else {
                applyFinish(level, pos, below, surface, support, finish, existing);
            }
        }
        return floor - start + 1;
    }

    private void applyFinish(ServerLevel level, BlockPos pos, BlockPos below, QuarryPlan.Surface surface, QuarryPlan.Support support,
                             QuarryMaterials.Finish finish, BlockState existing) {
        boolean edge = surface == QuarryPlan.Surface.WALL;
        Block material = finish.material(surface);
        boolean swap = material != existing.getBlock();
        if (material == Blocks.AIR) {
            return;
        }
        if ((swap && (edge ? spentWall : spentRoad) >= surfaces.cost(edge, material))
                || (material instanceof FallingBlock && !placeSupport(level, below, support))) {
            finishUnavailable = true;
            return;
        }
        if (swap) {
            if (!level.setBlock(pos, material.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS)) {
                finishUnavailable = true;
            } else if (edge) {
                spentWall++;
            } else {
                spentRoad++;
            }
        }
    }

    private boolean placeSupport(ServerLevel level, BlockPos below, QuarryPlan.Support support) {
        if (support == QuarryPlan.Support.NONE) {
            return true;
        }
        if (support == QuarryPlan.Support.IMPOSSIBLE || spentSupports >= allocation.supportCount()
                || !level.setBlock(below, allocation.support().defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS)) {
            return false;
        }
        spentSupports++;
        return true;
    }

    private void advanceColumn(ServerLevel level, BlockPos origin) {
        nextY = UNSTARTED;
        if (++column == 256) {
            chunkIndex++;
            column = 0;
            release(level, origin);
        }
    }

    void release(ServerLevel level, BlockPos origin) {
        if (loaded != null) {
            level.getChunkSource().removeRegionTicket(TICKET, loaded, 1, origin);
            loaded = null;
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(OWNER_KEY, owner);
        tag.putInt("Chunk", chunkIndex);
        tag.putInt("Column", column);
        tag.putInt(NEXT_Y_KEY, nextY);
        tag.putLong("Removed", removed);
        tag.putString("Phase", phase.name());
        tag.put("Report", report().save());
        tag.putBoolean("FinishUnavailable", finishUnavailable);
        tag.putLong("SpentRoad", spentRoad);
        tag.putLong("SpentWall", spentWall);
        tag.putLong("SpentSupports", spentSupports);
        return tag;
    }

    static QuarryExcavation load(CompoundTag tag) {
        if (!tag.hasUUID(OWNER_KEY)) {
            return null;
        }
        QuarryExcavation job = new QuarryExcavation(tag.getUUID(OWNER_KEY), null, Phase.CUT);
        job.chunkIndex = Math.max(0, tag.getInt("Chunk"));
        job.column = Math.clamp(tag.getInt("Column"), 0, 255);
        job.nextY = tag.contains(NEXT_Y_KEY) ? tag.getInt(NEXT_Y_KEY) : UNSTARTED;
        job.removed = tag.getLong("Removed");
        job.phase = switch (tag.getString("Phase")) {
            case "CHECK" -> Phase.CHECK;
            case "MEASURE" -> Phase.MEASURE;
            case "FINISH" -> Phase.FINISH;
            default -> Phase.CUT;
        };
        QuarryMaterials.Report report = QuarryMaterials.Report.load(tag.getCompound("Report"));
        job.materials = report.materials();
        job.surfaces = report.surfaces();
        job.finishUnavailable = tag.getBoolean("FinishUnavailable");
        job.spentRoad = tag.getLong("SpentRoad");
        job.spentWall = tag.getLong("SpentWall");
        job.spentSupports = tag.getLong("SpentSupports");
        return job;
    }
}
