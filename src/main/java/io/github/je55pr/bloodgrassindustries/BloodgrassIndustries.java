package io.github.je55pr.bloodgrassindustries;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(BloodgrassIndustries.ID)
public final class BloodgrassIndustries {
    public static final String ID = "bloodgrass_industries";

    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, ID);

    static final DeferredBlock<QuarryBlock> QUARRY = BLOCKS.register("quarry",
            () -> new QuarryBlock(BlockBehaviour.Properties.of().strength(1.5F)));
    static final DeferredHolder<MenuType<?>, MenuType<QuarryMenu>> QUARRY_MENU = MENUS.register("quarry",
            () -> IMenuTypeExtension.create(QuarryMenu::new));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<QuarryBlockEntity>> QUARRY_ENTITY =
            BLOCK_ENTITIES.register("quarry", () -> BlockEntityType.Builder.of(QuarryBlockEntity::new, QUARRY.get()).build(null));

    static final DeferredBlock<PoleBlock> POWER_POLE = BLOCKS.register("power_pole",
            () -> new PoleBlock(BlockBehaviour.Properties.of().strength(0.4F).noOcclusion().noLootTable()));
    static final DeferredBlock<MachineBlock> BURNER_GENERATOR = BLOCKS.register("burner_generator",
            () -> new MachineBlock(BlockBehaviour.Properties.of().strength(0.8F).noLootTable(),
                    3, 2, BloodgrassIndustries::burnerType));
    static final DeferredBlock<MachineBlock> POWERED_FURNACE = BLOCKS.register("powered_furnace",
            () -> new MachineBlock(BlockBehaviour.Properties.of().strength(0.8F).noLootTable(),
                    2, 3, BloodgrassIndustries::furnaceType));

    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PoleBlockEntity>> POLE_ENTITY =
            BLOCK_ENTITIES.register("power_pole", () -> BlockEntityType.Builder.of(PoleBlockEntity::new, POWER_POLE.get()).build(null));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BurnerGenerator>> BURNER_ENTITY =
            BLOCK_ENTITIES.register("burner_generator", () -> BlockEntityType.Builder.of(BurnerGenerator::new, BURNER_GENERATOR.get()).build(null));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PoweredFurnace>> FURNACE_ENTITY =
            BLOCK_ENTITIES.register("powered_furnace", () -> BlockEntityType.Builder.of(PoweredFurnace::new, POWERED_FURNACE.get()).build(null));

    public BloodgrassIndustries(IEventBus modBus) {
        ITEMS.registerSimpleBlockItem(POWER_POLE);
        ITEMS.registerSimpleBlockItem(QUARRY);
        ITEMS.registerSimpleBlockItem(BURNER_GENERATOR);
        ITEMS.registerSimpleBlockItem(POWERED_FURNACE);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
        modBus.addListener(QuarryPayload::register);
        modBus.addListener(BloodgrassIndustries::addCreativeItems);
    }

    static BlockEntityType<BurnerGenerator> burnerType() {
        return BURNER_ENTITY.get();
    }

    static BlockEntityType<PoweredFurnace> furnaceType() {
        return FURNACE_ENTITY.get();
    }

    private static void addCreativeItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(CreativeModeTabs.FUNCTIONAL_BLOCKS)) {
            event.accept(POWER_POLE.get());
            event.accept(BURNER_GENERATOR.get());
            event.accept(QUARRY.get());
            event.accept(POWERED_FURNACE.get());
        }
    }
}
