package com.chaoticloom.timesync.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Gui.class)
public class GuiMixin {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;isLocalServer()Z"
            )
    )
    private boolean forceTabListRender(Minecraft instance) {
        // We pretend we are NOT a local server (Singleplayer).
        // This forces the GUI to treat the session like a Multiplayer server
        // regarding the Tab list logic, effectively bypassing the "> 1 player" check.
        return false;
    }
}
