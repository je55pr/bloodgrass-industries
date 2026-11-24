package io.github.je55pr.bloodgrassindustries;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = BloodgrassIndustries.ID, value = Dist.CLIENT)
public final class QuarryFlyMode {
    private static final int OUTLINE = 0xffffbc55;
    private static final int PREVIEW_REFRESH_TICKS = 3;
    private static QuarryFlyMode active;
    private static QuarryScreen pending;

    private final Minecraft game;
    private final ClientLevel level;
    private final QuarryScreen editor;
    private final BlockPos origin;
    private final CameraType previousCameraType;
    private boolean showCut = true;
    private boolean returning;
    private boolean previewDirty = true;
    private int previewCooldown;
    private QuarryPlan renderedPlan;
    private QuarryMaterials.Report seenReport;
    private Block renderedSupport = Blocks.AIR;
    private BlockPos target;
    private int field;
    private String geometryStatus = "";
    private String feedback = "";

    private QuarryFlyMode(Minecraft game, QuarryScreen editor) {
        this.game = game;
        this.level = game.level;
        this.editor = editor;
        origin = editor.getMenu().position;
        previousCameraType = game.options.getCameraType();
    }

    static void open(QuarryScreen editor) {
        Minecraft game = Minecraft.getInstance();
        if (active != null || pending != null || game.level == null || game.player == null
                || !game.player.isCreative() || game.player.containerMenu != editor.getMenu()) {
            return;
        }
        pending = editor;
        PacketDistributor.sendToServer(new QuarrySurvey.Request(editor.getMenu().containerId, true));
    }

    static void receive(QuarrySurvey.State message) {
        Minecraft game = Minecraft.getInstance();
        if (active != null && active.editor.getMenu().containerId == message.containerId()) {
            if (!message.surveying()) {
                active.finish(true);
            } else {
                active.returning = false;
            }
            return;
        }
        if (pending == null || pending.getMenu().containerId != message.containerId()) {
            if (message.surveying() && game.player != null && game.player.containerMenu instanceof QuarryMenu menu
                    && menu.containerId == message.containerId()) {
                PacketDistributor.sendToServer(new QuarrySurvey.Request(menu.containerId, false));
            }
            return;
        }
        QuarryScreen editor = pending;
        pending = null;
        if (message.surveying() && game.level != null && game.player != null && game.player.containerMenu == editor.getMenu()) {
            active = new QuarryFlyMode(game, editor);
            game.options.setCameraType(CameraType.FIRST_PERSON);
            game.setScreen(null);
            active.updatePreview();
        }
    }

    private void finish(boolean returnToEditor) {
        active = null;
        QuarryRenderCut.show(game, level, origin, null, QuarryMaterials.Finish.NATURAL, Blocks.AIR);
        game.options.setCameraType(previousCameraType);
        if (returnToEditor && game.player != null && game.player.containerMenu == editor.getMenu()) {
            game.setScreen(editor);
        }
    }

    public static boolean handleKey(long window, int key, int scanCode, int action, int modifiers) {
        QuarryFlyMode mode = active;
        if (mode == null || window != mode.game.getWindow().getWindow() || key == GLFW.GLFW_KEY_F2) {
            return false;
        }
        if (action == GLFW.GLFW_PRESS) {
            switch (key) {
                case GLFW.GLFW_KEY_ESCAPE -> {
                    if (!mode.returning) {
                        mode.returning = true;
                        PacketDistributor.sendToServer(new QuarrySurvey.Request(mode.editor.getMenu().containerId, false));
                    }
                }
                case GLFW.GLFW_KEY_TAB -> mode.field = (mode.field + 1) % QuarryScreen.LABELS.length;
                case GLFW.GLFW_KEY_V -> {
                    mode.showCut = !mode.showCut;
                    mode.queuePreviewUpdate();
                }
                case GLFW.GLFW_KEY_Y -> mode.pickBaseY();
                default -> { }
            }
        }
        if ((action == GLFW.GLFW_PRESS || action == GLFW.GLFW_REPEAT)
                && (key == GLFW.GLFW_KEY_LEFT_BRACKET || key == GLFW.GLFW_KEY_RIGHT_BRACKET)) {
            int amount = (modifiers & GLFW.GLFW_MOD_SHIFT) == 0 ? 1 : 5;
            mode.adjust(key == GLFW.GLFW_KEY_LEFT_BRACKET ? -amount : amount);
        }
        var keys = mode.game.options;
        return !(keys.keyUp.matches(key, scanCode) || keys.keyDown.matches(key, scanCode)
                || keys.keyLeft.matches(key, scanCode) || keys.keyRight.matches(key, scanCode)
                || keys.keyJump.matches(key, scanCode) || keys.keyShift.matches(key, scanCode)
                || keys.keySprint.matches(key, scanCode));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (pending != null && Minecraft.getInstance().screen != pending) {
            pending = null;
        }
        QuarryFlyMode mode = active;
        if (mode == null) {
            return;
        }
        if (mode.game.level != mode.level || mode.game.player == null || mode.game.player.containerMenu != mode.editor.getMenu()
                || mode.game.screen != null || (!mode.returning && !mode.game.player.isSpectator())) {
            if (mode.game.player != null && mode.game.player.containerMenu == mode.editor.getMenu()) {
                PacketDistributor.sendToServer(new QuarrySurvey.Request(mode.editor.getMenu().containerId, false));
            }
            mode.finish(false);
            return;
        }
        mode.updatePreview();
        mode.target = mode.pointedBlock();
    }

