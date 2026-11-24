package io.github.je55pr.bloodgrassindustries;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

final class QuarryBlockEntity extends BlockEntity {
    private QuarryPlan.Settings settings;
    private QuarryMaterials.Finish finish = QuarryMaterials.Finish.NATURAL;
    private QuarryExcavation excavation;
    private Survey survey;
    private SurveyResult surveyResult;
    private Map<Item, Long> lastOutput = Map.of();
    private boolean hasLastOutput;

    QuarryBlockEntity(BlockPos pos, BlockState state) {
        super(BloodgrassIndustries.QUARRY_ENTITY.get(), pos, state);
        settings = QuarryPlan.Settings.defaults(pos.getY() - 1);
    }

    QuarryPlan.Settings settings() {
        return settings;
    }

    QuarryMaterials.Finish finish() {
        return finish;
    }

    Map<Item, Long> lastOutput() {
        return lastOutput;
    }

    boolean hasLastOutput() {
        return hasLastOutput;
    }

    void sendMaterialSurvey(ServerPlayer player, int containerId) {
        if (surveyResult != null) {
            PacketDistributor.sendToPlayer(player, new QuarryMaterialPayload(containerId, surveyResult.settings(),
                    "", surveyResult.report().save()));
        }
        if (survey != null) {
            sendSurveyStatus(player, containerId, survey.settings(), "Surveying the cut.");
        }
    }

    private static void sendSurveyStatus(ServerPlayer player, int containerId, QuarryPlan.Settings settings, String message) {
        PacketDistributor.sendToPlayer(player, new QuarryMaterialPayload(containerId, settings, message, null));
    }

