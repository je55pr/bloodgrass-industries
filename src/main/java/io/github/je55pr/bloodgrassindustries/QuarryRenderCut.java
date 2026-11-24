package io.github.je55pr.bloodgrassindustries;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
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

    static void show(Minecraft game, ClientLevel level, BlockPos origin, QuarryPlan plan, QuarryMaterials.Finish finish) {
        Snapshot previous = active;
        Snapshot next = plan == null ? null : new Snapshot(level, origin, plan, finish);
        active = next;
        if (game.level != level) {
            return;
        }
        if (previous != null && previous.level == level) {
            previous.rebuild(game);
        }
        if (next != null) {
            next.rebuild(game);
        }
        game.levelRenderer.needsUpdate();
    }

    public static final class Snapshot {
        private final ClientLevel level;
        private final BlockPos origin;
        private final QuarryPlan plan;
        private final QuarryMaterials.Finish finish;
        private final boolean skylight;

        private Snapshot(ClientLevel level, BlockPos origin, QuarryPlan plan, QuarryMaterials.Finish finish) {
            this.level = level;
            this.origin = origin;
            this.plan = plan;
            this.finish = finish;
            skylight = level.dimensionType().hasSkyLight();
        }

        public BlockState visible(BlockGetter world, BlockPos pos, BlockState state) {
            return plan.previewState(world, pos, state, pos.getX() - origin.getX(), pos.getZ() - origin.getZ(), finish);
        }

        public boolean hasPreviewSkylight(BlockPos pos) {
            return skylight && pos.getY() >= floorAt(pos);
        }

        private int floorAt(BlockPos pos) {
            return plan.floorAt(pos.getX() - origin.getX(), pos.getZ() - origin.getZ());
        }

        private void rebuild(Minecraft game) {
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
                    int lowest = Integer.MAX_VALUE;
                    int highest = Integer.MIN_VALUE;
                    for (int x = Math.max(minX, cx << 4); x <= Math.min(maxX, (cx << 4) + 15); x++) {
                        for (int z = Math.max(minZ, cz << 4); z <= Math.min(maxZ, (cz << 4) + 15); z++) {
                            int dx = x - origin.getX();
                            int dz = z - origin.getZ();
                            int floor = plan.floorAt(dx, dz);
                            if (floor != QuarryPlan.OUTSIDE) {
                                int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
                                int bottom = finish.wall() == Blocks.AIR ? floor : Math.min(floor, plan.wallBottom(dx, dz, floor) + 1);
                                if (top > floor || !finish.isNatural() && top >= bottom) {
                                    lowest = Math.min(lowest, bottom);
                                    highest = Math.max(highest, top);
                                }
                            }
                        }
                    }
                    if (lowest <= highest) {
                        for (int y = SectionPos.blockToSectionCoord(lowest); y <= SectionPos.blockToSectionCoord(highest + 1); y++) {
                            game.levelRenderer.setSectionDirtyWithNeighbors(cx, y, cz);
                        }
                    }
                }
            }
        }
    }
}
