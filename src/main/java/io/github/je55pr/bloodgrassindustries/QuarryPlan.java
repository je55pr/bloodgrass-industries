package io.github.je55pr.bloodgrassindustries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

final class QuarryPlan {
    static final int MAX_SPAN = 1_024;
    static final int MAX_CORNERS = 64;
    private static final int MAX_OFFSET = 60_000_000;
    static final int MAX_SETTING = 16;
    static final int OUTSIDE = Integer.MAX_VALUE;

    record Corner(int x, int z) {}

    record Settings(int baseY, int width, int drop, int steps, List<Corner> corners) {
        Settings {
            corners = List.copyOf(corners);
        }

        static Settings defaults(int baseY) {
            return new Settings(baseY, 3, 4, 3, List.of());
        }

        void save(CompoundTag tag) {
            int[] outline = new int[corners.size() * 2];
            for (int i = 0; i < corners.size(); i++) {
                outline[2 * i] = corners.get(i).x();
                outline[2 * i + 1] = corners.get(i).z();
            }
            tag.putInt("BaseY", baseY);
            tag.putInt("TerraceWidth", width);
            tag.putInt("Drop", drop);
            tag.putInt("Steps", steps);
            tag.putIntArray("Corners", outline);
        }

        static Settings load(CompoundTag tag) {
            int[] outline = tag.getIntArray("Corners");
            if (!tag.contains("BaseY") || outline.length % 2 != 0 || outline.length > MAX_CORNERS * 2) {
                return null;
            }
            List<Corner> corners = new ArrayList<>(outline.length / 2);
            for (int i = 0; i < outline.length; i += 2) {
                corners.add(new Corner(outline[i], outline[i + 1]));
            }
            return new Settings(tag.getInt("BaseY"), tag.getInt("TerraceWidth"), tag.getInt("Drop"), tag.getInt("Steps"), corners);
        }

        void validate(int minY, int maxY) {
            if (baseY < minY || baseY >= maxY || width < 1 || width > MAX_SETTING
                    || drop < 1 || drop > MAX_SETTING || steps < 1 || steps > MAX_SETTING
                    || baseY - steps * drop < minY) {
                throw new IllegalArgumentException("Set width, drop and steps from 1 to 16. The rim and lowest floor must be inside the world.");
            }
            if (corners.size() > MAX_CORNERS || corners.stream().anyMatch(corner -> corner.x() < -MAX_OFFSET
                    || corner.x() > MAX_OFFSET || corner.z() < -MAX_OFFSET || corner.z() > MAX_OFFSET)) {
                throw new IllegalArgumentException("Mark up to 64 corners within the world.");
            }
        }
    }

    record Bounds(int minX, int minZ, int maxX, int maxZ) {
        static Bounds of(List<Corner> corners) {
            int minX = corners.getFirst().x();
            int maxX = minX;
            int minZ = corners.getFirst().z();
            int maxZ = minZ;
            for (Corner corner : corners) {
                minX = Math.min(minX, corner.x());
                maxX = Math.max(maxX, corner.x());
                minZ = Math.min(minZ, corner.z());
                maxZ = Math.max(maxZ, corner.z());
            }
            return new Bounds(minX, minZ, maxX, maxZ);
        }

        int width() { return maxX - minX + 1; }

        int length() { return maxZ - minZ + 1; }
    }

    private final Bounds bounds;
    private final int[][] floors;
    private int deepest;

    private QuarryPlan(Bounds bounds, int baseY) {
        this.bounds = bounds;
        floors = new int[bounds.length()][bounds.width()];
        deepest = baseY;
    }

