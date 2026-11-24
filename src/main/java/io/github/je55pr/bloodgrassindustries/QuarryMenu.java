package io.github.je55pr.bloodgrassindustries;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

final class QuarryMenu extends AbstractContainerMenu {
    final BlockPos position;
    final QuarryPlan.Settings settings;
    final QuarryMaterials.Finish finish;
    final Map<Item, Long> lastOutput;
    final boolean hasLastOutput;
    QuarryMaterials.Report materialReport;
    QuarryPlan.Settings materialSettings;
    QuarryPlan.Settings materialStatusSettings;
    String materialStatus = "";
    private final ServerLevel level;
    private final UUID revision;
    boolean surveying;

    QuarryMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(BloodgrassIndustries.QUARRY_MENU.get(), id);
        position = buffer.readBlockPos();
        settings = QuarryPayload.readSettings(buffer);
        finish = QuarryMaterials.Finish.load(buffer.readNbt());
        hasLastOutput = buffer.readBoolean();
        lastOutput = QuarryMaterials.loadOutput(buffer.readNbt());
        level = null;
        revision = null;
    }

    QuarryMenu(int id, QuarryBlockEntity quarry) {
        super(BloodgrassIndustries.QUARRY_MENU.get(), id);
        level = (ServerLevel) quarry.getLevel();
        revision = quarry.menuRevision();
        position = quarry.getBlockPos();
        settings = quarry.settings();
        finish = quarry.finish();
        lastOutput = quarry.lastOutput();
        hasLastOutput = quarry.hasLastOutput();
    }

    @Override
    public boolean stillValid(Player player) {
        if (level == null) {
            return true;
        }
        if (player.level() != level) {
            return false;
        }
        if (surveying && player.isSpectator()) {
            // The quarry can go out of chunk range during a large preview.
            return !level.isLoaded(position) || quarry() != null;
        }
        return quarry() != null && stillValid(ContainerLevelAccess.create(level, position), player, BloodgrassIndustries.QUARRY.get());
    }

    boolean isAt(ServerLevel server, BlockPos pos) {
        return level == server && position.equals(pos);
    }

    QuarryBlockEntity quarry() {
        return level != null && level.isLoaded(position) && level.getBlockEntity(position) instanceof QuarryBlockEntity quarry
                && revision.equals(quarry.menuRevision()) ? quarry : null;
    }

    void forgetReport(QuarryPlan.Settings surveyed) {
        if (surveyed.equals(materialSettings)) {
            materialSettings = null;
            materialReport = null;
        }
    }

    @Override
    public void removed(Player player) {
        if (surveying && player instanceof ServerPlayer serverPlayer) {
            surveying = false;
            QuarrySurvey.restore(serverPlayer);
        }
        super.removed(player);
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
