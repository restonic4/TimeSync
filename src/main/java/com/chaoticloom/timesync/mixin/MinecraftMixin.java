package com.chaoticloom.timesync.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Shadow
    public abstract void setScreen(@Nullable Screen screen);

    @Inject(method = "pauseGame", at = @At("HEAD"), cancellable = true)
    private void preventGamePause(boolean pause, CallbackInfo ci) {
        if (pause) {
            this.setScreen(new PauseScreen(true));
            ci.cancel();
        }
    }

    @Redirect(
            method = "runTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/server/IntegratedServer;isPublished()Z"
            )
    )
    private boolean forcePublishedInTick(IntegratedServer server) {
        // We lie to the client tick loop and say the server is published (Open to LAN).
        // This prevents the game from entering the pause state while keeping all other logic intact.
        return true;
    }
}