    static QuarryPlan create(Settings settings, int minY, int maxY) {
        settings.validate(minY, maxY);
        List<Corner> corners = settings.corners();
        validateOutline(corners);
        Bounds bounds = Bounds.of(corners);
        if (bounds.width() > MAX_SPAN || bounds.length() > MAX_SPAN) {
            throw new IllegalArgumentException("The quarry can span up to 1,024 blocks along each axis.");
        }

        int width = bounds.width();
        int length = bounds.length();
        QuarryPlan plan = new QuarryPlan(bounds, settings.baseY());
        int[][] distances = new int[length + 2][width + 2];
        double[] crossings = new double[corners.size()];
        for (int z = 1; z <= length; z++) {
            markRow(corners, bounds.minX(), bounds.minZ() + z - 1, distances[z], crossings);
            for (int x = 1; x <= width; x++) {
                if (distances[z][x] != 0) {
                    distances[z][x] = 1 + Math.min(distances[z - 1][x], distances[z][x - 1]);
                }
            }
        }
        for (int z = length; z >= 1; z--) {
            for (int x = width; x >= 1; x--) {
                int distance = distances[z][x];
                if (distance == 0) {
                    plan.floors[z - 1][x - 1] = OUTSIDE;
                    continue;
                }
                distance = Math.min(distance, 1 + Math.min(distances[z + 1][x], distances[z][x + 1]));
                distances[z][x] = distance;
                int step = Math.min(settings.steps(), (distance - 1) / settings.width());
                int floor = settings.baseY() - step * settings.drop();
                plan.floors[z - 1][x - 1] = floor;
                plan.deepest = Math.min(plan.deepest, floor);
            }
        }
        if (plan.floorAt(0, 0) != OUTSIDE) {
            throw new IllegalArgumentException("Keep the quarry block outside the marked outline.");
        }
        return plan;
    }

    int deepestY() {
        return deepest;
    }

    int floorAt(int x, int z) {
        if (x < bounds.minX() || x > bounds.maxX() || z < bounds.minZ() || z > bounds.maxZ()) {
            return OUTSIDE;
        }
        return floors[z - bounds.minZ()][x - bounds.minX()];
    }

    Bounds bounds() {
        return bounds;
    }

    int wallBottom(int x, int z, int floor) {
        return Math.min(floor, Math.min(Math.min(floorAt(x - 1, z), floorAt(x + 1, z)),
                Math.min(floorAt(x, z - 1), floorAt(x, z + 1))));
    }

    BlockState previewState(BlockGetter world, BlockPos pos, BlockState state, int x, int z, QuarryMaterials.Finish finish, Block support) {
        int floor = floorAt(x, z);
        int y = pos.getY();
        if (y > floor) {
            return canExcavate(world, pos, state) ? Blocks.AIR.defaultBlockState() : state;
        }
        if (floor == OUTSIDE || finish.isNatural() || y < floor - MAX_SETTING) {
            return state;
        }
        int bottom = wallBottom(x, z, floor);
        Surface surface = surfaceAt(y, floor, bottom);
        if (surface != null) {
            Block material = finish.material(surface);
            if (material != Blocks.AIR && canFinish(world, pos, state)) {
                return state.is(material) ? state : material.defaultBlockState();
            }
        }
        if (support != Blocks.AIR && supportAt(world, pos, state) == Support.NEEDED
                && hasFallingFinishAbove(world, pos, floor, bottom, finish)) {
            return support.defaultBlockState();
        }
        return state;
    }

    private static boolean hasFallingFinishAbove(BlockGetter preview, BlockPos pos, int floor, int bottom,
                                                 QuarryMaterials.Finish finish) {
        Surface above = surfaceAt(pos.getY() + 1, floor, bottom);
        // The virtual reader only checks a higher cell. A previously finished cell can already provide support.
        return above != null && finish.material(above) instanceof FallingBlock falling
                && preview.getBlockState(pos.above()).is(falling);
    }

    static int skyBlocker(BlockGetter preview, BlockPos pos, int floor) {
        BlockPos.MutableBlockPos probe = pos.mutable();
        for (int y = preview.getMaxBuildHeight() - 1; y >= floor; y--) {
            probe.setY(y);
            BlockState state = preview.getBlockState(probe);
            if (!state.isAir() && !state.propagatesSkylightDown(preview, probe)) {
                return y;
            }
        }
        return floor - 1;
    }

    enum Surface { ROAD, WALL }

    enum Support { NONE, NEEDED, IMPOSSIBLE }

    static Support supportAt(BlockGetter world, BlockPos below, BlockState state) {
        if (!FallingBlock.isFree(state)) {
            return Support.NONE;
        }
        return below.getY() >= world.getMinBuildHeight() && canModify(world, below, state)
                ? Support.NEEDED : Support.IMPOSSIBLE;
    }

