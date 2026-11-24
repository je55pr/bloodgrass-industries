package io.github.je55pr.bloodgrassindustries.mixin;

import io.github.je55pr.bloodgrassindustries.QuarryFlyMode;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class QuarryKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void bloodgrass$handleSurveyKeys(long window, int key, int scanCode, int action, int modifiers, CallbackInfo info) {
        if (QuarryFlyMode.handleKey(window, key, scanCode, action, modifiers)) {
            info.cancel();
        }
    }
}
