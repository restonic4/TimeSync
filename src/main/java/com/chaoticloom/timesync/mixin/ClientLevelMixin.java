package com.chaoticloom.timesync.mixin;

import com.chaoticloom.timesync.TimeSync;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ClientLevel.class)
public class ClientLevelMixin {
    @Redirect(method = "tickTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;setDayTime(J)V"))
    private void syncTimeWithRealWorld(ClientLevel clientLevel, long newValue) {
        clientLevel.setDayTime(TimeSync.getCurrentTime());
    }
}
