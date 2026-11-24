package io.github.je55pr.bloodgrassindustries;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;

public final class QuarryRenderCut {
    private static volatile Snapshot active;

    private QuarryRenderCut() {}

    public static Snapshot snapshot(Level level) {
        Snapshot snapshot = active;
        return snapshot != null && snapshot.level == level ? snapshot : null;
    }

    static void show(Minecraft game, ClientLevel level, BlockPos origin, QuarryPlan plan, QuarryMaterials.Finish finish, Block support) {
        Snapshot previous = active;
        Snapshot next = plan == null ? null : new Snapshot(level, origin, plan, finish, support);
        active = next;
        if (game.level != level || (previous == null && next == null)) {
            return;
        }
        LongSet dirty = new LongOpenHashSet();
        if (previous != null && previous.level == level) {
            previous.collectDirtySections(game, dirty);
        }
        if (next != null) {
            next.collectDirtySections(game, dirty);
        }
        for (var sections = dirty.iterator(); sections.hasNext(); ) {
            long section = sections.nextLong();
            game.levelRenderer.setSectionDirtyWithNeighbors(SectionPos.x(section), SectionPos.y(section), SectionPos.z(section));
        }
        game.levelRenderer.needsUpdate();
    }

    public static final class Snapshot {
        private final ClientLevel level;
        private final BlockPos origin;
        private final QuarryPlan plan;
        private final QuarryMaterials.Finish finish;
        private final Block support;
        private final boolean skylight;

        private Snapshot(ClientLevel level, BlockPos origin, QuarryPlan plan, QuarryMaterials.Finish finish, Block support) {
            this.level = level;
            this.origin = origin;
            this.plan = plan;
            this.finish = finish;
            this.support = support;
            skylight = level.dimensionType().hasSkyLight();
        }

        public BlockState visible(BlockGetter world, BlockPos pos, BlockState state) {
            return plan.previewState(world, pos, state, pos.getX() - origin.getX(), pos.getZ() - origin.getZ(), finish, support);
        }

        public boolean hasPreviewSkylight(BlockGetter preview, BlockPos pos, Long2IntMap skyCeilings) {
            int floor = floorAt(pos);
            if (!skylight || pos.getY() < floor || !preview.getBlockState(pos).isAir()) {
                return false;
            }
            long column = ChunkPos.asLong(pos.getX(), pos.getZ());
            int ceiling = skyCeilings.getOrDefault(column, Integer.MAX_VALUE);
            if (ceiling == Integer.MAX_VALUE) {
                ceiling = QuarryPlan.skyBlocker(preview, pos, floor);
                skyCeilings.put(column, ceiling);
            }
            return pos.getY() > ceiling;
        }

        private int floorAt(BlockPos pos) {
            return plan.floorAt(pos.getX() - origin.getX(), pos.getZ() - origin.getZ());
        }

        private void collectDirtySections(Minecraft game, LongSet dirty) {
            if (game.player == null) {
                return;
            }
            QuarryPlan.Bounds bounds = plan.bounds();
            int minX = origin.getX() + bounds.minX();
            int maxX = origin.getX() + bounds.maxX();
            int minZ = origin.getZ() + bounds.minZ();
            int maxZ = origin.getZ() + bounds.maxZ();
            var center = game.player.chunkPosition();
            int range = game.options.getEffectiveRenderDistance() + 3;
            int left = Math.max(center.x - range, SectionPos.blockToSectionCoord(minX));
            int right = Math.min(center.x + range, SectionPos.blockToSectionCoord(maxX));
            int front = Math.max(center.z - range, SectionPos.blockToSectionCoord(minZ));
            int back = Math.min(center.z + range, SectionPos.blockToSectionCoord(maxZ));
            for (int cx = left; cx <= right; cx++) {
                for (int cz = front; cz <= back; cz++) {
                    var chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                    if (chunk == null) {
                        continue;
                    }
                    collectChunkSections(chunk, minX, maxX, minZ, maxZ, dirty);
                }
            }
        }

        private void collectChunkSections(LevelChunk chunk, int minX, int maxX, int minZ, int maxZ, LongSet dirty) {
            var pos = chunk.getPos();
            int lowest = Integer.MAX_VALUE;
            int highest = Integer.MIN_VALUE;
            for (int x = Math.max(minX, pos.getMinBlockX()); x <= Math.min(maxX, pos.getMaxBlockX()); x++) {
                for (int z = Math.max(minZ, pos.getMinBlockZ()); z <= Math.min(maxZ, pos.getMaxBlockZ()); z++) {
                    int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                    int bottom = affectedBottom(x - origin.getX(), z - origin.getZ(), top);
                    if (bottom != QuarryPlan.OUTSIDE) {
                        lowest = Math.min(lowest, bottom);
                        highest = Math.max(highest, top);
                    }
                }
            }
            if (lowest <= highest) {
                int topSection = SectionPos.blockToSectionCoord(highest + 1);
                for (int y = SectionPos.blockToSectionCoord(lowest); y <= topSection; y++) {
                    dirty.add(SectionPos.asLong(pos.x, y, pos.z));
                }
            }
        }

        private int affectedBottom(int x, int z, int top) {
            int floor = plan.floorAt(x, z);
            if (floor == QuarryPlan.OUTSIDE) {
                return QuarryPlan.OUTSIDE;
            }
            int bottom = finish.wall() == Blocks.AIR ? floor : Math.min(floor, plan.wallBottom(x, z, floor) + 1);
            if (top <= floor && (finish.isNatural() || top < bottom)) {
                return QuarryPlan.OUTSIDE;
            }
            return support == Blocks.AIR ? bottom : Math.max(level.getMinBuildHeight(), bottom - 1);
        }
    }
}
