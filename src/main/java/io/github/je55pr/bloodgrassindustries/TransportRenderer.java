package io.github.je55pr.bloodgrassindustries;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = BloodgrassIndustries.ID, value = Dist.CLIENT)
public final class TransportRenderer implements BlockEntityRenderer<TransportBlockEntity> {
    private final ItemRenderer items;

    private TransportRenderer(BlockEntityRendererProvider.Context context) {
        items = context.getItemRenderer();
    }

    @SubscribeEvent
    public static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(BloodgrassIndustries.TRANSPORT_ENTITY.get(), TransportRenderer::new);
    }

    @Override
    public void render(TransportBlockEntity transport, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int light, int overlay) {
        double time = transport.visualTime(partialTick);
        TransportBlockEntity.VisualFrame frame = transport.visualFrame(time);
        for (int slot = 0; slot < TransportBlockEntity.SLOT_COUNT; slot++) {
            ItemStack stack = frame.cargo().get(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Vec3 point = transport.renderPosition(frame, slot, time);
            BakedModel model = items.getModel(stack, transport.getLevel(), null, 0);
            pose.pushPose();
            pose.translate(point.x, point.y + (model.isGui3d() ? 0.115 : 0.035), point.z);
            if (!model.isGui3d()) {
                pose.mulPose(Axis.XP.rotationDegrees(90));
            }
            pose.scale(0.34F, 0.34F, 0.34F);
            items.render(stack, ItemDisplayContext.FIXED, false, pose, buffers, light, overlay, model);
            pose.popPose();
        }
    }

    @Override
    public AABB getRenderBoundingBox(TransportBlockEntity transport) {
        return new AABB(transport.getBlockPos()).expandTowards(0, 0.5, 0);
    }
}
