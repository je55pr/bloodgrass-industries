package io.github.je55pr.bloodgrassindustries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

final class PowerGrid {
    private static final int LINK_RANGE = 8;
    private static final int HORIZONTAL_COVERAGE = 3;
    private static final int VERTICAL_COVERAGE = 2;
    private static final Map<ServerLevel, PowerGrid> LEVELS = new WeakHashMap<>();

    private final Set<BlockPos> poles = new HashSet<>();
    private final Map<BlockPos, BoundingBox> generators = new HashMap<>();
    private final Map<BlockPos, List<BlockPos>> generatorsByPole = new HashMap<>();
    private boolean topologyChanged = true;

    static PowerGrid of(ServerLevel level) {
        return LEVELS.computeIfAbsent(level, ignored -> new PowerGrid());
    }

    void addPole(BlockPos pos) {
        topologyChanged |= poles.add(pos);
    }

    void removePole(BlockPos pos) {
        topologyChanged |= poles.remove(pos);
    }

    void addGenerator(BlockPos pos, BoundingBox bounds) {
        generators.put(pos, bounds);
        topologyChanged = true;
    }

    void removeGenerator(BlockPos pos) {
        topologyChanged |= generators.remove(pos) != null;
    }

    boolean supply(ServerLevel level, BoundingBox machine) {
        BlockPos pole = coveringPole(machine);
        if (pole == null) {
            return false;
        }
        if (topologyChanged) {
            rebuild();
        }
        for (BlockPos pos : generatorsByPole.get(pole)) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof BurnerGenerator generator
                    && generator.supply(level.getGameTime())) {
                return true;
            }
        }
        return false;
    }

    boolean hasCoverage(BoundingBox machine) {
        return coveringPole(machine) != null;
    }

    private BlockPos coveringPole(BoundingBox machine) {
        BlockPos nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (BlockPos pole : poles) {
            int dx = distanceToRange(pole.getX(), machine.minX(), machine.maxX());
            int dy = distanceToRange(pole.getY(), machine.minY(), machine.maxY());
            int dz = distanceToRange(pole.getZ(), machine.minZ(), machine.maxZ());
            if (dx > HORIZONTAL_COVERAGE || dz > HORIZONTAL_COVERAGE || dy > VERTICAL_COVERAGE) {
                continue;
            }
            int distance = dx * dx + dy * dy + dz * dz;
            if (nearest == null || distance < nearestDistance
                    || (distance == nearestDistance && pole.compareTo(nearest) < 0)) {
                nearest = pole;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private void rebuild() {
        generatorsByPole.clear();
        Set<BlockPos> remaining = new HashSet<>(poles);
        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            remaining.remove(start);
            ArrayDeque<BlockPos> pending = new ArrayDeque<>();
            List<BlockPos> circuitGenerators = new ArrayList<>();
            pending.add(start);
            while (!pending.isEmpty()) {
                BlockPos pole = pending.remove();
                generatorsByPole.put(pole, circuitGenerators);
                for (var positions = remaining.iterator(); positions.hasNext();) {
                    BlockPos candidate = positions.next();
                    if (withinLinkRange(pole, candidate)) {
                        positions.remove();
                        pending.add(candidate);
                    }
                }
            }
        }
        for (BlockPos generator : generators.keySet().stream().sorted().toList()) {
            BlockPos pole = coveringPole(generators.get(generator));
            if (pole != null) {
                generatorsByPole.get(pole).add(generator);
            }
        }
        topologyChanged = false;
    }

    int showLinks(ServerLevel level, BlockPos base) {
        Vec3 start = Vec3.atCenterOf(base.above(PoleBlock.HEIGHT - 1));
        int links = 0;
        for (BlockPos pole : poles) {
            if (!pole.equals(base) && withinLinkRange(base, pole)) {
                links++;
                Vec3 end = Vec3.atCenterOf(pole.above(PoleBlock.HEIGHT - 1));
                int points = (int) Math.ceil(start.distanceTo(end) * 4);
                for (int point = 1; point < points; point++) {
                    Vec3 particle = start.lerp(end, (double) point / points);
                    level.sendParticles(ParticleTypes.ELECTRIC_SPARK, particle.x, particle.y, particle.z, 1, 0, 0, 0, 0);
                }
            }
        }
        return links;
    }

    private static boolean withinLinkRange(BlockPos first, BlockPos second) {
        return first.distSqr(second) <= LINK_RANGE * LINK_RANGE;
    }

    private static int distanceToRange(int coordinate, int min, int max) {
        return Math.max(0, Math.max(min - coordinate, coordinate - max));
    }
}
