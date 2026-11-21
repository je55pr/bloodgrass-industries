package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(BloodgrassIndustries.ID)
@PrefixGameTestTemplate(false)
public final class PowerGridGameTests {
    private static final BlockPos FIRST_POLE = new BlockPos(5, 2, 4);

    @GameTest(template = "power_grid")
    public static void oneItemPlacesAndRetrievesEverySection(GameTestHelper test) {
        int expectedHeight = 4;
        ServerPlayer player = survivalPlayer(test);
        test.setBlock(FIRST_POLE.below(), Blocks.STONE);
        var pole = BloodgrassIndustries.POWER_POLE.get();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(pole));

        for (int section = 0; section < expectedHeight; section++) {
            test.assertTrue(useOn(test, player, FIRST_POLE.below()).consumesAction(), "Pole item could not be placed");
            for (int part = 0; part < expectedHeight; part++) {
                test.assertBlockProperty(FIRST_POLE.above(part), PoleBlock.SECTION, part);
            }
            test.assertBlockPresent(Blocks.AIR, FIRST_POLE.above(expectedHeight));
            test.assertTrue(countPoles(player) == 0, "Placement did not consume exactly one pole item");
            test.assertTrue(player.gameMode.destroyBlock(test.absolutePos(FIRST_POLE.above(section))), "Could not break pole section " + section);
            for (int part = 0; part < expectedHeight; part++) {
                test.assertBlockPresent(Blocks.AIR, FIRST_POLE.above(part));
            }
            test.assertTrue(countPoles(player) == 1, "Breaking a pole section did not return exactly one item");
            test.assertItemEntityNotPresent(pole.asItem());
        }
        test.succeed();
    }

    @GameTest(template = "power_grid")
    public static void blockedPlacementKeepsItem(GameTestHelper test) {
        ServerPlayer player = survivalPlayer(test);
        BlockPos top = FIRST_POLE.above(3);
        test.setBlock(FIRST_POLE.below(), Blocks.STONE);
        test.setBlock(top, Blocks.STONE);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BloodgrassIndustries.POWER_POLE.get()));

        test.assertFalse(useOn(test, player, FIRST_POLE.below()).consumesAction(), "Placed a pole through an occupied block");
        test.assertTrue(countPoles(player) == 1, "Blocked placement took the pole item");
        test.assertBlockPresent(Blocks.AIR, FIRST_POLE);
        test.assertBlockPresent(Blocks.AIR, FIRST_POLE.above());
        test.assertBlockPresent(Blocks.AIR, FIRST_POLE.above(2));
        test.assertBlockPresent(Blocks.STONE, top);
        test.succeed();
    }

    @GameTest(template = "power_grid")
    public static void machinesPlaceTheirFootprintAndBreakAsOne(GameTestHelper test) {
        ServerPlayer player = survivalPlayer(test);
        Block generator = BloodgrassIndustries.BURNER_GENERATOR.get();
        Block furnace = BloodgrassIndustries.POWERED_FURNACE.get();
        BlockPos generatorBase = new BlockPos(6, 2, 6);
        BlockPos generatorCorner = generatorBase.east().north(2).above();
        BlockPos furnaceBase = new BlockPos(14, 2, 6);
        BlockPos furnaceCorner = furnaceBase.west().north().above(2);

        test.setBlock(generatorBase.below(), Blocks.STONE);
        test.setBlock(generatorCorner, Blocks.STONE);
        player.setYRot(180);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(generator));
        test.assertFalse(useOn(test, player, generatorBase.below()).consumesAction(), "Generator placed through an occupied part");
        test.assertTrue(player.getMainHandItem().getCount() == 1, "Blocked generator placement consumed its item");
        test.assertBlockPresent(Blocks.STONE, generatorCorner);
        test.assertBlockPresent(Blocks.AIR, generatorBase);

        test.setBlock(generatorCorner, Blocks.AIR);
        placeMachine(test, player, generatorBase, generator, 180);
        placeMachine(test, player, furnaceBase, furnace, 90);
        assertMachineVolume(test, generator, generatorBase, generatorCorner);
        assertMachineVolume(test, furnace, furnaceBase, furnaceCorner);
        test.assertBlockPresent(Blocks.AIR, generatorCorner.above());
        test.assertBlockPresent(Blocks.AIR, generatorCorner.north());
        test.assertBlockPresent(Blocks.AIR, furnaceCorner.above());
        test.assertBlockPresent(Blocks.AIR, furnaceCorner.west());

        feed(test, player, generatorCorner, new ItemStack(Items.COAL));
        test.assertTrue(player.gameMode.destroyBlock(test.absolutePos(generatorCorner)), "Could not break the generator corner");
        test.assertTrue(player.getInventory().countItem(generator.asItem()) == 1, "Generator did not return exactly one item");
        test.assertItemEntityNotPresent(generator.asItem());
        test.assertItemEntityCountIs(Items.COAL, generatorBase, 2, 1);
        assertMachineVolume(test, Blocks.AIR, generatorBase, generatorCorner);

        test.assertTrue(player.gameMode.destroyBlock(test.absolutePos(furnaceBase)), "Could not break the furnace anchor");
        test.assertTrue(player.getInventory().countItem(furnace.asItem()) == 1, "Furnace did not return exactly one item");
        test.assertItemEntityNotPresent(furnace.asItem());
        assertMachineVolume(test, Blocks.AIR, furnaceBase, furnaceCorner);
        test.succeed();
    }

    @GameTest(template = "power_grid", timeoutTicks = 510)
    public static void furnaceStopsWhenLinkBreaksAndResumesWhenRestored(GameTestHelper test) {
        ServerPlayer player = survivalPlayer(test);
        BlockPos middle = FIRST_POLE.east(8);
        BlockPos far = FIRST_POLE.east(16);
        placePole(test, player, FIRST_POLE);
        placePole(test, player, middle);
        placePole(test, player, far);

        // Each powered machine has an anchor outside coverage and an outer part inside it.
        BlockPos generator = FIRST_POLE.west(5);
        BlockPos furnace = far.east(4);
        BlockPos furnaceCorner = furnace.west().north().above(2);
        BlockPos outside = furnace.south(3).east(2);
        placeMachine(test, player, generator, BloodgrassIndustries.BURNER_GENERATOR.get(), -90);
        placeMachine(test, player, furnace, BloodgrassIndustries.POWERED_FURNACE.get(), 90);
        placeMachine(test, player, outside, BloodgrassIndustries.POWERED_FURNACE.get(), 0);
        feed(test, player, generator.east(2).south().above(), new ItemStack(Items.COAL));
        feed(test, player, furnaceCorner, new ItemStack(Items.RAW_IRON));
        feed(test, player, outside, new ItemStack(Items.RAW_IRON));

        test.runAtTickTime(30, () -> {
            test.assertBlockProperty(generator, MachineBlock.LIT, true);
            test.assertBlockProperty(furnace, MachineBlock.LIT, true);
            test.assertBlockProperty(furnaceCorner, MachineBlock.LIT, true);
            test.assertBlockProperty(outside, MachineBlock.LIT, false);
            test.assertTrue(player.gameMode.destroyBlock(test.absolutePos(middle.above())), "Could not break the middle pole");
        });
        test.runAtTickTime(245, () -> {
            test.assertBlockProperty(generator, MachineBlock.LIT, false);
            test.assertBlockProperty(furnace, MachineBlock.LIT, false);
            test.assertBlockProperty(outside, MachineBlock.LIT, false);
            collect(test, player, furnaceCorner);
            test.assertTrue(player.getMainHandItem().isEmpty() && !player.getInventory().contains(new ItemStack(Items.IRON_INGOT)),
                    "Furnace completed a recipe while disconnected");
            placePole(test, player, middle);
        });
        test.runAtTickTime(485, () -> {
            collect(test, player, furnaceCorner);
            test.assertTrue(player.getInventory().contains(new ItemStack(Items.IRON_INGOT)), "Furnace did not deliver an iron ingot");
            player.getInventory().clearContent();
            collect(test, player, outside);
            test.assertTrue(!player.getInventory().contains(new ItemStack(Items.IRON_INGOT)), "Out-of-coverage furnace produced output");
            test.succeed();
        });
    }

    private static ServerPlayer survivalPlayer(GameTestHelper test) {
        ServerPlayer player = test.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        return player;
    }

    private static int countPoles(ServerPlayer player) {
        return player.getInventory().countItem(BloodgrassIndustries.POWER_POLE.get().asItem());
    }

    private static void placePole(GameTestHelper test, ServerPlayer player, BlockPos base) {
        test.setBlock(base.below(), Blocks.STONE);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(BloodgrassIndustries.POWER_POLE.get()));
        test.assertTrue(useOn(test, player, base.below()).consumesAction(), "Could not place test pole at " + base);
    }

    private static void placeMachine(GameTestHelper test, ServerPlayer player, BlockPos base, Block machine, float yaw) {
        test.setBlock(base.below(), Blocks.STONE);
        player.setYRot(yaw);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(machine));
        test.assertTrue(useOn(test, player, base.below()).consumesAction(), "Could not place test machine at " + base);
        test.assertTrue(player.getMainHandItem().isEmpty(), "Machine placement did not consume one item at " + base);
    }

    private static void assertMachineVolume(GameTestHelper test, Block block, BlockPos base, BlockPos opposite) {
        for (BlockPos pos : BlockPos.betweenClosed(base, opposite)) {
            test.assertBlockPresent(block, pos);
            boolean hasMachine = test.getLevel().getBlockEntity(test.absolutePos(pos)) instanceof MachineBlockEntity;
            test.assertTrue(hasMachine == (block != Blocks.AIR && pos.equals(base)),
                    "Unexpected machine block entity at " + pos);
        }
    }

    private static void feed(GameTestHelper test, ServerPlayer player, BlockPos machine, ItemStack item) {
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        test.assertTrue(useOn(test, player, machine).consumesAction(), "Could not feed test machine at " + machine);
        test.assertTrue(player.getMainHandItem().isEmpty(), "Machine did not accept its input at " + machine);
    }

    private static void collect(GameTestHelper test, ServerPlayer player, BlockPos machine) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        useOn(test, player, machine);
    }

    private static InteractionResult useOn(GameTestHelper test, ServerPlayer player, BlockPos pos) {
        BlockPos absolute = test.absolutePos(pos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute).add(0, 0.5, 0), Direction.UP, absolute, false);
        return player.gameMode.useItemOn(player, test.getLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
    }
}
