package io.github.je55pr.bloodgrassindustries;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.pipeline.VertexConsumerWrapper;

@EventBusSubscriber(modid = BloodgrassIndustries.ID, value = Dist.CLIENT)
public final class BeltPlacementPreview {
    private BeltPlacementPreview() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        var player = game.player;
        if (game.level == null || player == null || game.screen != null || player.isSpectator() || game.options.hideGui
                || !(game.hitResult instanceof BlockHitResult hit)) {
            return;
        }
        InteractionHand hand = player.getMainHandItem().getItem() instanceof BeltItem
                ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (!(player.getItemInHand(hand).getItem() instanceof BeltItem item)) {
            return;
        }
        BeltItem.Preview preview = item.preview(player, hand, hit);
        if (preview == null) {
            return;
        }

        var pos = preview.pos();
        var camera = event.getCamera().getPosition();
        var pose = event.getPoseStack();
        var buffers = game.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(pos.getX() - camera.x, pos.getY() - camera.y, pos.getZ() - camera.z);
        if (preview.valid()) {
            RenderType layer = Sheets.translucentItemSheet();
            var transparent = new VertexConsumerWrapper(buffers.getBuffer(layer)) {
                @Override
                public VertexConsumer setColor(int r, int g, int b, int a) {
                    return super.setColor(r, g, b, Math.min(a, 104));
                }
            };
            MultiBufferSource ghost = ignored -> transparent;
            game.getBlockRenderer().renderSingleBlock(preview.state(), pose, ghost, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, layer);
            buffers.endBatch(layer);
        }

        var outline = preview.state().getShape(game.level, pos, CollisionContext.of(player));
        LevelRenderer.renderVoxelShape(pose, buffers.getBuffer(RenderType.lines()), outline, 0, 0.003, 0,
                preview.valid() ? 0.38F : 1F, preview.valid() ? 0.85F : 0.24F, 0.38F, 0.85F, true);
        buffers.endBatch(RenderType.lines());
        pose.popPose();
    }
}