    static Surface surfaceAt(int y, int floor, int wallBottom) {
        if (y > floor) {
            return null;
        }
        if (y > wallBottom) {
            // The wall finish includes the terrace lip.
            return Surface.WALL;
        }
        return y == floor ? Surface.ROAD : null;
    }

    static boolean canModify(BlockGetter world, BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        // Removing one machine or pole part can dismantle the rest beyond the cut.
        return !(block instanceof MachineBlock) && !(block instanceof PoleBlock) && !state.hasBlockEntity()
                && state.getDestroySpeed(world, pos) >= 0;
    }

    static boolean canExcavate(BlockGetter world, BlockPos pos, BlockState state) {
        return !state.isAir() && canModify(world, pos, state);
    }

    static boolean canFinish(BlockGetter world, BlockPos pos, BlockState state) {
        return canExcavate(world, pos, state)
                && (state.getBlock() instanceof LiquidBlock ? state.getFluidState().isSource()
                : state.isCollisionShapeFullBlock(world, pos));
    }

    private static void validateOutline(List<Corner> corners) {
        if (corners.size() < 3) {
            throw new IllegalArgumentException("Mark at least three corners in the world preview.");
        }
        if (corners.stream().distinct().count() != corners.size()) {
            throw new IllegalArgumentException("Each corner must be a different block.");
        }
        long area = 0;
        int size = corners.size();
        for (int i = 0; i < size; i++) {
            Corner a = corners.get(i);
            Corner b = corners.get((i + 1) % size);
            area += (long) a.x() * b.z() - (long) b.x() * a.z();
            for (int j = i + 2; j < size; j++) {
                if ((j + 1) % size != i && intersects(a, b, corners.get(j), corners.get((j + 1) % size))) {
                    throw new IllegalArgumentException("The outline crosses itself. Mark corners in perimeter order.");
                }
            }
        }
        if (area == 0) {
            throw new IllegalArgumentException("The outline must enclose an area.");
        }
    }

    private static void markRow(List<Corner> outline, int minX, int z, int[] row, double[] crossings) {
        int count = 0;
        Corner previous = outline.getLast();
        for (Corner next : outline) {
            int low = Math.min(previous.z(), next.z());
            int high = Math.max(previous.z(), next.z());
            if (z == low && z == high) {
                Arrays.fill(row, Math.min(previous.x(), next.x()) - minX + 1, Math.max(previous.x(), next.x()) - minX + 2, 1);
            } else if (z >= low && z <= high) {
                long numerator = (long) (next.x() - previous.x()) * (z - previous.z());
                int denominator = next.z() - previous.z();
                if (numerator % denominator == 0) {
                    row[previous.x() - minX + (int) (numerator / denominator) + 1] = 1;
                }
                if (z < high) {
                    crossings[count++] = previous.x() - minX + (double) numerator / denominator;
                }
            }
            previous = next;
        }
        Arrays.sort(crossings, 0, count);
        for (int i = 0; i + 1 < count; i += 2) {
            Arrays.fill(row, (int) Math.ceil(crossings[i]) + 1, (int) Math.floor(crossings[i + 1]) + 2, 1);
        }
    }

    private static boolean intersects(Corner a, Corner b, Corner c, Corner d) {
        return Long.signum(cross(a, b, c)) * Long.signum(cross(a, b, d)) < 0
                && Long.signum(cross(c, d, a)) * Long.signum(cross(c, d, b)) < 0 || onSegment(a, b, c) || onSegment(a, b, d)
                || onSegment(c, d, a) || onSegment(c, d, b);
    }

    private static boolean onSegment(Corner a, Corner b, Corner p) {
        return cross(a, b, p) == 0 && p.x() >= Math.min(a.x(), b.x()) && p.x() <= Math.max(a.x(), b.x())
                && p.z() >= Math.min(a.z(), b.z()) && p.z() <= Math.max(a.z(), b.z());
    }

    private static long cross(Corner a, Corner b, Corner c) {
        return (long) (b.x() - a.x()) * (c.z() - a.z()) - (long) (b.z() - a.z()) * (c.x() - a.x());
    }
}
