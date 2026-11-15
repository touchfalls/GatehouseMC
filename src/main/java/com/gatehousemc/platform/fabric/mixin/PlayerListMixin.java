package com.gatehousemc.platform.fabric.mixin;

import com.gatehousemc.platform.fabric.GatehouseMod;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "canPlayerLogin", at = @At("RETURN"), cancellable = true, require = 1)
    private void gatehousemc$afterCanPlayerLogin(SocketAddress address, NameAndId profile,
                                                  CallbackInfoReturnable<Component> callback) {
        Component result = callback.getReturnValue();
        if (result == null || !(result.getContents() instanceof TranslatableContents translatable)) return;
        if (!"multiplayer.disconnect.not_whitelisted".equals(translatable.getKey())) return;
        callback.setReturnValue(GatehouseMod.handleWhitelistDenial(profile));
    }
}
