package com.chaoticloom.timesync.mixin;

import com.chaoticloom.timesync.TimeSync;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.time.LocalTime;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Redirect(method = "tickTime", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setDayTime(J)V"))
    private void syncTimeWithRealWorld(ServerLevel serverLevel, long newValue) {
        serverLevel.setDayTime(TimeSync.getSyncedTime(serverLevel));
    }
}
