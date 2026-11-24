package io.github.je55pr.bloodgrassindustries;

import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;

final class QuarryBlockEntity extends BlockEntity {
    static final String SURVEYING = "Surveying the cut.";
    private static final String LAST_OUTPUT_KEY = "LastOutput";
    private static final String MENU_REVISION_KEY = "MenuRevision";

    private UUID menuRevision = UUID.randomUUID();
    private boolean hasSavedMenuRevision;
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

    UUID menuRevision() {
        return menuRevision;
    }

    void sendMaterialSurvey(ServerPlayer player, int containerId) {
        if (surveyResult != null) {
            PacketDistributor.sendToPlayer(player, new QuarryMaterialPayload(containerId, surveyResult.settings(),
                    "", surveyResult.report().save()));
        }
        if (survey != null) {
            sendSurveyStatus(player, containerId, survey.settings(), SURVEYING);
        }
    }

    private static void sendSurveyStatus(ServerPlayer player, int containerId, QuarryPlan.Settings settings, String message) {
        PacketDistributor.sendToPlayer(player, new QuarryMaterialPayload(containerId, settings, message, null));
    }

    void apply(ServerPlayer player, int containerId, QuarryPlan.Settings draft, QuarryMaterials.Finish selected, QuarryPayload.Action action) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        try {
            if (excavation != null) {
                throw new IllegalArgumentException("This quarry already has an excavation in progress.");
            }
            switch (action) {
                case SURVEY -> requestSurvey(server, player, containerId, draft);
                case SAVE -> {
                    draft.validate(server.getMinBuildHeight(), server.getMaxBuildHeight());
                    saveSettings(server, player, draft, selected);
                    player.displayClientMessage(Component.literal("Quarry settings saved."), true);
                }
                case EXCAVATE -> startExcavation(server, player, draft, selected);
            }
        } catch (IllegalArgumentException invalid) {
            if (action == QuarryPayload.Action.SURVEY) {
                sendSurveyStatus(player, containerId, draft, invalid.getMessage());
            } else {
                player.closeContainer();
                player.displayClientMessage(Component.literal(invalid.getMessage()), true);
            }
        }
    }

    private void requestSurvey(ServerLevel server, ServerPlayer player, int containerId, QuarryPlan.Settings draft) {
        requireCreative(player);
        if (survey != null) {
            if (survey.settings().equals(draft)) {
                sendSurveyStatus(player, containerId, draft, SURVEYING);
                return;
            }
            if (survey.job().player(server) != player) {
                throw new IllegalArgumentException("Another player is surveying this quarry.");
            }
            QuarryPlan.Settings canceled = survey.settings();
            cancelSurvey(server);
            publishSurvey(server, canceled, "Material survey canceled.", null);
        }
        if (surveyResult != null && surveyResult.settings().equals(draft)) {
            surveyResult = null;
            setChanged();
        }
        try {
            QuarryPlan plan = validatedPlan(server, player, draft);
            survey = new Survey(QuarryExcavation.survey(player.getUUID(), plan), draft);
            publishSurvey(server, draft, SURVEYING, null);
        } catch (IllegalArgumentException invalid) {
            publishSurvey(server, draft, invalid.getMessage(), null);
        }
    }

    private void startExcavation(ServerLevel server, ServerPlayer player, QuarryPlan.Settings draft, QuarryMaterials.Finish selected) {
        requireCreative(player);
        QuarryPlan plan = validatedPlan(server, player, draft);
        cancelSurvey(server);
        // Recheck the stock against the current terrain even when the menu has a saved survey.
        excavation = QuarryExcavation.start(player.getUUID(), plan, selected);
        saveSettings(server, player, draft, selected);
        String message = excavation.checkingFinish() ? "Checking materials before excavation. Keep the quarry block loaded."
                : "Excavation started. Keep the quarry block loaded.";
        player.displayClientMessage(Component.literal(message), true);
    }

    private static void requireCreative(ServerPlayer player) {
        if (!player.isCreative()) {
            throw new IllegalArgumentException("Quarry surveying and excavation require Creative mode.");
        }
    }

    private QuarryPlan validatedPlan(ServerLevel server, ServerPlayer player, QuarryPlan.Settings draft) {
        QuarryPlan plan = QuarryPlan.create(draft, server.getMinBuildHeight(), server.getMaxBuildHeight());
        QuarryExcavation.validateSite(server, player, worldPosition, plan);
        return plan;
    }

    private void saveSettings(ServerLevel server, ServerPlayer player, QuarryPlan.Settings draft, QuarryMaterials.Finish selected) {
        settings = draft;
        finish = selected;
        invalidateMenus(server, player);
    }

    private void invalidateMenus(ServerLevel server, ServerPlayer actor) {
        menuRevision = UUID.randomUUID();
        setChanged();
        for (ServerPlayer viewer : server.players()) {
            if (viewer.containerMenu instanceof QuarryMenu menu && menu.isAt(server, worldPosition)) {
                viewer.closeContainer();
                if (viewer != actor) {
                    viewer.displayClientMessage(Component.literal("The quarry changed. Reopen it to see the latest settings and totals."), true);
                }
            }
        }
    }

    void tick(ServerLevel level) {
        if (survey != null) {
            surveyBatch(level);
        }
        if (excavation != null) {
            excavateBatch(level);
        }
    }

    private void surveyBatch(ServerLevel level) {
        Survey current = survey;
        ServerPlayer player = current.job().player(level);
        if (player == null || player.level() != level || !(player.isCreative()
                || player.isSpectator() && player.containerMenu instanceof QuarryMenu menu && menu.surveying)) {
            cancelSurvey(level);
            publishSurvey(level, current.settings(), "Material survey canceled.", null);
            return;
        }
        try {
            if (!current.job().tick(level, worldPosition, current.settings(), player, QuarryMaterials.Finish.NATURAL)) {
                return;
            }
            QuarryMaterials.Report report = current.job().report();
            surveyResult = new SurveyResult(current.settings(), report);
            setChanged();
            publishSurvey(level, current.settings(), "", report);
        } catch (IllegalArgumentException invalid) {
            publishSurvey(level, current.settings(), invalid.getMessage(), null);
        }
        cancelSurvey(level);
    }

    private void publishSurvey(ServerLevel level, QuarryPlan.Settings draft, String status, QuarryMaterials.Report report) {
        CompoundTag tag = report == null ? null : report.save();
        for (ServerPlayer viewer : level.players()) {
            if (viewer.containerMenu instanceof QuarryMenu menu && menu.isAt(level, worldPosition)) {
                PacketDistributor.sendToPlayer(viewer, new QuarryMaterialPayload(menu.containerId, draft, status, tag));
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
        boolean checkingFinish = excavation.checkingFinish();
        String result;
        try {
            if (!excavation.tick(level, worldPosition, settings, player, finish)) {
                setChanged();
                return;
            }
            result = finishExcavation(level, player, checkingFinish);
        } catch (IllegalArgumentException invalid) {
            result = invalid.getMessage();
            if (!checkingFinish && excavation.removed() > 0) {
                surveyResult = null;
                invalidateMenus(level, null);
                result += " Removed " + excavation.removed() + " blocks before stopping.";
            }
        }
        releaseChunk();
        excavation = null;
        setChanged();
        player.displayClientMessage(Component.literal(result), false);
    }

    private String finishExcavation(ServerLevel level, ServerPlayer player, boolean checkingFinish) {
        if (checkingFinish) {
            QuarryMaterials.Report report = excavation.report();
            surveyResult = new SurveyResult(settings, report);
            publishSurvey(level, settings, "", report);
            return report.surfaces().cannotSupport(finish) ? QuarryMaterials.NO_SUPPORT_SPACE + " No blocks were removed."
                    : "The cut cannot supply the selected finishes. No blocks were removed.";
        }
        lastOutput = excavation.output(finish);
        hasLastOutput = true;
        surveyResult = null;
        invalidateMenus(level, player);
        String result = "Quarry excavated. Removed " + excavation.removed() + " blocks.";
        return excavation.finishUnavailable() ? result + " The cut could no longer supply the selected finishes." : result;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel && !hasSavedMenuRevision) {
            // Give older quarry blocks a stable revision before a long preview leaves the chunk.
            hasSavedMenuRevision = true;
            setChanged();
        }
    }

    @Override
    public void onChunkUnloaded() {
        if (level instanceof ServerLevel server && survey != null) {
            publishSurvey(server, survey.settings(), "The quarry unloaded before the material survey finished.", null);
        }
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
        tag.putUUID(MENU_REVISION_KEY, menuRevision);
        tag.put("Finish", finish.save());
        if (surveyResult != null) {
            CompoundTag cached = surveyResult.report().save();
            surveyResult.settings().save(cached);
            tag.put("MaterialSurvey", cached);
        }
        if (hasLastOutput) {
            tag.put(LAST_OUTPUT_KEY, QuarryMaterials.saveOutput(lastOutput));
        }
        if (excavation != null) {
            tag.put("Excavation", excavation.save());
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        hasSavedMenuRevision = tag.hasUUID(MENU_REVISION_KEY);
        if (hasSavedMenuRevision) {
            menuRevision = tag.getUUID(MENU_REVISION_KEY);
        }
        QuarryPlan.Settings saved = QuarryPlan.Settings.load(tag);
        if (saved != null) {
            settings = saved;
        }
        CompoundTag cached = tag.getCompound("MaterialSurvey");
        QuarryPlan.Settings surveyed = QuarryPlan.Settings.load(cached);
        surveyResult = surveyed == null ? null : new SurveyResult(surveyed, QuarryMaterials.Report.load(cached));
        excavation = QuarryExcavation.load(tag.getCompound("Excavation"));
        finish = QuarryMaterials.Finish.load(tag.getCompound("Finish"));
        hasLastOutput = tag.contains(LAST_OUTPUT_KEY);
        lastOutput = QuarryMaterials.loadOutput(tag.getCompound(LAST_OUTPUT_KEY));
    }

    private record Survey(QuarryExcavation job, QuarryPlan.Settings settings) {}

    private record SurveyResult(QuarryPlan.Settings settings, QuarryMaterials.Report report) {}
}
