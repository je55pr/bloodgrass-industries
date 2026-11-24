package io.github.je55pr.bloodgrassindustries.mixin;

import io.github.je55pr.bloodgrassindustries.QuarryRenderCut;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RenderChunkRegion.class)
abstract class QuarryChunkMixin implements BlockAndTintGetter {
    @Shadow @Final protected Level level;
    @Unique private QuarryRenderCut.Snapshot bloodgrass$cut;

    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;II[Lnet/minecraft/client/renderer/chunk/RenderChunk;Lit/unimi/dsi/fastutil/longs/Long2ObjectFunction;)V",
            at = @At("RETURN"))
    private void bloodgrass$captureCut(CallbackInfo info) {
        bloodgrass$cut = QuarryRenderCut.snapshot(level);
    }

    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void bloodgrass$previewBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> info) {
        if (bloodgrass$cut != null) {
            BlockState state = info.getReturnValue();
            BlockState visible = bloodgrass$cut.visible(this, pos, state);
            if (visible != state) {
                info.setReturnValue(visible);
            }
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void bloodgrass$previewFluids(BlockPos pos, CallbackInfoReturnable<FluidState> info) {
        if (bloodgrass$cut != null) {
            info.setReturnValue(getBlockState(pos).getFluidState());
        }
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        if (layer == LightLayer.SKY && bloodgrass$cut != null && bloodgrass$cut.hasPreviewSkylight(pos)) {
            return getMaxLightLevel();
        }
        return BlockAndTintGetter.super.getBrightness(layer, pos);
    }
}
