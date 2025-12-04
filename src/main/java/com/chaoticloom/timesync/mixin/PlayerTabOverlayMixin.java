package com.chaoticloom.timesync.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {
    @Unique
    private static final ResourceLocation ICONS = new ResourceLocation("textures/gui/icons.png");

    @Inject(method = "renderPingIcon", at = @At("HEAD"), cancellable = true)
    private void renderCustomPingIcon(GuiGraphics guiGraphics, int widthOffset, int x, int y, PlayerInfo playerInfo, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();

        // Check if we are in a Singleplayer world (Integrated Server exists)
        // AND that the server is NOT published to LAN.
        if (mc.hasSingleplayerServer() && mc.getSingleplayerServer() != null && !mc.getSingleplayerServer().isPublished()) {

            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, 100.0F);

            // Draw the "Disconnected" Red X icon.
            // In vanilla logic, this icon is used when latency < 0 (index 5).
            // Texture V coordinate = 176 + (5 * 8) = 216.
            // The X position calculation matches the original method: x + widthOffset - 11

            guiGraphics.blit(ICONS, x + widthOffset - 11, y, 0, 216, 10, 8);

            guiGraphics.pose().popPose();

            // Cancel the vanilla logic so it doesn't draw the normal bars on top
            ci.cancel();
        }
    }
}
