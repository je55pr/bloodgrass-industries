package io.github.je55pr.bloodgrassindustries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Stream;
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
    private final Map<PoweredFurnace, List<BlockPos>> generatorsByFurnace = new WeakHashMap<>();
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

    boolean supply(ServerLevel level, PoweredFurnace furnace) {
        if (topologyChanged) {
            rebuild();
        }
        List<BlockPos> candidates = generatorsByFurnace.computeIfAbsent(furnace, machine -> findGenerators(machine.bounds()));
        for (BlockPos pos : candidates) {
            if (level.isLoaded(pos) && level.getBlockEntity(pos) instanceof BurnerGenerator generator
                    && generator.supply(level.getGameTime())) {
                return true;
            }
        }
        return false;
    }

    boolean hasCoverage(BoundingBox machine) {
        return coveringPoles(machine).findAny().isPresent();
    }

    private List<BlockPos> findGenerators(BoundingBox machine) {
        return coveringPoles(machine).sorted(coverageOrder(machine))
                .map(generatorsByPole::get)
                .distinct()
                .flatMap(List::stream)
                .toList();
    }

    private Stream<BlockPos> coveringPoles(BoundingBox machine) {
        return poles.stream().filter(pole -> coverageDistance(pole, machine) >= 0);
    }

    private static Comparator<BlockPos> coverageOrder(BoundingBox machine) {
        return Comparator.comparingInt((BlockPos pole) -> coverageDistance(pole, machine))
                .thenComparing(Comparator.naturalOrder());
    }

    private static int coverageDistance(BlockPos pole, BoundingBox machine) {
        int dx = distanceToRange(pole.getX(), machine.minX(), machine.maxX());
        int dy = distanceToRange(pole.getY(), machine.minY(), machine.maxY());
        int dz = distanceToRange(pole.getZ(), machine.minZ(), machine.maxZ());
        if (dx > HORIZONTAL_COVERAGE || dz > HORIZONTAL_COVERAGE || dy > VERTICAL_COVERAGE) {
            return -1;
        }
        return dx * dx + dy * dy + dz * dz;
    }

    private void rebuild() {
        generatorsByPole.clear();
        generatorsByFurnace.clear();
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
            BoundingBox bounds = generators.get(generator);
            coveringPoles(bounds).forEach(pole -> {
                List<BlockPos> circuitGenerators = generatorsByPole.get(pole);
                if (!circuitGenerators.contains(generator)) {
                    circuitGenerators.add(generator);
                }
            });
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