    void apply(ServerPlayer player, int containerId, QuarryPlan.Settings draft, QuarryMaterials.Finish selected, QuarryPayload.Action action) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        boolean excavate = action == QuarryPayload.Action.EXCAVATE;
        try {
            draft.validate(server.getMinBuildHeight(), server.getMaxBuildHeight());
            if (excavation != null) {
                throw new IllegalArgumentException("This quarry already has an excavation in progress.");
            }
            if (excavate || action == QuarryPayload.Action.SURVEY) {
                if (!player.isCreative()) {
                    throw new IllegalArgumentException("Quarry surveying and excavation require Creative mode.");
                }
                QuarryPlan plan = QuarryPlan.create(draft, server.getMinBuildHeight(), server.getMaxBuildHeight());
                QuarryExcavation.validateSite(server, player, worldPosition, plan);
                if (action == QuarryPayload.Action.SURVEY) {
                    if (survey != null && survey.settings().equals(draft)) {
                        sendSurveyStatus(player, containerId, draft, "Surveying the cut.");
                        return;
                    }
                    if (survey != null && survey.job().player(server) != player) {
                        throw new IllegalArgumentException("Another player is surveying this quarry.");
                    }
                    cancelSurvey(server);
                    survey = new Survey(QuarryExcavation.survey(player.getUUID(), plan), draft);
                    sendSurveyStatus(player, containerId, draft, "Surveying the cut.");
                    return;
                }
                // Terrain may have changed since a previous or partial survey.
                cancelSurvey(server);
                excavation = QuarryExcavation.start(player.getUUID(), plan, selected);
            }
            settings = draft;
            finish = selected;
            setChanged();
            player.closeContainer();
            String message = !excavate ? "Quarry settings saved." : excavation.checkingFinish()
                    ? "Checking materials before excavation. Keep the quarry block loaded."
                    : "Excavation started. Keep the quarry block loaded.";
            player.displayClientMessage(Component.literal(message), true);
        } catch (IllegalArgumentException invalid) {
            if (action == QuarryPayload.Action.SURVEY) {
                sendSurveyStatus(player, containerId, draft, invalid.getMessage());
            } else {
                player.closeContainer();
                player.displayClientMessage(Component.literal(invalid.getMessage()), true);
            }
        }
    }

    static void tick(Level level, BlockPos pos, BlockState state, QuarryBlockEntity quarry) {
        if (level instanceof ServerLevel server) {
            if (quarry.survey != null) {
                quarry.surveyBatch(server);
            }
            if (quarry.excavation != null) {
                quarry.excavateBatch(server);
            }
        }
    }

    private void surveyBatch(ServerLevel level) {
        Survey current = survey;
        ServerPlayer player = current.job().player(level);
        if (player == null || player.level() != level || !(player.isCreative()
                || player.isSpectator() && player.containerMenu instanceof QuarryMenu menu && menu.surveying)) {
            cancelSurvey(level);
            return;
        }
        String error = null;
        try {
            if (!current.job().tick(level, worldPosition, current.settings(), player, QuarryMaterials.Finish.NATURAL)) {
                return;
            }
            surveyResult = new SurveyResult(current.settings(), current.job().report());
            setChanged();
        } catch (IllegalArgumentException invalid) {
            error = invalid.getMessage();
        }
        cancelSurvey(level);
        for (ServerPlayer viewer : level.players()) {
            if (viewer.containerMenu instanceof QuarryMenu menu && menu.quarry() == this) {
                if (error == null) {
                    sendMaterialSurvey(viewer, menu.containerId);
                } else {
                    sendSurveyStatus(viewer, menu.containerId, current.settings(), error);
                }
            }
        }
    }

    private void cancelSurvey(ServerLevel level) {
        if (survey != null) {
            survey.job().release(level, worldPosition);
            survey = null;
        }
    }

    private void excavateBatch(ServerLevel level) {
        ServerPlayer player = excavation.player(level);
        if (player == null || !player.isCreative() || player.level() != level) {
            releaseChunk();
            return;
        }
        String result;
        boolean checkingFinish = excavation.checkingFinish();
        try {
            if (!excavation.tick(level, worldPosition, settings, player, finish)) {
                setChanged();
                return;
            }
            result = checkingFinish ? "The cut cannot supply the selected finishes. No blocks were removed."
                    : "Quarry excavated. Removed " + excavation.removed() + " blocks."
                    + (excavation.finishUnavailable() ? " The cut could no longer supply the selected finishes." : "");
        } catch (IllegalArgumentException invalid) {
            result = invalid.getMessage();
        }
        if (checkingFinish) {
            if (excavation.finishUnavailable()) {
                surveyResult = new SurveyResult(settings, excavation.report());
            }
        } else {
            lastOutput = excavation.output(finish);
            hasLastOutput = true;
            surveyResult = null;
        }
        releaseChunk();
        excavation = null;
        setChanged();
        player.displayClientMessage(Component.literal(result), false);
    }

    @Override
    public void onChunkUnloaded() {
        releaseChunk();
        super.onChunkUnloaded();
    }

    @Override
    public void setRemoved() {
        releaseChunk();
        super.setRemoved();
    }

    private void releaseChunk() {
        if (level instanceof ServerLevel server) {
            cancelSurvey(server);
            if (excavation != null) {
                excavation.release(server, worldPosition);
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        settings.save(tag);
        tag.put("Finish", finish.save());
        if (surveyResult != null) {
            CompoundTag cached = surveyResult.report().save();
            surveyResult.settings().save(cached);
            tag.put("MaterialSurvey", cached);
        }
        if (hasLastOutput) {
            tag.put("LastOutput", QuarryMaterials.saveOutput(lastOutput));
        }
        if (excavation != null) {
            tag.put("Excavation", excavation.save());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        QuarryPlan.Settings saved = QuarryPlan.Settings.load(tag);
        if (saved != null) {
            settings = saved;
        }
        CompoundTag cached = tag.getCompound("MaterialSurvey");
        QuarryPlan.Settings surveyed = QuarryPlan.Settings.load(cached);
        surveyResult = surveyed == null ? null : new SurveyResult(surveyed, QuarryMaterials.Report.load(cached));
        excavation = QuarryExcavation.load(tag.getCompound("Excavation"));
        finish = QuarryMaterials.Finish.load(tag.getCompound("Finish"));
        hasLastOutput = tag.contains("LastOutput");
        lastOutput = QuarryMaterials.loadOutput(tag.getCompound("LastOutput"));
    }

    private record Survey(QuarryExcavation job, QuarryPlan.Settings settings) {}

    private record SurveyResult(QuarryPlan.Settings settings, QuarryMaterials.Report report) {}
}
