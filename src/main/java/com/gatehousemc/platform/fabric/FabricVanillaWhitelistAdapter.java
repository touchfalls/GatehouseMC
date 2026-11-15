package com.gatehousemc.platform.fabric;

import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.port.VanillaWhitelistPort;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserWhiteListEntry;

import java.util.concurrent.CompletableFuture;

public final class FabricVanillaWhitelistAdapter implements VanillaWhitelistPort {
    private final MinecraftServer server;

    public FabricVanillaWhitelistAdapter(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) {
        return onServerThread(() -> {
            boolean allowed = server.getPlayerList().getWhiteList().isWhiteListed(profile(identity));
            if (!allowed && server.usesAuthentication()) {
                allowed = server.getPlayerList().getWhiteList().isWhiteListed(new NameAndId(identity.offlineUuid(), identity.exactUsername()));
            }
            return allowed;
        });
    }

    @Override
    public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
        return onServerThread(() -> {
            server.getPlayerList().getWhiteList().add(new UserWhiteListEntry(profile(identity)));
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> removeExactProfile(PlayerIdentity identity) {
        return onServerThread(() -> {
            server.getPlayerList().getWhiteList().remove(profile(identity));
            if (server.usesAuthentication()) {
                server.getPlayerList().getWhiteList().remove(new NameAndId(identity.offlineUuid(), identity.exactUsername()));
            }
            return null;
        });
    }

    private NameAndId profile(PlayerIdentity identity) {
        if (server.usesAuthentication()) {
            java.util.Optional<NameAndId> cached = server.services().nameToIdCache().get(identity.exactUsername());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        return new NameAndId(identity.offlineUuid(), identity.exactUsername());
    }

    private <T> CompletableFuture<T> onServerThread(java.util.function.Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