    @SubscribeEvent
    public static void onClick(InputEvent.MouseButton.Pre event) {
        if (active == null) {
            return;
        }
        int button = event.getButton();
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT && button != GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            var keys = active.game.options;
            if (keys.keyUp.matchesMouse(button) || keys.keyDown.matchesMouse(button) || keys.keyLeft.matchesMouse(button)
                    || keys.keyRight.matchesMouse(button) || keys.keyJump.matchesMouse(button) || keys.keyShift.matchesMouse(button)
                    || keys.keySprint.matchesMouse(button)) {
                return;
            }
        }
        event.setCanceled(true);
        if (event.getAction() == GLFW.GLFW_PRESS) {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                active.markCorner();
            } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && active.editor.undoCorner()) {
                active.queuePreviewUpdate();
            }
        }
    }

    private void markCorner() {
        BlockPos picked = pointedBlock();
        if (picked == null) {
            feedback = "Aim at a block to mark a corner.";
            return;
        }
        int dx = picked.getX() - origin.getX();
        int dz = picked.getZ() - origin.getZ();
        if (editor.addCorner(new QuarryPlan.Corner(dx, dz))) {
            queuePreviewUpdate();
        } else {
            feedback = "Choose a different block. The outline holds up to 64 corners.";
        }
    }

    private void pickBaseY() {
        BlockPos hit = pointedBlock();
        if (hit != null) {
            field = 0;
            if (editor.setValue(0, hit.getY())) {
                queuePreviewUpdate();
            }
        }
    }

    private void adjust(int amount) {
        int min = field == 0 ? level.getMinBuildHeight() : 1;
        int max = field == 0 ? level.getMaxBuildHeight() - 1 : QuarryPlan.MAX_SETTING;
        if (editor.setValue(field, Mth.clamp(editor.numericValue(field) + amount, min, max))) {
            queuePreviewUpdate();
        }
    }

    private void queuePreviewUpdate() {
        feedback = "";
        previewDirty = true;
    }

    private void updatePreview() {
        if (seenReport != editor.getMenu().materialReport) {
            seenReport = editor.getMenu().materialReport;
            previewDirty = true;
        }
        if (previewCooldown > 0) {
            previewCooldown--;
        }
        if (!previewDirty || previewCooldown != 0) {
            return;
        }
        previewDirty = false;
        previewCooldown = PREVIEW_REFRESH_TICKS;
        QuarryPlan plan = editor.plan();
        QuarryPlan next = showCut ? plan : null;
        QuarryMaterials.Finish finish = editor.finish();
        QuarryMaterials.Allocation allocation = next == null ? null : editor.allocation();
        Block support = allocation == null ? Blocks.AIR : allocation.support();
        geometryStatus = previewStatus(next, finish, allocation);
        if (next != renderedPlan || support != renderedSupport) {
            renderedPlan = next;
            renderedSupport = support;
            QuarryRenderCut.show(game, level, origin, next, finish, support);
        }
    }

    private String previewStatus(QuarryPlan plan, QuarryMaterials.Finish finish, QuarryMaterials.Allocation allocation) {
        if (plan != null && allocation == null && (finish.road() instanceof FallingBlock || finish.wall() instanceof FallingBlock)) {
            var report = editor.report();
            if (report == null) {
                String status = editor.materialStatus();
                return status.isEmpty() ? "Survey the current plan to preview falling-block supports." : status;
            }
            return report.surfaces().cannotSupport(finish) ? QuarryMaterials.NO_SUPPORT_SPACE : QuarryMaterials.INSUFFICIENT_FINISHES;
        }
        return editor.status();
    }

    private BlockPos pointedBlock() {
        Vec3 start = game.player.getEyePosition();
        Vec3 end = start.add(game.player.getLookAngle().scale(256));
        BlockGetter terrain = new PreviewTerrain(level, QuarryRenderCut.snapshot(level));
        var hit = terrain.clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, game.player));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    @SubscribeEvent
    public static void hud(RenderGuiEvent.Post event) {
        if (active != null) {
            active.drawHud(event.getGuiGraphics());
        }
    }

    @SubscribeEvent
    public static void hideHand(RenderHandEvent event) {
        if (active != null) {
            event.setCanceled(true);
        }
    }

    private void drawHud(GuiGraphics graphics) {
        int width = game.getWindow().getGuiScaledWidth();
        int height = game.getWindow().getGuiScaledHeight();
        graphics.fill(6, 6, Math.min(width - 6, 330), 70, 0xb216191e);
        graphics.drawString(game.font, renderedPlan != null ? "Quarry preview" : "Quarry outline",
                11, 11, OUTLINE);
        graphics.drawString(game.font, "Left click mark   Right click undo   V terrain/cut   Esc return", 11, 22, 0xffeeeeee);
        graphics.drawString(game.font, "Move + look   Jump / sneak up and down   Scroll speed", 11, 33, 0xffeeeeee);
        graphics.drawString(game.font, "Tab select   [ / ] adjust   Y pick base from block", 11, 44, 0xffeeeeee);
        int x = 11;
        for (int i = 0; i < QuarryScreen.LABELS.length; i++) {
            String setting = QuarryScreen.LABELS[i] + " " + editor.value(i);
            graphics.drawString(game.font, setting, x, 57, i == field ? OUTLINE : 0xffeeeeee);
            x += game.font.width(setting) + 13;
        }
        String status = feedback.isEmpty() ? geometryStatus : feedback;
        graphics.fill(6, height - 25, width - 6, height - 6, 0xb216191e);
        graphics.drawString(game.font, editor.corners().size() + " corners  |  " + status, 11, height - 20, 0xffeeeeee);
        int centerX = width / 2;
        int centerY = height / 2;
        graphics.fill(centerX - 4, centerY, centerX + 5, centerY + 1, OUTLINE);
        graphics.fill(centerX, centerY - 4, centerX + 1, centerY + 5, OUTLINE);
    }

    @SubscribeEvent
    public static void outline(RenderLevelStageEvent event) {
        if (active != null && event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            active.drawOutline(event);
        }
    }

    private void drawOutline(RenderLevelStageEvent event) {
        List<Vec3> points = new ArrayList<>();
        QuarryPlan plan = renderedPlan;
        for (QuarryPlan.Corner corner : editor.corners()) {
            BlockPos pos = origin.offset(corner.x(), 0, corner.z());
            int y = level.isLoaded(pos) ? level.getHeight(Heightmap.Types.WORLD_SURFACE, pos.getX(), pos.getZ()) : editor.numericValue(0) + 1;
            if (plan != null) {
                y = Math.min(y, plan.floorAt(corner.x(), corner.z()) + 1);
            }
            points.add(new Vec3(pos.getX() + 0.5, y + 0.12, pos.getZ() + 0.5));
        }
        if (points.isEmpty() && target == null) {
            return;
        }
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        Matrix4f pose = event.getPoseStack().last().pose();
        Vec3 view = event.getCamera().getPosition();
        for (int i = 0; i < points.size(); i++) {
            Vec3 point = points.get(i);
            line(buffer, pose, view, point, point.add(0, 1.5, 0), OUTLINE);
            if (i != 0 || points.size() >= 3) {
                Vec3 previous = i == 0 ? points.getLast() : points.get(i - 1);
                line(buffer, pose, view, previous, point, OUTLINE);
            }
        }
        if (target != null) {
            Vec3 first = new Vec3(target.getX(), target.getY() + 1.02, target.getZ());
            Vec3 second = first.add(1, 0, 0);
            Vec3 third = first.add(1, 0, 1);
            Vec3 fourth = first.add(0, 0, 1);
            int color = 0xfff8e6b9;
            line(buffer, pose, view, first, second, color);
            line(buffer, pose, view, second, third, color);
            line(buffer, pose, view, third, fourth, color);
            line(buffer, pose, view, fourth, first, color);
        }
        BufferUploader.drawWithShader(buffer.buildOrThrow());
        RenderSystem.enableDepthTest();
    }

    private static void line(BufferBuilder buffer, Matrix4f pose, Vec3 view, Vec3 start, Vec3 end, int color) {
        buffer.addVertex(pose, (float) (start.x - view.x), (float) (start.y - view.y), (float) (start.z - view.z)).setColor(color);
        buffer.addVertex(pose, (float) (end.x - view.x), (float) (end.y - view.y), (float) (end.z - view.z)).setColor(color);
    }

    private record PreviewTerrain(ClientLevel level, QuarryRenderCut.Snapshot cut) implements BlockGetter {
        @Override
        public BlockState getBlockState(BlockPos pos) {
            BlockState state = level.isLoaded(pos) ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
            return cut == null ? state : cut.visible(this, pos, state);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return level.getBlockEntity(pos);
        }

        @Override
        public int getMinBuildHeight() {
            return level.getMinBuildHeight();
        }

        @Override
        public int getHeight() {
            return level.getHeight();
        }
    }
}
